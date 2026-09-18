-- ============================================================================
-- V4 课程表导入与值班排班
--
-- 背景
-- ----
-- 需要把每个人自己的课程表（WakeUpSchedule / 教务系统导出的 .ics）导入系统，
-- 用于两件事：
--   1. 查询"某个时段哪些同学没课"；
--   2. 按讲课为单位自动安排实验室值班（每讲课至少 1 人，最多 4 人）。
--
-- ICS 的实际形态（见样例）：
--   DTSTART;TZID=Asia/Shanghai:20260902T140000
--   DTEND;TZID=Asia/Shanghai:20260902T153500
--   RRULE:FREQ=WEEKLY;UNTIL=20260929T160000Z;INTERVAL=1
--   DESCRIPTION:第5 - 6节\n曹妃甸校区HE座HE-405\n卢朝辉*
-- 重要：DESCRIPTION 里**直接写了节次**，因此解析时优先取它，
-- 不要用 DTSTART/DTEND 去反推节次（连堂时反推会错）。
--
-- 时间约定沿用 V1：所有时间列保存实验室本地墙钟时间（ISO-8601，无时区偏移）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 节次表：10 个讲课，来自学校实际时间表。
-- 两讲课为一节：第1-2节(上午) 第3-4节(上午) 第5-6节(下午) 第7-8节(下午) 第9-10节(晚上)
-- 两讲课之间休息 5 分钟，两节之间休息 20 分钟（课间不排值班）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS class_periods (
  period_no INTEGER PRIMARY KEY,
  start_at TEXT NOT NULL,        -- 'HH:MM'，字典序即时间序
  end_at TEXT NOT NULL,
  section_no INTEGER NOT NULL,   -- 所属"节"（1..5）
  section_name TEXT NOT NULL,    -- 上午第一节 / 上午第二节 / 下午第一节 / 下午第二节 / 晚上
  note TEXT NOT NULL DEFAULT ''
);

-- 学校实际时间表。19:00–20:35 依据 ICS 样例中的实测时间戳，而非口头描述的"十七点"。
INSERT OR REPLACE INTO class_periods(period_no,start_at,end_at,section_no,section_name,note) VALUES
  (1, '08:30','09:15',1,'上午第一节',''),
  (2, '09:20','10:05',1,'上午第一节','第1-2讲课之间休息5分钟'),
  (3, '10:25','11:10',2,'上午第二节','第1-2节与第3-4节之间休息20分钟'),
  (4, '11:15','12:00',2,'上午第二节',''),
  (5, '14:00','14:45',3,'下午第一节',''),
  (6, '14:50','15:35',3,'下午第一节','第5-6讲课之间休息5分钟'),
  (7, '15:55','16:40',4,'下午第二节','第5-6节与第7-8节之间休息20分钟'),
  (8, '16:45','17:30',4,'下午第二节',''),
  (9, '19:00','19:45',5,'晚上',''),
  (10,'19:50','20:35',5,'晚上','第9-10讲课之间休息5分钟');

-- ---------------------------------------------------------------------------
-- 课程：按 ICS 的 VEVENT 或"手动添加"的一条课程保存。
--
-- 归属学期很重要：一个成员在一个学期内只应有一份课表，
-- 因此 uid 的唯一性按 (user_id, term_id) 限定 —— 重新导入 ICS 是更新而非新增。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS courses (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  term_id INTEGER,
  uid TEXT NOT NULL,                 -- VEVENT 的 UID；手动添加的课用 'manual-<随机>' 占位
  source TEXT NOT NULL DEFAULT 'ICS',-- ICS = 从日历导入；MANUAL = 用户手动添加
  summary TEXT NOT NULL DEFAULT '',  -- 课程名
  location TEXT NOT NULL DEFAULT '',
  description TEXT NOT NULL DEFAULT '',
  dtstart TEXT NOT NULL,             -- 本地墙钟 'YYYY-MM-DDTHH:MM:SS'
  dtend TEXT NOT NULL,
  rrule TEXT NOT NULL DEFAULT '',
  periods_raw TEXT NOT NULL DEFAULT '',  -- DESCRIPTION 里解析出的"第5 - 6节"原文
  dtstamp TEXT NOT NULL DEFAULT '',      -- 导入时该 VEVENT 的 DTSTAMP，用于判断是否需要更新
  imported_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours')),
  UNIQUE(user_id, uid)
);
CREATE INDEX IF NOT EXISTS idx_courses_user_term ON courses(user_id, term_id);

