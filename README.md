# 实验室成员与工位管理系统

Spring Boot 3 + Vue 3 + SQLite 的单实验室成员、工位、预约与考察管理系统。系统以单机 SQLite/WAL 模式运行，适合一个实验室使用；不支持多台后端实例同时连接同一个数据库文件。

## 本地开发

```powershell
cd backend
mvn test
mvn package
java -jar target/lab-seat-system-0.1.0.jar
```

另开终端：

```powershell
cd frontend
npm install
npm run dev -- --host 127.0.0.1 --port 5175
```

首次使用空数据库时，必须配置管理员；例如 PowerShell：

```powershell
$env:APP_BOOTSTRAP_ADMIN_STUDENT_NO='admin'
$env:APP_BOOTSTRAP_ADMIN_PASSWORD='请替换为长随机密码'
java -jar target/lab-seat-system-0.1.0.jar
```

系统不再创建 `admin/admin` 或任何演示成员。已有数据库不会被删除或覆盖。

## 云服务器部署

前提：Linux 云服务器已安装 Docker Engine 与 Docker Compose Plugin；域名 A/AAAA 记录已指向该服务器；80/443 端口已在安全组和防火墙放行。

```bash
cp .env.example .env
nano .env                 # 必须填写域名、邮箱和强管理员密码
mkdir -p data logs backups
chmod +x deploy/*.sh
docker compose up -d --build
docker compose ps
```

部署后访问 `https://你的域名`。Caddy 会自动申请与续期 TLS 证书；不要将 `app` 服务的 8080 端口映射到公网。

### 上线前必须完成

1. `.env` 中设置强随机 `APP_BOOTSTRAP_ADMIN_PASSWORD`，且绝不提交 `.env`。
2. 首次登录后确认管理员账号可用；若迁移的是旧库，应先在管理端移除演示账号与演示预约/考察数据。
3. 每日执行 `./deploy/backup.sh`，并至少做一次恢复演练：`./deploy/restore.sh backups/xxx.db`。
4. 只运行一个 `app` 副本。SQLite 是单写入器数据库；需要横向扩容或高频写入时应迁移至 PostgreSQL/MySQL。
5. 使用真实域名和 HTTPS。不要通过 IP 地址或 HTTP 直接投入使用。

### 运维命令

```bash
docker compose logs -f app
docker compose logs -f web
docker compose up -d --build
docker compose down
./deploy/backup.sh
```

备份文件和实时数据库位于宿主机 `data/`；请额外同步备份到对象存储或另一台机器。恢复脚本会替换实时数据库，执行前务必确认备份文件。

## 生产安全措施

- BCrypt 密码哈希；首次管理员来自环境变量，不含默认密码；
- 登录失败按“学号 + 来源地址”限流，连续 5 次失败锁定 15 分钟；
- 登录后重建 Session，并对所有认证后的写操作启用 CSRF Token；
- Cookie 使用 HttpOnly、SameSite=Lax，生产环境强制 Secure；
- Caddy 提供 HTTPS、基础安全响应头和同域 API 反代；
- SQLite 使用 WAL、每连接 busy timeout、有限连接池与宿主机持久化卷；
- 全局异常不向客户端暴露堆栈；审计记录仍保存在数据库中。

## 功能说明

### 成员管理与近期更新

- 注册审核支持一键全选当前筛选结果并批量通过。
- 管理员删除成员时需填写原因并确认学号；账号停用且旧会话失效，历史报告和审计记录保留。未结束项目的发布者需先结束项目。
- 管理员可主动取消尚未结束的考察并填写原因；保留工位和已提交报告，关闭未完成任务。
- 管理员可通过“报告查询”按成员、日期、提交状态与考察范围检索历史周报。

### 项目团队

- 管理员发布各类项目，审核通过的普通成员也可发布竞赛招募并管理自己的竞赛；发布者自动加入团队并计入人数上限。列表支持名称搜索、状态筛选、分页及按发布时间正序或倒序排列。
- 已确认成员提交申请理由、技能与可投入时间；待审核申请可撤回，不能重复申请。
- 管理员审核并填写备注，也可直接添加或移出成员。关闭招募后仍可审核已有申请；结束项目不可恢复，并关闭待审核申请。
- 普通申请人仅能查看自己的报名材料，项目发布者与管理员可查看该项目报名材料；团队名单只展示姓名，不开放个人周报或成果。项目身份与工位身份独立。
- 周报可从已加入项目填入名称和协作同学，仍需保存或提交才会持久化。

验证范围：后端回归测试使用临时 SQLite 数据库，不操作现有业务数据；已验证前端生产构建和运行时模板编译。真实浏览器端到端流程、Docker 部署和备份恢复演练仍需在目标环境验证。

### 工位与考察

- 注册后为未确认成员；审核通过成为流动成员；有固定工位为正式成员；固定工位考察期间为考察成员；
- 32 个工位（4×8、南窗北门、中央南北通道）；空工位默认流动；
- 固定工位申请/直接分配、流动工位按时间预约、考察与按周周报；
- 周报只在所属自然周开放填写，未参与事项可通过“省略”保存为“无”；
- 注册、审批、预约与考察结果站内信，管理员审计日志。
