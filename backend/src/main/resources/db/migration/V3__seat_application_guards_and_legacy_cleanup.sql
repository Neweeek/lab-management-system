-- ============================================================================
-- V3 数据清理与约束加固
--
-- 1) 清理旧 applySeat 并发竞态留下的重复 PENDING 工位申请；
-- 2) 修正没有任何待审核申请却停留在 PENDING 的工位；
-- 3) 归档并删除遗留表 reports（从未被任何 Java 代码引用）；
-- 4) 建立两个部分唯一索引，从数据库层面杜绝"同一工位/同一成员并发重复申请"。
--
-- 顺序很关键：必须先清干净，第 4 步的索引才能建起来。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 重复 PENDING 申请：同一工位保留最早一条，其余标记拒绝
-- ---------------------------------------------------------------------------
UPDATE seat_applications
   SET status='REJECTED',
       review_note='数据库迁移：同一工位存在重复待审核申请，自动保留最早一条'
 WHERE status='PENDING'
   AND id NOT IN (SELECT MIN(a.id) FROM seat_applications a WHERE a.status='PENDING' GROUP BY a.seat_id);

-- 同一成员保留最早一条
UPDATE seat_applications
   SET status='REJECTED',
       review_note='数据库迁移：同一成员存在重复待审核申请，自动保留最早一条'
 WHERE status='PENDING'
   AND id NOT IN (SELECT MIN(a.id) FROM seat_applications a WHERE a.status='PENDING' GROUP BY a.user_id);

-- ---------------------------------------------------------------------------
-- 2) 没有任何待审核申请的工位不应停留在 PENDING
-- ---------------------------------------------------------------------------
UPDATE seats
   SET type='MOBILE', status='AVAILABLE'
 WHERE status='PENDING'
   AND occupant_id IS NULL
   AND type<>'DISABLED'
   AND NOT EXISTS (SELECT 1 FROM seat_applications a WHERE a.seat_id=seats.id AND a.status='PENDING');

-- ---------------------------------------------------------------------------
-- 3) 遗留表 reports 归档后删除
--    先备份成 reports_legacy_backup 便于人工核查；若该表本就不存在（V1 之后的新库），
--    这两条语句是无害的 no-op。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reports_legacy_backup AS SELECT * FROM reports;
DROP TABLE IF EXISTS reports;

-- ---------------------------------------------------------------------------
-- 4) 并发守卫索引（清理完成后方可建立）
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS idx_seat_app_pending_user
  ON seat_applications(user_id) WHERE status='PENDING';
CREATE UNIQUE INDEX IF NOT EXISTS idx_seat_app_pending_seat
  ON seat_applications(seat_id) WHERE status='PENDING';
