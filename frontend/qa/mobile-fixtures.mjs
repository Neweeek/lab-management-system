// All browser tests use in-memory APIs. They never log in to or mutate a real database.
const term = { id: 1, name: '2026–2027 秋季学期', start_date: '2026-09-07', end_date: '2027-01-24', is_current: 1 };
const people = [
  { id: 2, student_no: '20260002', name: '陈同学', role: 'MEMBER', member_status: 'MOBILE', approved: 1 },
  { id: 3, student_no: '20260003', name: '李同学', role: 'MEMBER', member_status: 'ACTIVE', approved: 1, seat_code: 'A02' }
];
const times = [['08:30','09:15'],['09:20','10:05'],['10:25','11:10'],['11:15','12:00'],['14:00','14:45'],['14:50','15:35'],['15:55','16:40'],['16:45','17:30'],['19:00','19:45'],['19:50','20:35']];
const periods = times.map(([start_at, end_at], i) => ({ period_no: i + 1, start_at, end_at, section_no: Math.floor(i / 2) + 1 }));
const seats = Array.from({ length: 32 }, (_, i) => ({ id: i + 1, code: 'A' + String(i + 1).padStart(2,'0'), row_no: Math.floor(i/8) + 1, col_no: i % 8 + 1, type: i === 1 ? 'FIXED' : 'MOBILE', status: i === 1 ? 'OCCUPIED' : 'AVAILABLE', occupant_name: i === 1 ? '李同学' : null }));
const project = { id: 1, name: '全国大学生智能车竞赛：视觉导航与整车联调', type: 'COMPETITION', status: 'RECRUITING', description: '一起完成视觉感知、嵌入式控制与整车联调。整理实验数据、复核关键场景，并开展校园赛道测试。', requirements: '熟悉 C++ 或 Python，每周投入 6 小时，欢迎愿意动手调试的同学。', deadline: '2027-01-20', capacity: 6, member_count: 1, created_by: 1, creator_name: '管理员', created_at: '2026-10-12T10:00' };
const report = { id: 1, name: '李同学', student_no: '20260003', period_index: 6, period_start: '2026-10-12T00:00', due_at: '2026-10-18T23:59', status: 'DRAFT', review_status: 'ACTIVE', has_report: 1, can_submit: true };
const course = { id: 1, summary: '计算机网络与分布式系统实验', source: 'ICS', location: '实验楼 HE-405', meetings: [{ id: 1, weekday: 0, weekday_label: '周一', period_start: 1, period_end: 2, weeks: [1,2,3,4,5,6,7,8] }] };