-- ---------------------------------------------------------------------------
-- 可编辑的上课时段：一门课可以有多条（不同周几、不同讲课、不同生效周次）。
--
-- 这是"导入的课也能改"的关键：ICS 导入时把 RRULE 展开后的日期聚合成
-- 「周几 + 起止讲课 + 周次」，与用户手动填写的形式完全一致，
-- 于是两种来源共用同一套编辑逻辑与同一套展开逻辑。
--
--   weekday      0=周一 … 6=周日，与 java.time.DayOfWeek 的序号对齐
--   weeks        JSON 数组，学期周次，如 [1,2,3,4] 或 [1,3,5,7]（单周）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS course_meetings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  course_id INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
  weekday INTEGER NOT NULL,
  period_start INTEGER NOT NULL,
  period_end INTEGER NOT NULL,
  weeks TEXT NOT NULL DEFAULT '[]',
  location TEXT NOT NULL DEFAULT '',
  UNIQUE(course_id, weekday, period_start, period_end)
);
CREATE INDEX IF NOT EXISTS idx_course_meetings_course ON course_meetings(course_id);

-- ---------------------------------------------------------------------------
-- 课程占用：把 meeting 按学期周次展开到具体日期后的结果。
--
-- period_start/period_end 是占用的讲课区间（如第5 - 6节 → 5..6）。
-- 这两个字段是"谁没课"和"该给谁排班"的唯一依据，因此必须准确。
--
-- 注意没有 (course_id, on_date) 唯一约束：一门课的多条 meeting 可能落在同一天
-- （例如周三既排第1-2讲也排第7-8讲），因此唯一性由 (course_id, on_date, period_start) 表示。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS course_occurrences (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  course_id INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL,
  term_id INTEGER,
  on_date TEXT NOT NULL,             -- 'YYYY-MM-DD'
  period_start INTEGER NOT NULL,     -- 1..10
  period_end INTEGER NOT NULL,       -- >= period_start
  UNIQUE(course_id, on_date, period_start)
);
CREATE INDEX IF NOT EXISTS idx_course_occ_user_date ON course_occurrences(user_id, on_date);
CREATE INDEX IF NOT EXISTS idx_course_occ_date ON course_occurrences(on_date, period_start, period_end);
CREATE INDEX IF NOT EXISTS idx_course_occ_course ON course_occurrences(course_id);

-- ---------------------------------------------------------------------------
-- 学期：管理员配置，同一时间只有一个 is_current=1。
-- "第 N 周"以 start_date 所在周为第 1 周；start_date 必须是一个周一。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lab_terms (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  start_date TEXT NOT NULL,
  end_date TEXT NOT NULL,
  is_current INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
);

-- ---------------------------------------------------------------------------
-- 值班安排：以"讲课"为单位，每天 10 个槽位。
-- 一个槽位至少 1 人、最多 4 人（上限在应用层校验，这里只加唯一约束防重复）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS duty_assignments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  on_date TEXT NOT NULL,             -- 'YYYY-MM-DD'
  period_no INTEGER NOT NULL,        -- 1..10，一讲课一个槽位
  user_id INTEGER NOT NULL,
  source TEXT NOT NULL DEFAULT 'AUTO',   -- AUTO(算法生成) / MANUAL(管理员指派)
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours')),
  UNIQUE(on_date, period_no, user_id)
);
CREATE INDEX IF NOT EXISTS idx_duty_date ON duty_assignments(on_date, period_no);
CREATE INDEX IF NOT EXISTS idx_duty_user ON duty_assignments(user_id, on_date);
