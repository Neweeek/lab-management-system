# 管理页面验收

运行 `npm run dev -- --host 127.0.0.1 --port 5177`，打开 `/qa/management.html`。
该页面挂载真实的项目和报告组件，使用本地模拟数据，不访问生产接口，也不写入数据库。

验收项：项目列表、详情页签、报名筛选、成员名单、管理员报告列表、姓名筛选、历史报告、草稿提示、不安全成果链接不可点击、390px 窄屏排版。
权限与数据库查询由 `AdminReportControllerTest` 和 `ProjectControllerTest` 使用临时 SQLite 数据库测试。
此目录不在生产构建入口或发布文件清单中。