export async function installApi(page, initialRole = 'ADMIN') {
  let role = initialRole;
  const duty = [];
  const writes = [];
  const unexpected = [];
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const user = () => role === 'ADMIN' ? { id: 1, name: '管理员', role: 'ADMIN', approved: 1 } : people[0];
  const week = (offset, admin) => {
    const monday = new Date('2026-10-12T12:00:00Z');
    monday.setUTCDate(monday.getUTCDate() + offset * 7);
    return {
      term, periods, duty, unimported: [], has_course_data: true, week_label: '10月12日 — 10月18日',
      days: Array.from({ length: 7 }, (_, i) => {
        const date = new Date(monday); date.setUTCDate(date.getUTCDate() + i);
        return { date: date.toISOString().slice(0,10), weekday: ['周一','周二','周三','周四','周五','周六','周日'][i], is_today: i === 3, week_number: 6 + offset,
          periods: periods.map(p => ({ period_no: p.period_no, schedulable: true, free_count: people.length, free_members: people, busy_members: [], busy: !admin && i === 0 && p.period_no <= 2, courses: [course] })) };
      })
    };
  };
  await page.clock.install({ time: new Date('2026-10-15T10:00:00+08:00') });
  await page.route('**/api/**', async route => {
    const req = route.request();
    const url = new URL(req.url());
    const path = url.pathname.slice(4);
    const reply = (body, status = 200) => route.fulfill({ status, json: body });
    if (req.method() !== 'GET') {
      const body = req.headers()['content-type']?.includes('text/calendar') ? req.postData() : req.postDataJSON();
      writes.push({ path, body });
      if (path === '/auth/login') { role = 'MEMBER'; return reply({ user: user(), csrfToken: 'test-csrf' }); }
      if (path === '/auth/logout') { role = null; return reply({ message: '已退出' }); }
      if (path === '/duty/admin/assign') duty.push({ assignment_id: duty.length + 1, date: body.date, period_no: body.periodNo, user_id: body.userId, name: people.find(p => p.id === body.userId).name, source: 'MANUAL' });
      if (path === '/courses/import') return reply({ message: '导入成功', added: 1, warnings: [], removedCourseNames: [] });
      return reply({ id: 1, message: '操作完成（本地模拟）' });
    }
    if (path === '/me') return role ? reply(user()) : reply({ message: '请先登录' }, 401);
    if (path === '/csrf') return reply({ csrfToken: 'test-csrf' });
    if (path === '/seats') return reply(seats);
    if (path === '/admin/overview') return reply({ availableSeats: 31, pendingRegistrations: 1, activeReviews: 1, awaitingDecision: 1 });
    if (path === '/admin/members') return reply([{ id: 1, name: '管理员', student_no: 'admin', role: 'ADMIN', approved: 1 }, ...people]);
    if (path === '/admin/registrations') return reply([{ id: 4, student_no: '20260004', name: '待审核同学', created_at: '2026-10-15T09:00', approved: 0 }]);
    if (path === '/admin/members/2/delete-preview') return reply({ user: people[0], seats: 0, bookings: 1, reports: 1, teams: 1, ownedProjects: 0 });
    if (path === '/admin/applications' || path === '/seat-applications/my') return reply([{ id: 1, name: '陈同学', code: 'A01', reason: '持续参与实验室科研和项目协作，申请固定工位。', status: 'PENDING' }]);
    if (path === '/admin/bookings' || path === '/seat-bookings/my') return reply([{ id: 1, name: '陈同学', code: 'A01', seat_id: 1, start_at: '2026-10-16T09:00', end_at: '2026-10-16T11:00', status: 'PENDING' }]);
    if (path === '/admin/reviews') return reply([{ id: 1, name: '李同学', code: 'A02', start_at: '2026-10-01T00:00', end_at: '2026-10-31T23:59', status: 'AWAITING_DECISION' }]);
    if (path === '/admin/audit-logs') return reply([{ id: 1, created_at: '2026-10-15T10:00', actor_name: '管理员', action: 'MANUAL_DUTY_ASSIGNMENT', target: 'member:20260002', detail: '管理员手动安排本周值班，保留既有课表与历史记录。' }]);
    if (path === '/seat-bookings/availability') return reply({ seats: seats.filter(s => s.type === 'MOBILE'), reservations: [{ id: 1, seat_id: 1, start_at: '2026-10-16T09:00', end_at: '2026-10-16T11:00', status: 'APPROVED', mine: false }] });
    if (path === '/report-tasks/my') return reply([report]);
    if (path === '/notifications') return reply([{ id: 1, title: '本周值班安排已更新', content: '请查看个人课程表中的值班时段；如有冲突，请联系管理员核对。', read_flag: 0, created_at: '2026-10-15T10:00' }]);
    if (path === '/special-circumstances/my') return reply([{ id: 1, type: '学科竞赛', description: '参加学校组织的比赛集中训练。', start_at: '2026-10-16T08:00', end_at: '2026-10-18T18:00' }]);
    if (path === '/courses/terms') return reply({ current: term, terms: [term] });
    if (path === '/courses/my') return reply({ courses: [course] });
    if (path === '/courses/week' || path === '/courses/admin/week') return reply(week(Number(url.searchParams.get('week') || 0), path.includes('/admin/')));
    if (path === '/duty/admin/day') return reply({ periods: [], total: 0 });
    if (path === '/duty/admin/load') return reply(people.map(p => ({ user_id: p.id, name: p.name, student_no: p.student_no, duty_count: duty.filter(d => d.user_id === p.id).length })));
    if (path === '/projects/mine') return reply([]);
    if (path === '/projects') return reply({ items: [project], total: 1, page: 1 });
    if (path === '/projects/1') return reply({ project, team: [{ id: 1, name: '管理员', joined_at: '2026-10-12' }], applications: role === 'ADMIN' ? [{ id: 1, name: '陈同学', user_id: 2, status: 'PENDING', reason: '希望参与嵌入式控制和算法联调。', skills: 'C++、Python', availability: '每周六小时', created_at: '2026-10-13T12:00' }] : [] });
    if (path === '/admin/reports') return reply({ items: [report], total: 1, page: 1 });
    if (path === '/admin/reports/1') return reply({ ...report, saved_status: 'DRAFT', submitted_at: '', reason: '固定工位阶段性学习成果考察', requirements: '记录本周进展和下一周计划。', sections: { learning: { title: '控制系统联调', detail: '完成视觉模块对接与三组不同光照场景下的测试，整理失效场景并提出改进方案。' }, outcome: { title: '实验记录', detail: '测试日志和数据已归档。', url: 'https://example.com/results' } } });
    unexpected.push(path);
    return reply({ message: 'Unmocked API: ' + path }, 501);
  });
  return { writes, unexpected, errors };
}
