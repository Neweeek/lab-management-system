package com.lab.seat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MemberAdminControllerTest {
  @TempDir Path dir;JdbcTemplate db;TransactionTemplate tx;MemberAdminController controller;MockHttpSession admin,member;MockMvc mvc;TestDatabase database;
  @BeforeEach void setup(){database=TestDatabase.create(dir,"users.db");db=database.db;tx=database.tx;controller=new MemberAdminController(db);
    db.update("INSERT INTO users(id,student_no,name,password_hash,role,approved,member_status) VALUES(1,'admin','管理员','unused','ADMIN',1,'ACTIVE'),(2,'student2','学生乙','unused','MEMBER',1,'REVIEW'),(3,'student3','学生丙','unused','MEMBER',0,'UNCONFIRMED'),(4,'student4','学生丁','unused','MEMBER',0,'UNCONFIRMED'),(5,'admin2','管理员乙','unused','ADMIN',1,'ACTIVE')");
    admin=session(1,"ADMIN");member=session(2,"MEMBER");mvc=standaloneSetup(controller,new ProjectController(db)).addFilters(new AccountStateFilter(db),new CsrfTokenFilter()).build();
  }
  /** 关闭连接池：Windows 上未关闭的 SQLite 连接会锁住 -wal/-shm，导致 @TempDir 清理失败。 */
  @AfterEach void closeDatabase(){database.close();}
  MockHttpSession session(long id,String role){var s=new MockHttpSession();s.setAttribute("uid",id);s.setAttribute("role",role);s.setAttribute("csrfToken","test-csrf");return s;}
  <T>T call(Supplier<T> fn){return tx.execute(s->fn.get());}
  String value(String table,String column,int id){return db.queryForObject("SELECT "+column+" FROM "+table+" WHERE id=?",String.class,id);}
  void review(String status){db.update("INSERT INTO seats(id,code,row_no,col_no,type,status,occupant_id,review_mode) VALUES(1,'T1',1,1,'FIXED','OCCUPIED',2,1)");db.update("INSERT INTO reviews(id,member_id,seat_id,reason,start_at,end_at,status,created_by) VALUES(1,2,1,'考察','2020-01-01T00:00','2030-01-01T00:00',?,1)",status);db.update("INSERT INTO weekly_report_tasks(id,review_id,member_id,period_index,due_at,status) VALUES(1,1,2,1,'2020-01-08T00:00','SUBMITTED'),(2,1,2,2,'2030-01-08T00:00','DRAFT')");db.update("INSERT INTO weekly_reports(task_id,user_id,sections_json,status) VALUES(1,2,'{}','SUBMITTED'),(2,2,'{}','DRAFT')");}
  @Test void batchIsScopedDeduplicatedAndIdempotent(){var result=call(()->controller.batch(new MemberAdminController.Batch(List.of(3L,3L,4L,999L,1L)),admin));assertEquals(2,result.get("approved"));assertEquals(2,result.get("skipped"));assertEquals("1",value("users","approved",3));assertEquals("MOBILE",value("users","member_status",4));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM notifications",Integer.class));assertEquals(0,call(()->controller.batch(new MemberAdminController.Batch(List.of(3L,4L)),admin)).get("approved"));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM notifications",Integer.class));}
  @Test void batchRejectsMemberAndInvalidPayloadAndCsrf() throws Exception {
    mvc.perform(post("/api/admin/registrations/batch-approve").session(member).header("X-CSRF-Token","test-csrf").contentType("application/json").content("{\"ids\":[3]}")).andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/registrations/batch-approve").session(admin).contentType("application/json").content("{\"ids\":[3]}")).andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/registrations/batch-approve").session(admin).header("X-CSRF-Token","test-csrf").contentType("application/json").content("{\"ids\":[]}")).andExpect(status().isBadRequest());
  }
  @Test void deleteBlocksSelfOtherAdminWrongConfirmationAndOwner(){assertThrows(ResponseStatusException.class,()->call(()->controller.delete(1,new MemberAdminController.Removal("admin","原因"),admin)));assertThrows(ResponseStatusException.class,()->call(()->controller.delete(5,new MemberAdminController.Removal("admin2","原因"),admin)));assertThrows(ResponseStatusException.class,()->call(()->controller.delete(2,new MemberAdminController.Removal("wrong","原因"),admin)));
    db.update("INSERT INTO projects(name,type,description,requirements,capacity,deadline,created_by) VALUES('竞赛','COMPETITION','介绍','要求',3,'2030-01-01',2)");assertThrows(ResponseStatusException.class,()->call(()->controller.delete(2,new MemberAdminController.Removal("student2","原因"),admin)));assertEquals("1",value("users","approved",2));}
  @Test void deleteReleasesResourcesButPreservesHistoryAndRevokesSession() throws Exception {review("ACTIVE");db.update("INSERT INTO seat_assignments(seat_id,user_id) VALUES(1,2)");db.update("INSERT INTO seat_bookings(user_id,seat_id,start_at,end_at,status) VALUES(2,1,'2030-01-01T08:00','2030-01-01T10:00','APPROVED')");
    call(()->controller.delete(2,new MemberAdminController.Removal("student2","离开实验室"),admin));assertEquals("-2",value("users","approved",2));assertEquals("AVAILABLE",value("seats","status",1));assertEquals("CANCELLED",value("seat_bookings","status",1));assertEquals("CANCELLED",value("reviews","status",1));assertEquals("SUBMITTED",value("weekly_report_tasks","status",1));assertEquals("CANCELLED",value("weekly_report_tasks","status",2));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM weekly_reports",Integer.class));
    mvc.perform(get("/api/projects").session(member)).andExpect(status().isUnauthorized());assertTrue(member.isInvalid());
    new DataIntegrityRunner().repairReleasedSeatReviews(db).run();assertEquals("DELETED",value("users","member_status",2));
  }
  @Test void deletedAccountsAreNotReapproved(){call(()->controller.delete(3,new MemberAdminController.Removal("student3","测试账号"),admin));assertEquals(0,call(()->controller.batch(new MemberAdminController.Batch(List.of(3L)),admin)).get("approved"));assertEquals("-2",value("users","approved",3));}
  @Test void cancelPreservesSeatAndSubmittedMaterial(){review("ACTIVE");call(()->controller.cancelReview(1,new MemberAdminController.Cancellation("提前通过考察，无需继续"),admin));assertEquals("CANCELLED",value("reviews","status",1));assertEquals("ADMIN_CANCELLED",value("reviews","result",1));assertEquals("ACTIVE",value("users","member_status",2));assertEquals("OCCUPIED",value("seats","status",1));assertEquals("2",value("seats","occupant_id",1));assertEquals("0",value("seats","review_mode",1));assertEquals("SUBMITTED",value("weekly_report_tasks","status",1));assertEquals("CANCELLED",value("weekly_report_tasks","status",2));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM weekly_reports",Integer.class));assertThrows(ResponseStatusException.class,()->call(()->controller.cancelReview(1,new MemberAdminController.Cancellation("重复"),admin)));}
  @Test void cancelAllowsPlannedAndAwaitingDecisionButNotCompleted(){review("PLANNED");call(()->controller.cancelReview(1,new MemberAdminController.Cancellation("计划调整"),admin));db.update("UPDATE reviews SET status='AWAITING_DECISION' WHERE id=1");call(()->controller.cancelReview(1,new MemberAdminController.Cancellation("无需决策"),admin));db.update("UPDATE reviews SET status='COMPLETED' WHERE id=1");assertThrows(ResponseStatusException.class,()->call(()->controller.cancelReview(1,new MemberAdminController.Cancellation("无效"),admin)));}
  @Test void memberCannotDeletePreviewOrCancelAndReasonRequired() throws Exception {review("ACTIVE");mvc.perform(get("/api/admin/members/3/delete-preview").session(member)).andExpect(status().isForbidden());mvc.perform(post("/api/admin/reviews/1/cancel").session(member).header("X-CSRF-Token","test-csrf").contentType("application/json").content("{\"reason\":\"越权\"}")).andExpect(status().isForbidden());mvc.perform(post("/api/admin/reviews/1/cancel").session(admin).header("X-CSRF-Token","test-csrf").contentType("application/json").content("{\"reason\":\"\"}")).andExpect(status().isBadRequest());}
}
