package com.lab.seat;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 派生状态修复器。
 *
 * <p>成员身份（{@code users.member_status}）与工位类型/状态都不是独立事实，而是由
 * "是否持有固定工位""是否有未结束考察"派生出来的。这里在每次启动时把它们与事实对齐，
 * 修复曾经因异常中断或手工改库造成的不一致。
 *
 * <p>这些 UPDATE 都带 WHERE 条件，只会命中真正不一致的行；在一致的数据上它们是
 * 空操作。工位与成员数量都是几十行级别，启动开销可忽略。
 *
 * <p><b>顺序要求</b>：必须在数据库迁移建表之后运行（见 {@code LabApplication} 的
 * {@code @Order} 说明），否则会因 {@code no such table} 导致启动失败。
 */
@Configuration
public class DataIntegrityRunner {
  /** 见 LabApplication 中关于 CommandLineRunner 顺序的说明。 */
  @Bean
  @org.springframework.core.annotation.Order(2)
  CommandLineRunner repairReleasedSeatReviews(JdbcTemplate db) {
    return args -> {
      db.update("UPDATE reviews SET status='CANCELLED', result='SEAT_RELEASED', decision_note='固定工位已释放，系统自动结束考察' WHERE status IN ('PLANNED','ACTIVE','AWAITING_DECISION') AND NOT EXISTS (SELECT 1 FROM seats s WHERE s.id=reviews.seat_id AND s.occupant_id=reviews.member_id)");
      db.update("UPDATE weekly_report_tasks SET status='CANCELLED' WHERE review_id IN (SELECT id FROM reviews WHERE status='CANCELLED') AND status IN ('OPEN','DRAFT')");
      db.update("UPDATE seats SET type='MOBILE', status='AVAILABLE', review_mode=0 WHERE occupant_id IS NULL AND id IN (SELECT DISTINCT seat_id FROM seat_bookings WHERE status IN ('PENDING','APPROVED'))");
      db.update("UPDATE seats SET type='MOBILE', status='AVAILABLE', review_mode=0 WHERE occupant_id IS NULL AND type<>'DISABLED' AND status='AVAILABLE'");
      db.update("UPDATE seats SET type='FIXED', status='OCCUPIED' WHERE occupant_id IS NOT NULL");
      db.update("UPDATE users SET member_status='UNCONFIRMED' WHERE role='MEMBER' AND approved NOT IN (1,-2)");
      db.update("UPDATE users SET member_status='DELETED' WHERE role='MEMBER' AND approved=-2");
      db.update("UPDATE users SET member_status='REVIEW' WHERE role='MEMBER' AND approved=1 AND EXISTS (SELECT 1 FROM reviews r JOIN seats s ON s.id=r.seat_id WHERE r.member_id=users.id AND s.occupant_id=users.id AND r.status IN ('PLANNED','ACTIVE','AWAITING_DECISION'))");
      db.update("UPDATE users SET member_status='ACTIVE' WHERE role='MEMBER' AND approved=1 AND EXISTS (SELECT 1 FROM seats s WHERE s.occupant_id=users.id) AND NOT EXISTS (SELECT 1 FROM reviews r WHERE r.member_id=users.id AND r.status IN ('PLANNED','ACTIVE','AWAITING_DECISION'))");
      db.update("UPDATE users SET member_status='MOBILE' WHERE role='MEMBER' AND approved=1 AND NOT EXISTS (SELECT 1 FROM seats s WHERE s.occupant_id=users.id)");
    };
  }
}
