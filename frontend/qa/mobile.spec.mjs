import { test, expect } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { installApi } from './mobile-fixtures.mjs';

const screenshotRoot = path.resolve(process.env.LAB_SCREENSHOT_DIR || 'artifacts/mobile/screenshots');
async function saveScreen(page, info, name) {
  const directory = path.join(screenshotRoot, info.project.name);
  await mkdir(directory, { recursive: true });
  await page.screenshot({ path: path.join(directory, name + '.png'), fullPage: true });
}
async function layout(page) {
  const metrics = await page.evaluate(() => {
    const width = document.documentElement.clientWidth;
    const isVisible = el => el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';
    const escaped = [...document.querySelectorAll('.content > *, .panel, .mg-surface, .sidebar, .mobile-nav-toggle, .login-panel')]
      .filter(isVisible).filter(el => el.getBoundingClientRect().left < -1 || el.getBoundingClientRect().right > width + 1)
      .map(el => ({ class: el.className, left: el.getBoundingClientRect().left, right: el.getBoundingClientRect().right }));
    return { width, scroll: document.documentElement.scrollWidth, escaped };
  });
  expect(metrics.escaped).toEqual([]);
  expect(metrics.scroll).toBeLessThanOrEqual(metrics.width + 1);
}
async function navigate(page, id) {
  const toggle = page.getByRole('button', { name: /功能导航/ });
  if (await toggle.isVisible()) await toggle.click();
  const target = page.locator(`#workspace-navigation [data-page="${id}"]`);
  await expect(target).toBeVisible();
  await target.click();
  await expect(target).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('.content [aria-busy="true"]')).toHaveCount(0);
}
async function scrollRegions(page) {
  const regions = page.locator('.table-scroll, .mg-table-wrap, .week-grid-wrap, .duty-week-scroll, .floor-plan-scroll');
  for (const region of await regions.all()) {
    if (!await region.isVisible()) continue;
    const overflow = await region.evaluate(el => el.scrollWidth > el.clientWidth);
    if (!overflow) continue;
    await region.evaluate(el => { el.scrollLeft = el.scrollWidth; });
    expect(await region.evaluate(el => Math.abs(el.scrollWidth - el.clientWidth - el.scrollLeft))).toBeLessThanOrEqual(2);
    const last = region.locator('thead th:last-child, button.seat:last-child').last();
    if (await last.count()) {
      const box = await region.boundingBox(), end = await last.boundingBox();
      expect(end.right ?? end.x + end.width).toBeLessThanOrEqual(box.x + box.width + 2);
    }
    await region.evaluate(el => { el.scrollLeft = 0; });
  }
}

for (const role of ['ADMIN', 'MEMBER']) test(`${role}: every feature reachable and contained`, async ({ page }, info) => {
  const api = await installApi(page, role);
  await page.goto('/');
  await expect(page.locator('#workspace-content')).toBeVisible();
  const ids = role === 'ADMIN' ? ['home','members','seats','applications','reviews','adminReports','duty','logs','projects'] : ['home','seats','booking','reports','special','notices','courses','projects'];
  for (const id of ids) {
    await navigate(page, id);
    await layout(page);
    if (id === 'reports') await page.locator('.task-button').first().click();
    if (id === 'seats') {
      await page.locator('.floor-plan .seat').first().click();
      await expect(page.locator('.seat-detail')).toBeVisible();
      if (role === 'MEMBER') await expect(page.locator('.booking-drawer')).toBeVisible();
    }
    await scrollRegions(page);
    await layout(page);
    await saveScreen(page, info, `${role.toLowerCase()}-${id}`);
  }
  // Closing/reopening and resizing must keep every entry (including booking) available.
  if (page.viewportSize().width <= 900) {
    await page.getByRole('button', { name: /功能导航/ }).click();
    await expect(page.locator('.sidebar-links button:visible')).toHaveCount(ids.length);
    await saveScreen(page, info, role.toLowerCase() + '-all-functions');
    await page.locator('.sidebar-links button').last().focus();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('button', { name: /功能导航/ })).toBeFocused();
  }
  await page.setViewportSize({ width: 1280, height: 600 });
  await expect(page.locator('.sidebar-links button:visible')).toHaveCount(ids.length);
  const last = page.locator('.sidebar-links button').last();
  await last.scrollIntoViewIfNeeded();
  await expect(last).toBeInViewport();
  await layout(page);
  expect(api.unexpected).toEqual([]);
  expect(api.errors).toEqual([]);
});

