package com.lab.seat;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminReportControllerTest {
  @TempDir Path directory;
  JdbcTemplate db; MockMvc mvc; MockHttpSession admin, member;
  @BeforeEach void setup() {
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("reports.db"));
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
    db=new JdbcTemplate(ds);mvc=standaloneSetup(new AdminReportController(db)).build();
    admin=new MockHttpSession();admin.setAttribute("uid",1L);admin.setAttribute("role","ADMIN");
    member=new MockHttpSession();member.setAttribute("uid",2L);member.setAttribute("role","MEMBER");
    db.update("INSERT INTO users(id,student_no,name,password_hash) VALUES(2,'20260001','王同学','secret'),(3,'20260002','李同学','secret')");
    db.update("INSERT INTO reviews(id,member_id,reason,start_at,end_at,status,created_by) VALUES(1,2,'历史考察','2020-01-01T00:00','2020-02-01T00:00','COMPLETED',1),(2,3,'进行中','2020-01-01T00:00','2030-01-01T00:00','ACTIVE',1),(3,2,'已取消','2020-01-01T00:00','2020-02-01T00:00','CANCELLED',1)");
    db.update("INSERT INTO weekly_report_tasks(id,review_id,member_id,period_index,due_at,status) VALUES(1,1,2,1,'2020-01-08T23:59','SUBMITTED'),(2,2,3,1,'2020-02-08T23:59','DRAFT'),(3,3,2,1,'2020-01-15T23:59','CANCELLED')");
    db.update("INSERT INTO weekly_reports(task_id,user_id,sections_json,status,submitted_at) VALUES(1,2,?, 'SUBMITTED','2020-01-07T10:00'),(2,3,?,'DRAFT',NULL)","{\"learning\":{\"title\":\"算法学习\",\"detail\":\"学习记录\"}}","{\"learning\":{\"title\":\"未正式提交\"}}");
  }
  @Test void rejectsAnonymousAndMemberForListAndDetail() throws Exception {
    for(String path:new String[]{"/api/admin/reports","/api/admin/reports/1"}){
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(get(path).session(member)).andExpect(status().isForbidden());
    }
  }
  @Test void historicalReportsAndCancelledTasksRemainQueryable() throws Exception {
    mvc.perform(get("/api/admin/reports").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
    mvc.perform(get("/api/admin/reports/1").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.review_status").value("COMPLETED")).andExpect(jsonPath("$.sections.learning.title").value("算法学习")).andExpect(jsonPath("$.password_hash").doesNotExist());
    mvc.perform(get("/api/admin/reports/3").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.review_status").value("CANCELLED")).andExpect(jsonPath("$.has_report").value(false));
  }
  @Test void filtersNameStudentNoDateStatusAndReview() throws Exception {
    mvc.perform(get("/api/admin/reports").session(admin).param("q","20260001").param("from","2020-01-08").param("to","2020-01-08").param("status","SUBMITTED").param("reviewId","1")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].name").value("王同学")).andExpect(jsonPath("$.items[0].sections_json").doesNotExist());
    mvc.perform(get("/api/admin/reports").session(admin).param("q","李").param("status","MISSED")).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].saved_status").value("DRAFT"));
    mvc.perform(get("/api/admin/reports").session(admin).param("q","%_'")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
  }
  @Test void validatesFiltersAndMissingReport() throws Exception {
    mvc.perform(get("/api/admin/reports").session(admin).param("from","bad")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/admin/reports").session(admin).param("from","2020-02-01").param("to","2020-01-01")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/admin/reports").session(admin).param("status","invalid")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/admin/reports").session(admin).param("page","0")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/admin/reports/999").session(admin)).andExpect(status().isNotFound());
  }
  @Test void paginationClampsAndDoesNotMutateDrafts() throws Exception {
    for(int i=4;i<=18;i++)db.update("INSERT INTO weekly_report_tasks(id,review_id,member_id,period_index,due_at) VALUES(?,2,3,?,'2020-03-01T00:00')",i,i);
    mvc.perform(get("/api/admin/reports").session(admin)).andExpect(jsonPath("$.items.length()").value(12)).andExpect(jsonPath("$.total").value(18));
    mvc.perform(get("/api/admin/reports").session(admin).param("page","999")).andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.items.length()").value(6));
    mvc.perform(get("/api/admin/reports/2").session(admin)).andExpect(jsonPath("$.saved_status").value("DRAFT")).andExpect(jsonPath("$.status").value("MISSED"));
    assertEquals("DRAFT",db.queryForObject("SELECT status FROM weekly_report_tasks WHERE id=2",String.class));
  }
  @Test void malformedHistoricalContentShowsExplicitError() throws Exception {
    db.update("UPDATE weekly_reports SET sections_json='not-json' WHERE task_id=1");
    mvc.perform(get("/api/admin/reports/1").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.content_error").isNotEmpty()).andExpect(jsonPath("$.has_report").value(true));
  }
}
