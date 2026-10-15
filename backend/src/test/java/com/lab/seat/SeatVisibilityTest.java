package com.lab.seat;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SeatVisibilityTest {
  @TempDir Path directory;
  TestDatabase database;
  ApiController api;
  MockHttpSession member;

  @BeforeEach void setup() {
    database = TestDatabase.create(directory, "seat-visibility.db");
    database.seedSeats();
    api = new ApiController(database.db, new BCryptPasswordEncoder(), new LoginAttemptGuard(5, 15));
    database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) VALUES"
        + "(1,'admin','管理员','x','ADMIN','ACTIVE',1),(2,'s2','查看者','x','MEMBER','MOBILE',1),"
        + "(3,'s3','使用同学','x','MEMBER','ACTIVE',1)");
    member = TestDatabase.session(2, "MEMBER");
  }

  @AfterEach void close() { database.close(); }

  private Map<String, Object> seat(MockHttpSession session) {
    return api.seats(session).stream().filter(row -> ((Number) row.get("id")).intValue() == 1).findFirst().orElseThrow();
  }

  private void booking(String status, String start, String end) {
    database.db.update("INSERT INTO seat_bookings(user_id,seat_id,start_at,end_at,status) VALUES(3,1,?,?,?)", start, end, status);
  }

  @Test void fixedOccupantIsVisibleToOtherMembersAndAdminsButNotAnonymousUsers() {
    database.db.update("UPDATE seats SET type='FIXED',status='OCCUPIED',occupant_id=3 WHERE id=1");
    for (MockHttpSession session : new MockHttpSession[] {member, TestDatabase.session(1, "ADMIN")}) {
      var row = seat(session);
      assertEquals("使用同学", row.get("occupant_name"));
      assertEquals("使用同学", row.get("current_user_name"));
      assertFalse(row.containsKey("student_no"));
      assertFalse(row.containsKey("password_hash"));
    }
    assertEquals(401, assertThrows(ResponseStatusException.class, () -> api.seats(new MockHttpSession())).getStatusCode().value());
  }

  @Test void onlyApprovedOngoingBookingRevealsCurrentUser() {
    var now = LabTime.now().withNano(0);
    booking("APPROVED", now.minusMinutes(10).toString(), now.plusMinutes(30).toString());
    assertEquals("使用同学", seat(member).get("current_user_name"));
    assertNull(seat(member).get("occupant_name"));
  }

  @Test void futureAndExpiredApprovedBookingsDoNotRevealTheirUsers() {
    var now = LabTime.now().withNano(0);
    booking("APPROVED", now.plusHours(1).toString(), now.plusHours(2).toString());
    booking("APPROVED", now.minusHours(2).toString(), now.toString());
    assertNull(seat(member).get("current_user_name"));
  }

  @Test void pendingRejectedAndCancelledBookingsDoNotRevealTheirUsers() {
    var now = LabTime.now().withNano(0);
    for (String status : new String[] {"PENDING", "REJECTED", "CANCELLED"}) {
      booking(status, now.minusMinutes(10).toString(), now.plusMinutes(30).toString());
    }
    assertNull(seat(member).get("current_user_name"));
  }
}
