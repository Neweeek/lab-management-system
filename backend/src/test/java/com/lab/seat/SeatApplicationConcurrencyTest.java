package com.lab.seat;

import com.lab.seat.migration.MigrationRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SeatApplicationConcurrencyTest {
  @TempDir Path directory;
  TestDatabase database;
  ApiController api;
  org.springframework.mock.web.MockHttpSession admin, memberA, memberB;

  @BeforeEach void setup() {
    database = TestDatabase.create(directory, "concurrency.db");
    database.seedSeats();
    api = new ApiController(database.db, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(), new LoginAttemptGuard(5, 15));
    database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) VALUES(1,'admin','管理员','x','ADMIN','ACTIVE',1)");
    for (int id = 2; id <= 3; id++) {
      database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) VALUES(?,?,?,?,'MEMBER','MOBILE',1)", id, "s" + id, "成员" + id, "x");
    }
    admin = TestDatabase.session(1, "ADMIN");
    memberA = TestDatabase.session(2, "MEMBER");
    memberB = TestDatabase.session(3, "MEMBER");
  }

  private int applicationCount() {
    return database.db.queryForObject("SELECT COUNT(*) FROM seat_applications WHERE status='PENDING'", Integer.class);
  }

  private String seatStatus(long seatId) {
    return database.db.queryForObject("SELECT status FROM seats WHERE id=?", String.class, seatId);
  }

  private long firstSeatId() {
    return database.db.queryForObject("SELECT MIN(id) FROM seats", Long.class);
  }

  @Test void duplicatePendingApplicationFromSameMemberIsRejected() {
    long seat = firstSeatId();
    database.call(() -> api.applySeat(Map.of("seatId", seat, "reason", "第一次"), memberA));
    assertEquals(1, applicationCount());

    long otherSeat = database.db.queryForObject("SELECT id FROM seats WHERE id<>? ORDER BY id LIMIT 1", Long.class, seat);
    assertThrows(ResponseStatusException.class,
        () -> database.call(() -> api.applySeat(Map.of("seatId", otherSeat, "reason", "第二次"), memberA)));
    assertEquals(1, applicationCount());
  }

  @Test void seatAlreadyPendingCannotBeAppliedForAgain() {
    long seat = firstSeatId();
    database.call(() -> api.applySeat(Map.of("seatId", seat, "reason", "先申请"), memberA));
    assertEquals("PENDING", seatStatus(seat));

    assertThrows(ResponseStatusException.class,
        () -> database.call(() -> api.applySeat(Map.of("seatId", seat, "reason", "后申请"), memberB)));
    assertEquals(1, applicationCount());
    assertEquals(1, database.db.queryForObject("SELECT COUNT(*) FROM seat_applications WHERE seat_id=?", Integer.class, seat));
  }

  /**
   * 核心回归测试：两个成员在真实并发下申请同一个工位。
   *
   * <p>修复前 applySeat 不检查 UPDATE 的受影响行数，两个事务都会通过前置 SELECT 检查，
   * 最终同一工位留下两条 PENDING 申请。现在由"条件 UPDATE + 行数检查"和
   * V3 的部分唯一索引双重保证，只能有一条成功。
   */
  @Test void simultaneousApplicationsForSameSeatOnlyOneSucceeds() throws Exception {
    long seat = firstSeatId();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      List<Future<Boolean>> results = new java.util.ArrayList<>();
      for (var session : List.of(memberA, memberB)) {
        results.add(pool.submit(() -> {
          gate.await();
          try {
            database.call(() -> api.applySeat(Map.of("seatId", seat, "reason", "并发申请"), session));
            return true;
          } catch (ResponseStatusException e) {
            return false;
          }
        }));
      }
      gate.countDown();
      int successes = 0;
      for (var result : results) if (result.get(30, TimeUnit.SECONDS)) successes++;

      assertEquals(1, successes, "同一工位并发申请只应有一个成功");
      assertEquals(1, applicationCount(), "不应留下重复的待审核申请");
      assertEquals("PENDING", seatStatus(seat));
    }
  }

  @Test void approvingApplicationMarksSeatOccupiedAndMemberActive() {
    long seat = firstSeatId();
    database.call(() -> api.applySeat(Map.of("seatId", seat, "reason", "申请"), memberA));
    long applicationId = database.db.queryForObject("SELECT MAX(id) FROM seat_applications", Long.class);

    database.call(() -> api.decideApplication(applicationId, "approve", Map.of("note", "同意"), admin));

    assertEquals("OCCUPIED", seatStatus(seat));
    assertEquals("ACTIVE", database.db.queryForObject("SELECT member_status FROM users WHERE id=2", String.class));
    assertEquals(1, database.db.queryForObject("SELECT COUNT(*) FROM seat_assignments WHERE seat_id=? AND ended_at IS NULL", Integer.class, seat));
  }

  @Test void rejectingApplicationReleasesSeatToAvailable() {
    long seat = firstSeatId();
    database.call(() -> api.applySeat(Map.of("seatId", seat, "reason", "申请"), memberA));
    long applicationId = database.db.queryForObject("SELECT MAX(id) FROM seat_applications", Long.class);

    database.call(() -> api.decideApplication(applicationId, "reject", Map.of("note", "不通过"), admin));

    assertEquals("AVAILABLE", seatStatus(seat));
    assertEquals("MOBILE", database.db.queryForObject("SELECT member_status FROM users WHERE id=2", String.class));
    assertEquals(0, applicationCount());
  }

  @Test void unconfirmedMemberCannotApplyForSeat() {
    database.db.update("UPDATE users SET member_status='UNCONFIRMED', approved=0 WHERE id=2");
    assertThrows(ResponseStatusException.class,
        () -> database.call(() -> api.applySeat(Map.of("seatId", firstSeatId(), "reason", "申请"), memberA)));
  }

  @AfterEach void closeDatabase() { database.close(); }
}
