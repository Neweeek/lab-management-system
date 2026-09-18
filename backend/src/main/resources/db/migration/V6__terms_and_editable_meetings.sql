-- ============================================================================
-- V6 学期归属与"可编辑的上课时间"
--
-- 本迁移同时是**修复迁移**：V4 在开发期间被改过，早期形态下
--   * lab_terms 是"教学日历"形状（没有 is_current，多了 closed_dates/extra_workdays）
--   * courses 没有 term_id / source
--   * course_occurrences 没有 term_id
-- 迁移器按校验和会拒绝在"已应用旧 V4"的库上直接跑新脚本，
-- 因此这里不假设 V4 是哪种形态，而是**先探测再修正**，兼容两种库。
--
-- 目标结构（也是全新安装的形状）：
--   lab_terms(id,name,start_date,end_date,is_current,created_at)
--   courses(+term_id,+source)
--   course_meetings(可编辑的上课时段：周几 + 起止讲课 + 生效周次)
--   course_occurrences(+term_id)
--
-- 对全新安装，下面的探测分支都会走"已是目标形状"的路径，语句是 no-op。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) lab_terms 对齐到目标形状
--
--    早期 V4 的 lab_terms 结构不同（没有 is_current、多了废弃列）。
--    "探测列结构再决定怎么改"这件事由 MigrationRunner.repairEarlyV4Schema()
--    在 Java 里完成（它会把旧表改名为 _legacy_lab_terms，并在本迁移之后
--    调用 restoreTermsFromLegacy() 把旧学期数据抢救回来）——
--    因为在 SQL 里做不到：SQLite 在解析阶段就会拒绝引用不存在的列，
--    CASE/EXISTS 都兜不住 no such column（这是实际踩过的坑）。
--
--    因此这里只做无条件安全的操作：确保目标形状的表存在、且恰好有一个当前学期。
--    已有正确形状的 lab_terms 会被 IF NOT EXISTS 原样保留。
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
-- 2) courses 补 term_id / source（重复加列由迁移器容忍）
-- ---------------------------------------------------------------------------
ALTER TABLE courses ADD COLUMN term_id INTEGER;
ALTER TABLE courses ADD COLUMN source TEXT NOT NULL DEFAULT 'ICS';

-- ---------------------------------------------------------------------------
-- 3) course_occurrences 补 term_id
-- ---------------------------------------------------------------------------
ALTER TABLE course_occurrences ADD COLUMN term_id INTEGER;

CREATE INDEX IF NOT EXISTS idx_courses_user_term ON courses(user_id, term_id);
CREATE INDEX IF NOT EXISTS idx_course_occ_course ON course_occurrences(course_id);

-- ---------------------------------------------------------------------------
-- 4) 还没有学期时，按已有课程日期推一个
--    注意必须显式判断"确实有行"：聚合查询在没有匹配行时仍返回一行全 NULL，
--    直接 INSERT 会撞上 start_date 的 NOT NULL 约束。
-- ---------------------------------------------------------------------------
INSERT INTO lab_terms(name, start_date, end_date, is_current)
SELECT '未命名学期（由历史数据生成）',
       date(MIN(on_date), 'weekday 0', '-6 days'),
       date(MAX(on_date), '+180 days'),
       1
  FROM course_occurrences
 WHERE NOT EXISTS (SELECT 1 FROM lab_terms)
 HAVING COUNT(*) > 0;

-- 兜底：任何情况下都保证恰好有一个当前学期
UPDATE lab_terms SET is_current = 1
 WHERE NOT EXISTS (SELECT 1 FROM lab_terms WHERE is_current = 1)
   AND id = (SELECT MIN(id) FROM lab_terms);
UPDATE lab_terms SET is_current = 0
 WHERE is_current = 1
   AND id <> (SELECT MIN(id) FROM lab_terms WHERE is_current = 1);

-- ---------------------------------------------------------------------------
-- 5) 给缺失归属的课程与占用补上当前学期
-- ---------------------------------------------------------------------------
UPDATE courses
   SET term_id = (SELECT id FROM lab_terms WHERE is_current=1 ORDER BY id LIMIT 1)
 WHERE term_id IS NULL
   AND EXISTS (SELECT 1 FROM lab_terms);

UPDATE course_occurrences
   SET term_id = (SELECT term_id FROM courses WHERE courses.id = course_occurrences.course_id)
 WHERE term_id IS NULL;

-- ---------------------------------------------------------------------------
-- 6) 把展开结果聚合回可编辑的上课时段
--
--    这一步让**老的 ICS 课程也变成可编辑的**：RRULE 展开出的日期按
--    「周几 + 讲课区间」分组、换算成学期周次，就与用户手动填写的形式一致。
--    周次 = (日期 - 学期起始日) / 7 + 1；strftime('%w') 是 0=周日，转成 0=周一。
-- ---------------------------------------------------------------------------
INSERT INTO course_meetings(course_id, weekday, period_start, period_end, weeks, location)
SELECT o.course_id,
       (CAST(strftime('%w', o.on_date) AS INTEGER) + 6) % 7,
       o.period_start,
       o.period_end,
       '[' || group_concat(
           CAST((julianday(o.on_date) - julianday(t.start_date)) / 7 AS INTEGER) + 1
       ) || ']',
       COALESCE(c.location, '')
  FROM course_occurrences o
  JOIN courses c ON c.id = o.course_id
  JOIN lab_terms t ON t.id = c.term_id
 WHERE NOT EXISTS (SELECT 1 FROM course_meetings m WHERE m.course_id = o.course_id)
 GROUP BY o.course_id,
          (CAST(strftime('%w', o.on_date) AS INTEGER) + 6) % 7,
          o.period_start,
          o.period_end;
