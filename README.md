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
npm ci
npm run dev -- --host 127.0.0.1 --port 5175
```

前端可用的校验命令：

```powershell
npm run typecheck   # tsc --noEmit，无 tsconfig 时代码里曾有真实类型错误未被发现
npm run build       # vite build（esbuild 只剥离类型，不做类型检查）
```

首次使用空数据库时，必须配置管理员；例如 PowerShell：

```powershell
$env:APP_BOOTSTRAP_ADMIN_STUDENT_NO='admin'
$env:APP_BOOTSTRAP_ADMIN_PASSWORD='请替换为长随机密码'
java -jar target/lab-seat-system-0.1.0.jar
```

管理员密码至少 12 位，且不能是 `.env.example` 里的示例占位值——否则应用会拒绝启动。系统不再创建 `admin/admin` 或任何演示成员。已有数据库不会被删除或覆盖；密码校验只在"库中还没有管理员、即将创建首个管理员"时执行，不会把在跑的部署锁在门外。

## 数据库结构变更（迁移）

结构由自带的迁移器管理，**不再使用 `spring.sql.init`**：

- 脚本位置：`backend/src/main/resources/db/migration/`，命名 `V<版本>__<描述>.sql`
- 台账表：`schema_migrations`（版本、描述、SHA-256 校验和、应用时间）
- 执行时机：应用启动时，在任何业务逻辑之前；每个脚本在**单个事务**内执行，失败整体回滚
- 已应用脚本若被事后修改，启动会因校验和不一致而失败——**不要改已发布的迁移，请新增更高版本**

新增结构变更的流程：

```powershell
# 1. 新建 V4__add_xxx.sql（CREATE TABLE / ALTER TABLE / UPDATE 均可）
# 2. mvn test        —— 迁移脚本会被测试真实执行
# 3. 部署后启动应用，观察日志中的 "已应用数据库迁移: [4]"
```

现有的三个迁移：

| 版本 | 内容 |
|---|---|
| V1 | 初始结构（baseline）。所有时间列默认值改为实验室本地时间 |
| V2 | 把旧代码按 UTC 写入的时间值统一修正为本地时间，并归一化时间格式 |
| V3 | 清理重复的待审核工位申请、归档并删除遗留 `reports` 表、建立并发守卫唯一索引 |

### 时间约定（重要）

**所有时间列统一保存"实验室本地墙钟时间"**（Asia/Shanghai），ISO-8601 且不带时区偏移，例如 `2026-09-17T10:50:40`。

- Java 侧一律用 `LabTime.now()` / `LabTime.nowText()`，**禁止直接使用 `LocalDateTime.now()`**
- SQLite 侧默认值一律用 `(strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))`，**禁止使用 `CURRENT_TIMESTAMP`**（那是 UTC，混用会差 8 小时）
- 前端展示一律走 `frontend/src/time.ts`，**不要做时区换算**；输入框用本地墙钟值

修改时区需要同时改 `LabTime.ZONE` 与 SQLite 默认值里的偏移量，并新增一个迁移平移历史数据。

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

`.env` 只会完整注入 `app` 容器；`web`（Caddy）只接收 `APP_DOMAIN` 与 `ACME_EMAIL`，避免管理员密码出现在不需要它的容器里。

### 上线前必须完成

1. `.env` 中设置强随机 `APP_BOOTSTRAP_ADMIN_PASSWORD`（≥12 位，非示例值），且绝不提交 `.env`。
2. 首次登录后确认管理员账号可用；若迁移的是旧库，应先在管理端移除演示账号与演示预约/考察数据。
3. 每日执行 `./deploy/backup.sh`（默认保留最近 14 份，可用 `BACKUP_KEEP` 调整），并至少做一次恢复演练：`./deploy/restore.sh --dry-run backups/xxx.db` 先校验，再执行真实恢复。
4. 只运行一个 `app` 副本。SQLite 是单写入器数据库；需要横向扩容或高频写入时应迁移至 PostgreSQL/MySQL。
5. 使用真实域名和 HTTPS。不要通过 IP 地址或 HTTP 直接投入使用。

### 运维命令

```bash
docker compose logs -f app
docker compose logs -f web
docker compose up -d --build
docker compose down
./deploy/backup.sh
./deploy/restore.sh --dry-run backups/lab-YYYYMMDD-HHMMSS.db   # 只校验
./deploy/restore.sh            backups/lab-YYYYMMDD-HHMMSS.db   # 交互确认后恢复
```

`restore.sh` 会先校验 SHA-256 与 `PRAGMA integrity_check`，再让你输入 `yes` 二次确认，恢复前自动保存一份当前库快照，并在失败时自动把 app 重新拉起；恢复后等待 `/api/health` 通过才报成功。备份文件和实时数据库位于宿主机 `data/`；请额外同步备份到对象存储或另一台机器。

## 生产安全措施

- BCrypt 密码哈希；首次管理员来自环境变量，不含默认密码，且会拒绝示例占位值；
- 登录失败按"学号 + 来源地址"限流，连续 5 次失败锁定 15 分钟；
- 登录后重建 Session，并对所有认证后的写操作启用 CSRF Token；
- Cookie 使用 HttpOnly、SameSite=Lax，生产环境强制 Secure；
- Caddy 提供 HTTPS、HSTS、CSP 与基础安全响应头，同域反代 API，并对构建产物与 `index.html` 采用不同的缓存策略；
- 所有工位相关接口需登录；`/api/seats` 只对管理员返回占用人姓名，容器健康检查改用不返回业务数据的 `/api/health`；
- SQLite 使用 WAL、每连接 busy timeout 与外键约束、有限连接池与宿主机持久化卷；
- 全局异常不向客户端暴露堆栈；审计记录仍保存在数据库中。

## 功能说明

### 成员管理与近期更新

- 注册审核支持一键全选当前筛选结果并批量通过。
- 管理员删除成员时需填写原因并确认学号；账号停用且旧会话失效，历史报告和审计记录保留。未结束项目的发布者需先结束项目。
- 管理员可主动取消尚未结束的考察并填写原因；保留工位和已提交报告，关闭未完成任务。
- 管理员可通过"报告查询"按成员、日期、提交状态与考察范围检索历史周报。

### 项目团队

- 管理员发布各类项目，审核通过的普通成员也可发布竞赛招募并管理自己的竞赛；发布者自动加入团队并计入人数上限。列表支持名称搜索、状态筛选、分页及按发布时间正序或倒序排列。
- 已确认成员提交申请理由、技能与可投入时间；待审核申请可撤回，不能重复申请。
- 管理员审核并填写备注，也可直接添加或移出成员。关闭招募后仍可审核已有申请；结束项目不可恢复，并关闭待审核申请。
- 普通申请人仅能查看自己的报名材料，项目发布者与管理员可查看该项目报名材料；团队名单只展示姓名，不开放个人周报或成果。项目身份与工位身份独立。
- 周报可从已加入项目填入名称和协作同学，仍需保存或提交才会持久化。

### 工位与考察

- 注册后为未确认成员；审核通过成为流动成员；有固定工位为正式成员；固定工位考察期间为考察成员；
- 32 个工位（4×8、南窗北门、中央南北通道）；空工位默认流动；
- 固定工位申请/直接分配、流动工位按时间预约、考察与按周周报；
- 周报只在所属自然周开放填写，未参与事项可通过"省略"保存为"无"；
- 注册、审批、预约与考察结果站内信，管理员审计日志。

### 并发控制

SQLite 全库只有一个写者，且默认事务是延迟（deferred）的：先读后写会在锁升级时**立即**返回 `SQLITE_BUSY`，此时 `busy_timeout` 不生效。因此所有"先校验再写入"的工位写事务都用 `SqliteLocks.acquire(...)` **在事务第一条语句抢写锁**，配合：

- `UPDATE ... WHERE status='AVAILABLE'` 的条件写入 + 受影响行数检查；
- `seat_applications` 上针对 `PENDING` 的部分唯一索引（成员维度、工位维度各一条）。

三者共同保证同一工位不会被并发分配给两个人。

## 验证范围

后端回归测试使用临时 SQLite 数据库，不操作现有业务数据；测试与生产走同一条迁移路径，因此迁移脚本错误会在 `mvn test` 阶段暴露。已验证前端类型检查（`npm run typecheck`）与生产构建。真实浏览器端到端流程、Docker 部署与备份恢复演练仍需在目标环境验证。
