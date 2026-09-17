-- ============================================================================
-- V1 baseline: 初始结构（从 backend/src/main/resources/schema.sql 迁移而来）
--
-- 时间约定（重要）：
--   本库中所有时间列统一保存"实验室本地墙钟时间"，格式为 ISO-8601
--   且不含时区偏移，例如 2026-09-17T10:50:40。
--
--   默认值一律使用下面这个表达式，**不要使用 CURRENT_TIMESTAMP**：
--     strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours')
--   SQLite 的 CURRENT_TIMESTAMP 返回 UTC，而 Java 侧写入的是
--   LocalDateTime.now(LabTime.ZONE)（+08:00），同列混用会相差 8 小时，
--   任何跨列/跨来源的时间比较都会埋雷。
--
--   'now' 在 SQLite 中是 UTC 瞬时值，因此 '+8 hours' 对任意宿主时区都成立
--   （容器已设置 TZ=UTC；即便宿主时区不同也不影响该表达式的结果）。
--   Java 侧的时区常量定义在 LabTime.ZONE，两者必须保持同步。
--
--   单实验室固定时区下这样最简单：存储值、字符串比较、前端展示
--   （datetime-local 输入框、按日比较）三者语义完全一致。
-- ============================================================================

-- 连接级 PRAGMA。迁移器会在初始连接上执行；journal_mode=WAL 是持久化的库级设置，
-- busy_timeout 是连接级的（application.yml 的 connection-init-sql 也会设置，
-- 以保证连接池中每条新连接都生效）。
PRAGMA journal_mode=WAL;
PRAGMA busy_timeout=10000;

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  student_no TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  gender TEXT,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'MEMBER',
  member_status TEXT NOT NULL DEFAULT 'UNCONFIRMED',
  approved INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);
CREATE TABLE IF NOT EXISTS seats (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT UNIQUE NOT NULL,
  row_no INTEGER NOT NULL,
  col_no INTEGER NOT NULL,
  area TEXT NOT NULL DEFAULT '主实验室',
  type TEXT NOT NULL DEFAULT 'FIXED',
  status TEXT NOT NULL DEFAULT 'AVAILABLE',
  occupant_id INTEGER,
  review_mode INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS seat_applications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  seat_id INTEGER NOT NULL,
  reason TEXT,
  status TEXT NOT NULL DEFAULT 'PENDING',
  reviewed_by INTEGER,
  review_note TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);
CREATE TABLE IF NOT EXISTS seat_bookings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  seat_id INTEGER NOT NULL,
  start_at TEXT NOT NULL,
  end_at TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);
CREATE TABLE IF NOT EXISTS notifications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  read_flag INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);
CREATE TABLE IF NOT EXISTS audit_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  actor_id INTEGER,
  action TEXT NOT NULL,
  target TEXT NOT NULL,
  detail TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);
CREATE TABLE IF NOT EXISTS seat_assignments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  seat_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  started_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours')),
  ended_at TEXT,
  end_reason TEXT
);
CREATE TABLE IF NOT EXISTS reviews (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  member_id INTEGER NOT NULL,
  seat_id INTEGER,
  reason TEXT NOT NULL,
  requirements TEXT,
  start_at TEXT NOT NULL,
  end_at TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PLANNED',
  result TEXT,
  decision_note TEXT,
  created_by INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);
CREATE TABLE IF NOT EXISTS weekly_report_tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  review_id INTEGER NOT NULL,
  member_id INTEGER NOT NULL,
  period_index INTEGER NOT NULL,
  due_at TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'OPEN',
  UNIQUE(review_id, period_index)
);
CREATE TABLE IF NOT EXISTS weekly_reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id INTEGER NOT NULL UNIQUE,
  user_id INTEGER NOT NULL,
  sections_json TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'DRAFT',
  submitted_at TEXT,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);
CREATE TABLE IF NOT EXISTS special_circumstances (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  type TEXT NOT NULL,
  start_at TEXT NOT NULL,
  end_at TEXT NOT NULL,
  description TEXT NOT NULL,
  evidence_url TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);
CREATE TABLE IF NOT EXISTS admin_notes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  member_id INTEGER NOT NULL,
  review_id INTEGER,
  content TEXT NOT NULL,
  created_by INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);
CREATE TABLE IF NOT EXISTS projects (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  type TEXT NOT NULL,
  description TEXT NOT NULL,
  requirements TEXT NOT NULL,
  capacity INTEGER NOT NULL CHECK(capacity>0),
  deadline TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'RECRUITING',
  created_by INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);
CREATE TABLE IF NOT EXISTS project_applications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id INTEGER NOT NULL REFERENCES projects(id),
  user_id INTEGER NOT NULL REFERENCES users(id),
  reason TEXT NOT NULL,
  skills TEXT NOT NULL,
  availability TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  review_note TEXT NOT NULL DEFAULT '',
  reviewed_by INTEGER,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);
CREATE TABLE IF NOT EXISTS project_members (
  project_id INTEGER NOT NULL REFERENCES projects(id),
  user_id INTEGER NOT NULL REFERENCES users(id),
  joined_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours')),
  PRIMARY KEY(project_id,user_id)
);

-- 注意：以下两个"部分唯一索引"是 applySeat 并发竞态的最终防线，但它们要求
-- seat_applications 里没有重复的 PENDING 行。历史库可能因旧的竞态产生脏数据，
-- 因此它们不在 baseline 里创建，而是由 V3 在清理脏数据之后建立。
--   idx_seat_app_pending_user
--   idx_seat_app_pending_seat
CREATE UNIQUE INDEX IF NOT EXISTS idx_project_pending
  ON project_applications(project_id,user_id) WHERE status='PENDING';
CREATE INDEX IF NOT EXISTS idx_users_approved ON users(approved, member_status);
CREATE INDEX IF NOT EXISTS idx_seat_booking_window ON seat_bookings(seat_id, status, start_at, end_at);
CREATE INDEX IF NOT EXISTS idx_review_member ON reviews(member_id, status);
CREATE INDEX IF NOT EXISTS idx_task_member ON weekly_report_tasks(member_id, due_at);
CREATE INDEX IF NOT EXISTS idx_project_app_user ON project_applications(user_id,project_id);

-- 迁移台账：migration runner 在此记录已应用的版本与校验和。
CREATE TABLE IF NOT EXISTS schema_migrations (
  version TEXT PRIMARY KEY,
  description TEXT NOT NULL,
  checksum TEXT NOT NULL,
  applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);