test('course editor, project tabs and report details remain usable', async ({ page }, info) => {
  const api = await installApi(page, 'MEMBER');
  await page.goto('/');
  await navigate(page, 'courses');
  await page.getByRole('button', { name: '＋ 手动添加课程' }).click();
  await page.getByLabel('课程名称').fill('移动端课程编辑验证');
  await page.getByRole('button', { name: '应用', exact: true }).click();
  await layout(page);
  const columns = await page.locator('.mg-form-grid.three label').evaluateAll(elements => elements.map(el => Math.round(el.getBoundingClientRect().left)));
  if (page.viewportSize().width <= 700) expect(new Set(columns).size).toBe(1);
  await saveScreen(page, info, 'member-course-editor');
  await page.getByRole('button', { name: '添加课程', exact: true }).click();
  await expect(page.locator('.courses-page')).toHaveAttribute('aria-busy', 'false');
  expect(api.writes.some(w => w.path === '/courses/save')).toBe(true);
  await page.locator('input[type="file"]').setInputFiles({ name: 'phone.ics', mimeType: 'text/calendar', buffer: Buffer.from('BEGIN:VCALENDAR\nEND:VCALENDAR') });
  await expect(page.locator('.mg-notice').first()).toContainText('导入');
  expect(api.writes.some(w => w.path === '/courses/import')).toBe(true);
  await navigate(page, 'projects');
  await page.getByRole('button', { name: '查看项目 →' }).click();
  for (const label of ['项目概况','团队成员','我的申请']) {
    await page.getByRole('button', { name: new RegExp(label) }).click();
    await layout(page);
    await saveScreen(page, info, 'member-project-' + label);
  }
  await page.getByLabel('为什么想加入').fill('希望参与实验室项目');
  await page.getByLabel('已有技能').fill('Python');
  await page.getByLabel('可投入时间').fill('每周六小时');
  await page.getByRole('button', { name: '提交加入申请' }).click();
  await expect.poll(() => api.writes.some(w => w.path === '/projects/1/apply')).toBe(true);
  await page.getByRole('button', { name: '修改密码' }).click();
  await layout(page);
  await saveScreen(page, info, 'password-dialog');
  await page.locator('.modal-close').click();
  expect(api.unexpected).toEqual([]);
  expect(api.errors).toEqual([]);
});

test('manual duty can select the same member in multiple daily slots', async ({ page }, info) => {
  const api = await installApi(page, 'ADMIN');
  await page.goto('/');
  await navigate(page, 'duty');
  await expect(page.locator('.duty-week-grid tbody tr')).toHaveCount(5);
  for (const period of [1,3]) {
    await page.locator('.duty-slot').filter({ hasText: '点击选择值班同学' }).first().waitFor();
    await page.getByRole('button', { name: new RegExp(`2026-10-12第${period}至`) }).click();
    await page.getByLabel('选择空闲学生').selectOption('2');
    await page.getByRole('button', { name: '安排值班', exact: true }).click();
    await expect(page.locator('.duty-admin')).toHaveAttribute('aria-busy','false');
  }
  expect(api.writes.filter(w => w.path === '/duty/admin/assign').map(w => w.body)).toEqual([
    { date: '2026-10-12', periodNo: 1, userId: 2 }, { date: '2026-10-12', periodNo: 3, userId: 2 }
  ]);
  await layout(page);
  await saveScreen(page, info, 'admin-manual-duty-multiple');
  await navigate(page, 'adminReports');
  await page.getByRole('button', { name: '查看材料 →' }).click();
  await expect(page.locator('.report-document')).toBeVisible();
  await layout(page);
  await saveScreen(page, info, 'admin-report-document');
  expect(api.unexpected).toEqual([]);
  expect(api.errors).toEqual([]);
});

test('login and registration fit small screens', async ({ page }, info) => {
  const api = await installApi(page, null);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible();
  await layout(page);
  await saveScreen(page, info, 'login');
  await page.getByRole('button', { name: '注册实验室成员账号' }).click();
  await expect(page.getByRole('heading', { name: '提交成员申请' })).toBeVisible();
  await layout(page);
  await saveScreen(page, info, 'registration');
  expect(api.unexpected).toEqual([]);
  expect(api.errors).toEqual([]);
});
