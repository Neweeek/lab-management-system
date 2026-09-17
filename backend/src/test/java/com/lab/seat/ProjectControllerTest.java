package com.lab.seat;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class ProjectControllerTest {
  @TempDir Path directory;
  JdbcTemplate db; ProjectController controller; TransactionTemplate tx;
  MockHttpSession admin, member, other;
  TestDatabase database;
  @BeforeEach void setup() {
    database=TestDatabase.create(directory,"test.db");
    db=database.db; tx=database.tx; controller=new ProjectController(db);
    for(int id=1;id<=3;id++) db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) VALUES(?,?,?,?,?,'MOBILE',1)",id,"test"+id,"成员"+id,"unused",id==1?"ADMIN":"MEMBER");
    admin=session(1,"ADMIN"); member=session(2,"MEMBER"); other=session(3,"MEMBER");
  }
  /** 关闭连接池：Windows 上未关闭的 SQLite 连接会锁住 -wal/-shm，导致 @TempDir 清理失败。 */
  @AfterEach void closeDatabase(){ database.close(); }
  MockHttpSession session(long id,String role) { var s=new MockHttpSession(); s.setAttribute("uid",id);s.setAttribute("role",role);return s; }
  <T>T call(Supplier<T> fn) { return tx.execute(s -> fn.get()); }
  long create(int capacity) { return ((Number)call(() -> controller.create(new ProjectController.Draft("测试竞赛","COMPETITION","介绍","要求",capacity,LocalDate.now().plusDays(7)),admin)).get("id")).longValue(); }
  long apply(long id,MockHttpSession s) { call(() -> controller.apply(id,new ProjectController.Application("理由","技能","每周五小时"),s));return db.queryForObject("SELECT MAX(id) FROM project_applications",Long.class); }
  int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
  @Test void duplicateAndWithdraw() { long p=create(2),a=apply(p,member); assertThrows(ResponseStatusException.class,()->apply(p,member));assertThrows(ResponseStatusException.class,()->call(()->controller.withdraw(p,a,other)));call(()->controller.withdraw(p,a,member));apply(p,member);assertEquals(2,count("project_applications")); }
  @Test void approvalCapacityAndAtomicity() { long p=create(1),a=apply(p,member),b=apply(p,other);call(()->controller.decide(p,a,new ProjectController.Decision("approve","欢迎"),admin));assertThrows(ResponseStatusException.class,()->call(()->controller.decide(p,b,new ProjectController.Decision("approve",""),admin)));assertEquals(1,count("project_members"));assertEquals("PENDING",db.queryForObject("SELECT status FROM project_applications WHERE id=?",String.class,b));assertEquals(1,count("notifications")); }
  @Test void privacyAndAuthorization() { long p=create(3); apply(p,member);apply(p,other);var d=controller.detail(p,member);assertEquals(1,((List<?>)d.get("applications")).size());assertEquals(2,((List<?>)controller.detail(p,admin).get("applications")).size());assertThrows(ResponseStatusException.class,()->controller.list(new MockHttpSession(),"","",1,"newest"));assertThrows(ResponseStatusException.class,()->call(()->controller.directAdd(p,new ProjectController.Member(2),member))); }
  @Test void closeCanReviewAndCompleteIsTerminal() { long p=create(3),a=apply(p,member);call(()->controller.status(p,Map.of("status","CLOSED"),admin));assertThrows(ResponseStatusException.class,()->apply(p,other));call(()->controller.decide(p,a,new ProjectController.Decision("approve",""),admin));call(()->controller.status(p,Map.of("status","RECRUITING"),admin));apply(p,other);call(()->controller.status(p,Map.of("status","COMPLETED"),admin));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM project_applications WHERE status='PENDING'",Integer.class));assertThrows(ResponseStatusException.class,()->call(()->controller.status(p,Map.of("status","RECRUITING"),admin)));assertEquals(1,count("project_members")); }
  @Test void directAddConsumesPendingAndDoesNotChangeSeatStatus() { long p=create(3);apply(p,member);call(()->controller.directAdd(p,new ProjectController.Member(2),admin));assertEquals("APPROVED",db.queryForObject("SELECT status FROM project_applications",String.class));assertEquals("MOBILE",db.queryForObject("SELECT member_status FROM users WHERE id=2",String.class));call(()->controller.remove(p,2,admin));assertEquals(0,count("project_members")); }
  @Test void deadlineAndUnconfirmedMemberAreRejected() { long p=create(3);db.update("UPDATE projects SET deadline=?",LocalDate.now().minusDays(1).toString());assertThrows(ResponseStatusException.class,()->apply(p,member));db.update("UPDATE projects SET deadline=?",LocalDate.now().plusDays(1).toString());db.update("UPDATE users SET approved=0 WHERE id=2");assertThrows(ResponseStatusException.class,()->apply(p,member));assertEquals(0,count("project_applications")); }
  @Test void simultaneousApprovalsCannotOverfill() throws Exception { long p=create(1),a=apply(p,member),b=apply(p,other);try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();for(long id:List.of(a,b))results.add(pool.submit(()->{gate.await();try{call(()->controller.decide(p,id,new ProjectController.Decision("approve",""),admin));return true;}catch(ResponseStatusException e){return false;}}));gate.countDown();int successes=0;for(var result:results)if(result.get(15,TimeUnit.SECONDS))successes++;assertEquals(1,successes);assertEquals(1,count("project_members"));} }
  ApiController api() { return new ApiController(db,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(),new LoginAttemptGuard(5,15)); }
  @Test void completedReviewCannotBeSubmitted() {
    db.update("INSERT INTO reviews(id,member_id,reason,start_at,end_at,status,created_by) VALUES(1,2,'测试','2020-01-01T00:00','2020-01-08T00:00','COMPLETED',1)");
    db.update("INSERT INTO weekly_report_tasks(id,review_id,member_id,period_index,due_at) VALUES(1,1,2,1,'2020-01-08T00:00')");
    assertThrows(ResponseStatusException.class,()->api().submitReport(1,Map.of("mode","submit","sections",Map.of()),member));assertEquals(0,count("weekly_reports"));
  }
  @Test void futureBookingBlocksFixedAssignmentButPastBookingDoesNot() {
    db.update("INSERT INTO seats(id,code,row_no,col_no,type) VALUES(1,'T1',1,1,'MOBILE')");
    db.update("INSERT INTO seat_bookings(user_id,seat_id,start_at,end_at,status) VALUES(3,1,?,?,'APPROVED')",LocalDate.now().plusDays(1)+"T09:00",LocalDate.now().plusDays(1)+"T10:00");
    assertThrows(ResponseStatusException.class,()->call(()->api().assignSeat(1,Map.of("memberId",2),admin)));assertEquals(0,count("seat_assignments"));
    db.update("UPDATE seat_bookings SET start_at='2020-01-01T09:00',end_at='2020-01-01T10:00'");call(()->api().assignSeat(1,Map.of("memberId",2),admin));assertEquals(1,count("seat_assignments"));
  }
  @Test void memberListDoesNotExposePasswordHash() { assertFalse(api().members(admin).get(0).containsKey("password_hash")); }
  @Test void httpRejectsInvalidDraftAndAnonymousAccess() throws Exception {
    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/projects")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/projects").session(admin).contentType("application/json").content("{\"name\":\"\",\"type\":\"COMPETITION\",\"description\":\"介绍\",\"requirements\":\"要求\",\"capacity\":0,\"deadline\":\"2030-01-01\"}"))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());assertEquals(0,count("projects"));
  }
  @Test void memberCanCreateOnlyCompetitionAndBecomesTeamOwner() {
    var draft=new ProjectController.Draft("学生竞赛","COMPETITION","介绍","要求",2,LocalDate.now().plusDays(7));
    long p=((Number)call(()->controller.create(draft,member)).get("id")).longValue();
    assertEquals(1,count("project_members"));assertEquals(true,controller.detail(p,member).get("can_manage"));assertEquals(false,controller.detail(p,other).get("can_manage"));
    assertThrows(ResponseStatusException.class,()->call(()->controller.create(new ProjectController.Draft("课题","HORIZONTAL","介绍","要求",3,LocalDate.now().plusDays(7)),member)));
    db.update("UPDATE users SET approved=0 WHERE id=3");assertThrows(ResponseStatusException.class,()->call(()->controller.create(draft,other)));
  }
  @Test void ownerManagesOnlyTheirCompetitionAndCannotChangeType() {
    var draft=new ProjectController.Draft("学生竞赛","COMPETITION","介绍","要求",2,LocalDate.now().plusDays(7));
    long p=((Number)call(()->controller.create(draft,member)).get("id")).longValue();long a=apply(p,other);
    assertEquals(1,((List<?>)controller.detail(p,member).get("applications")).size());
    call(()->controller.decide(p,a,new ProjectController.Decision("approve","欢迎"),member));
    assertEquals(2,count("project_members"));assertThrows(ResponseStatusException.class,()->call(()->controller.edit(p,draft,other)));
    assertThrows(ResponseStatusException.class,()->call(()->controller.edit(p,new ProjectController.Draft("课题","VERTICAL","介绍","要求",3,LocalDate.now().plusDays(7)),member)));
    call(()->controller.edit(p,draft,member));call(()->controller.status(p,Map.of("status","CLOSED"),member));
    assertThrows(ResponseStatusException.class,()->call(()->controller.remove(p,2,member)));
    assertThrows(ResponseStatusException.class,()->call(()->controller.directAdd(p,new ProjectController.Member(3),member)));
    call(()->controller.remove(p,3,member));assertEquals(1,count("project_members"));
  }
  @Test void publicationSortingUsesTimestampThenId() {
    long a=create(2),b=create(2),c=create(2);db.update("UPDATE projects SET created_at='2020-01-01 00:00:00' WHERE id IN (?,?)",a,c);db.update("UPDATE projects SET created_at='2021-01-01 00:00:00' WHERE id=?",b);
    var newest=(List<Map<String,Object>>)controller.list(member,"","",1,"newest").get("items");
    var oldest=(List<Map<String,Object>>)controller.list(member,"","",1,"oldest").get("items");
    assertEquals(b,((Number)newest.get(0).get("id")).longValue());assertEquals(a,((Number)oldest.get(0).get("id")).longValue());assertEquals(c,((Number)oldest.get(1).get("id")).longValue());
    assertThrows(ResponseStatusException.class,()->controller.list(member,"","",1,"drop table users"));
  }
}
