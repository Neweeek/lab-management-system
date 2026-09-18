-- ============================================================================
-- V5 清理管理员账号下的课程表数据
--
-- 背景
-- ----
-- 之前的 CourseController 没有按角色限制导入接口，管理员账号也能导入课表。
-- 但排班只针对 role='MEMBER'，因此管理员的课表永远是死数据，
-- 却会让"已导入课表人数"之类的统计虚高（例如显示 3 人已导入，实际只有 2 名成员）。
--
-- 代码侧已修为 requireMember（管理员导入会被 403 拒绝），本迁移负责清理历史残留。
-- 对不存在此类数据的库是无害的 no-op。
-- ============================================================================

-- course_occurrences 通过外键 ON DELETE CASCADE 关联 courses，
-- 但级联只在外键开启时生效；这里显式删除两张表，避免依赖 PRAGMA 状态。
DELETE FROM course_occurrences
 WHERE user_id IN (SELECT id FROM users WHERE role <> 'MEMBER');

DELETE FROM courses
 WHERE user_id IN (SELECT id FROM users WHERE role <> 'MEMBER');
