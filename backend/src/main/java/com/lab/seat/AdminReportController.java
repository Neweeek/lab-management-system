package com.lab.seat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;

/** Read-only report archive. Includes completed/cancelled reviews, never shared with members. */
@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {
  private final JdbcTemplate db;
  private final ObjectMapper json = new ObjectMapper();
  AdminReportController(JdbcTemplate db) { this.db = db; }
  private void authorize(HttpSession session) {
    if (!(session.getAttribute("uid") instanceof Number)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
    if (!"ADMIN".equals(session.getAttribute("role"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理员可查询成员报告");
  }
  private static final String JOINS = " FROM weekly_report_tasks t JOIN users u ON u.id=t.member_id JOIN reviews r ON r.id=t.review_id LEFT JOIN weekly_reports w ON w.task_id=t.id ";
  private static final String STATUS = "CASE WHEN t.status IN ('OPEN','DRAFT') AND t.due_at<? THEN 'MISSED' ELSE t.status END";

  @GetMapping
  Map<String,Object> list(HttpSession session,
      @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String status,
      @RequestParam(defaultValue="") String from, @RequestParam(defaultValue="") String to,
      @RequestParam(defaultValue="0") long reviewId, @RequestParam(defaultValue="1") int page) {
    authorize(session);
    if (!Set.of("","OPEN","DRAFT","SUBMITTED","LATE_SUBMITTED","MISSED","CANCELLED").contains(status)) throw bad("报告状态不合法");
    if(page<1 || page>100000 || reviewId<0 || q.length()>100) throw bad("查询参数不合法");
    LocalDate start=date(from), end=date(to);
    if(start!=null && end!=null && start.isAfter(end)) throw bad("开始日期不能晚于结束日期");
    String current=LabTime.nowText();
    StringBuilder where=new StringBuilder(" WHERE 1=1");
    List<Object> args=new ArrayList<>();
    if(!q.isBlank()) { where.append(" AND (u.name LIKE ? ESCAPE '\\' OR u.student_no LIKE ? ESCAPE '\\')"); String term="%"+q.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"; args.add(term);args.add(term); }
    if(!status.isBlank()) { where.append(" AND ("+STATUS+")=?");args.add(current);args.add(status); }
    if(start!=null) { where.append(" AND substr(t.due_at,1,10)>=?");args.add(start.toString()); }
    if(end!=null) { where.append(" AND substr(t.due_at,1,10)<=?");args.add(end.toString()); }
    if(reviewId>0) {where.append(" AND t.review_id=?");args.add(reviewId);}
    int total=db.queryForObject("SELECT COUNT(*)"+JOINS+where,Integer.class,args.toArray());
    int actualPage=Math.min(page,Math.max(1,(total+11)/12));
    List<Object> rowsArgs=new ArrayList<>(); rowsArgs.add(current);rowsArgs.addAll(args);rowsArgs.add((actualPage-1)*12);
    var rows=db.queryForList("SELECT t.id,t.review_id,t.member_id,t.period_index,t.due_at,"+STATUS+" status,u.name,u.student_no,r.status review_status,w.submitted_at,w.status saved_status,CASE WHEN w.id IS NULL THEN 0 ELSE 1 END has_report"+JOINS+where+" ORDER BY t.due_at DESC,t.id DESC LIMIT 12 OFFSET ?",rowsArgs.toArray());
    return Map.of("items",rows,"total",total,"page",actualPage);
  }
  @GetMapping("/{taskId}")
  Map<String,Object> detail(@PathVariable long taskId,HttpSession session) {
    authorize(session);
    var rows=db.queryForList("SELECT t.id,t.review_id,t.member_id,t.period_index,t.due_at,"+STATUS+" status,u.name,u.student_no,r.status review_status,r.reason,r.requirements,r.start_at review_start,r.end_at review_end,r.result,r.decision_note,w.sections_json,w.status saved_status,w.submitted_at,w.updated_at"+JOINS+" WHERE t.id=?",java.time.LocalDateTime.now().toString(),taskId);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"报告任务不存在");
    var report=rows.get(0); Object raw=report.remove("sections_json");
    report.put("has_report",raw!=null);
    try { report.put("sections",raw==null?Map.of():json.readValue(raw.toString(),Map.class)); }
    catch(Exception ignored) { report.put("sections",Map.of());report.put("content_error","历史材料格式异常，无法完整解析，请联系管理员检查原始数据。"); }
    return report;
  }
  private ResponseStatusException bad(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
  private LocalDate date(String value) {try{return value.isBlank()?null:LocalDate.parse(value);}catch(Exception e){throw bad("日期格式应为 YYYY-MM-DD");}}
}
