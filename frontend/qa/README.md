# 管理页面验收

运行 `npm run dev -- --host 127.0.0.1 --port 5177`，打开 `/qa/management.html`。
该页面挂载真实的项目和报告组件，使用本地模拟数据，不访问生产接口，也不写入数据库。

验收项：项目列表、详情页签、报名筛选、成员名单、管理员报告列表、姓名筛选、历史报告、草稿提示、不安全成果链接不可点击、390px 窄屏排版。
权限与数据库查询由 `AdminReportControllerTest` 和 `ProjectControllerTest` 使用临时 SQLite 数据库测试。
此目录不在生产构建入口或发布文件清单中。

## 手机与导航回归

在 `frontend` 目录执行：

```powershell
npm ci
npm run typecheck
npm run test:mobile
```

默认使用本机已安装的 Chrome。使用 Edge 时，先设置 `$env:LAB_BROWSER_CHANNEL='msedge'`；
使用 Playwright 自带浏览器时，先执行 `npx playwright install chromium`，再设置
`$env:LAB_BROWSER_CHANNEL='chromium'`。

测试自动构建生产前端并在 `127.0.0.1:5186` 启动预览；所有 `/api/` 请求由
`mobile-fixtures.mjs` 在内存中响应，不连接服务器或真实数据库。

- 五种视口：320×740、390×844、768×1024、900×440、1440×900。
- 遍历管理员 9 个、成员 8 个功能入口，验证导航展开、收起、Esc 和窗口尺寸切换。
- 验证页面没有整体横向溢出，工位图最右侧和宽表格最后一列能在各自区域内滚动到达。
- 操作课程编辑、ICS 文件选择、项目详情与报名、密码弹窗、报告档案与同日多次手动排班。
- 两个预约入口覆盖提交成功、冲突拒绝、请求超时、提交中禁止重复请求、列表刷新失败；
  验证结果在按钮旁持续显示，且顶部导航不会遮挡提示。
- 每次运行只把 PNG 写入 `frontend/artifacts/mobile/screenshots/`，不在工具响应中输出图片。
  可用 `LAB_SCREENSHOT_DIR` 指定其他本地目录；截图和 JSON 报告已加入 Git 忽略规则。

这些检查基于 Chrome 的手机视口与触摸模拟，不替代实体 iPhone / Android 设备验收。
