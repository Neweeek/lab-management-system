package com.lab.seat;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DataIntegrityRunner {
  @Bean
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
