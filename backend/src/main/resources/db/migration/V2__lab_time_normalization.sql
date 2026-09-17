-- ============================================================================
-- V2 时间基准修复：把"旧代码按 UTC 写入"的时间列统一到实验室本地时间（+08:00）
--
-- 背景
-- ----
-- V1 之前的代码里，时间列存在两个来源、两种基准：
--   1) Java 侧 `LocalDateTime.now()` —— 本地时间（+08:00）
--   2) SQLite 侧 `DEFAULT CURRENT_TIMESTAMP` —— UTC
-- 于是同一个列里混着两种基准，相差 8 小时。
--
-- 哪些列受影响
-- ------------
-- 只有那些"由 SQLite 默认值写入、旧代码从不用 Java 显式赋值"的列才是 UTC。
-- 本迁移只修正这些列（每个表一处）：
--
--   users.created_at              seat_applications.created_at
--   seat_bookings.created_at      notifications.created_at
--   audit_logs.created_at         seat_assignments.started_at
--   reviews.created_at            special_circumstances.created_at
--   admin_notes.created_at        projects.created_at
--   project_applications.created_at  project_members.joined_at
--   weekly_reports.updated_at     weekly_reports.submitted_at
--
-- 刻意不动的列（旧代码用 Java 写入，本来就是本地时间）：
--   seat_assignments.ended_at    —— 旧代码虽写 CURRENT_TIMESTAMP，但它必须与同一行
--                                   started_at 保持配对语义；新代码已改为由 Java
--                                   显式写入，历史行留原值，以免出现"结束早于开始"。
--   reviews.start_at / end_at、weekly_report_tasks.due_at、
--   seat_bookings.start_at / end_at、special_circumstances.start_at / end_at
--                                —— 均为 Java 按用户输入写入。
--
-- 幂等性
-- ------
-- 由 schema_migrations 台账保证只执行一次。对"V1 新建的空库"没有行可改，是 no-op；
-- 对"V1 之前建立的老库"把 UTC 值修正为本地值。
-- 无法（也不需要）逐行识别来源：上述列在旧代码里只有一种写入来源，整列偏移是确定的。
-- ============================================================================

UPDATE users                 SET created_at = strftime('%Y-%m-%dT%H:%M:%S', created_at, '+8 hours') WHERE created_at IS NOT NULL;
UPDATE seat_applications     SET created_at = strftime('%Y-%m-%dT%H:%M:%S', created_at, '+8 hours') WHERE created_at IS NOT NULL;
UPDATE seat_bookings         SET created_at = strftime('%Y-%m-%dT%H:%M:%S', created_at, '+8 hours') WHERE created_at IS NOT NULL;
UPDATE notifications         SET created_at = strftime('%Y-%m-%dT%H:%M:%S', created_at, '+8 hours') WHERE created_at IS NOT NULL;
UPDATE audit_logs            SET created_at = strftime('%Y-%m-%dT%H:%M:%S', created_at, '+8 hours') WHERE created_at IS NOT NULL;
UPDATE seat_assignments      SET started_at = strftime('%Y-%m-%dT%H:%M:%S', started_at, '+8 hours') WHERE started_at IS NOT NULL;
UPDATE reviews               SET created_at = strftime('%Y-%m-%dT%H:%M:%S', created_at, '+8 hours') WHERE created_at IS NOT NULL;
UPDATE special_circumstances SET created_at = strftime('%Y-%m-%dT%H:%M:%S', created_at, '+8 hours') WHERE created_at IS NOT NULL;
UPDATE admin_notes           SET created_at = strftime('%Y-%m-%dT%H:%M:%S', created_at, '+8 hours') WHERE created_at IS NOT NULL;
UPDATE projects              SET created_at = strftime('%Y-%m-%dT%H:%M:%S', created_at, '+8 hours') WHERE created_at IS NOT NULL;
UPDATE project_applications  SET created_at = strftime('%Y-%m-%dT%H:%M:%S', created_at, '+8 hours') WHERE created_at IS NOT NULL;
UPDATE project_members       SET joined_at  = strftime('%Y-%m-%dT%H:%M:%S', joined_at,  '+8 hours') WHERE joined_at  IS NOT NULL;

-- 旧代码对"正式提交"写 CURRENT_TIMESTAMP（UTC），对草稿保留 submitted_at 原值。
-- 草稿行 submitted_at 为 NULL，不受影响。
UPDATE weekly_reports SET submitted_at = strftime('%Y-%m-%dT%H:%M:%S', submitted_at, '+8 hours') WHERE submitted_at IS NOT NULL;
UPDATE weekly_reports SET updated_at   = strftime('%Y-%m-%dT%H:%M:%S', updated_at,   '+8 hours') WHERE updated_at   IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 归一化历史时间格式：
-- 旧代码既有 'T' 分隔（Java 写入），也有空格分隔（SQLite datetime() 产生）。
-- 统一为 'T' 分隔，保证字符串比较与排序语义一致（ISO-8601 的字典序即时间序）。
-- ---------------------------------------------------------------------------
UPDATE reviews               SET start_at = replace(start_at,' ','T') WHERE start_at LIKE '% %';
UPDATE reviews               SET end_at   = replace(end_at,' ','T')   WHERE end_at   LIKE '% %';
UPDATE weekly_report_tasks   SET due_at   = replace(due_at,' ','T')   WHERE due_at   LIKE '% %';
UPDATE seat_bookings         SET start_at = replace(start_at,' ','T') WHERE start_at LIKE '% %';
UPDATE seat_bookings         SET end_at   = replace(end_at,' ','T')   WHERE end_at   LIKE '% %';
UPDATE special_circumstances SET start_at = replace(start_at,' ','T') WHERE start_at LIKE '% %';
UPDATE special_circumstances SET end_at   = replace(end_at,' ','T')   WHERE end_at   LIKE '% %';
