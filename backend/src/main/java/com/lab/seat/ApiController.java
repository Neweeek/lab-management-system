package com.lab.seat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final JdbcTemplate db;
  private final PasswordEncoder passwordEncoder;
  private final LoginAttemptGuard loginAttemptGuard;
  private final ObjectMapper json = new ObjectMapper();

  ApiController(JdbcTemplate db, PasswordEncoder passwordEncoder, LoginAttemptGuard loginAttemptGuard) { this.db = db; this.passwordEncoder = passwordEncoder; this.loginAttemptGuard = loginAttemptGuard; }
  private String now() { return LocalDateTime.now().toString(); }
  private long uid(HttpSession session) { Object value = session.getAttribute("uid"); if (value == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"); return (long) value; }
  private void admin(HttpSession session) { if (!"ADMIN".equals(session.getAttribute("role"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限"); }
  private Map<String, Object> one(String sql, Object... values) { try { return db.queryForMap(sql, values); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到对应记录"); } }
  private boolean exists(String sql, Object... values) { return !db.queryForList(sql, values).isEmpty(); }
  private void audit(long actor, String action, String target, String detail) { db.update("INSERT INTO audit_logs(actor_id,action,target,detail) VALUES(?,?,?,?)", actor, action, target, detail); }
  private void notice(long user, String title, String content) { db.update("INSERT INTO notifications(user_id,title,content) VALUES(?,?,?)", user, title, content); }
  private LocalDateTime reviewDate(String value, boolean endOfDay) { try { return value.length() == 10 ? LocalDate.parse(value).atTime(endOfDay ? LocalTime.of(23, 59, 59) : LocalTime.MIN) : LocalDateTime.parse(value); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "考察日期格式不正确"); } }
  private LocalDateTime reportPeriodStart(long reviewId, int periodIndex, String reviewStartAt) {
    if (periodIndex == 1) return LocalDateTime.parse(reviewStartAt);
    String previousDue = db.queryForObject("SELECT due_at FROM weekly_report_tasks WHERE review_id=? AND period_index=?", String.class, reviewId, periodIndex - 1);
    return LocalDateTime.parse(previousDue).toLocalDate().plusDays(1).atStartOfDay();
  }
  private Object normalizeReportSections(Object rawSections) {
    if (!(rawSections instanceof Map<?, ?> sections)) return Map.of();
    Map<String, Object> normalized = new LinkedHashMap<>();
    sections.forEach((sectionKey, rawSection) -> {
      if (!(rawSection instanceof Map<?, ?> section)) { normalized.put(String.valueOf(sectionKey), rawSection); return; }
      boolean omitted = Boolean.parseBoolean(String.valueOf(section.get("omitted")));
      Map<String, Object> copy = new LinkedHashMap<>();
      section.forEach((field, value) -> {
        if (!"omitted".equals(String.valueOf(field))) copy.put(String.valueOf(field), omitted ? "无" : value);
      });
      normalized.put(String.valueOf(sectionKey), copy);
    });
    return normalized;
  }
  private void syncStates() { String current = now(); db.update("UPDATE reviews SET status='ACTIVE' WHERE status='PLANNED' AND start_at<=?", current); db.update("UPDATE reviews SET status='AWAITING_DECISION' WHERE status='ACTIVE' AND end_at<=?", current); db.update("UPDATE weekly_report_tasks SET status='MISSED' WHERE status IN ('OPEN','DRAFT') AND due_at<?", current); }
  private Map<String, Object> profile(long userId) { return one("SELECT id,student_no,name,gender,role,member_status,approved,created_at FROM users WHERE id=?", userId); }
  private String input(Map<String, ?> body, String key) { Object value = body.get(key); return value == null ? "" : String.valueOf(value).trim(); }
  private long id(Map<String, ?> body, String key) { try { return Long.parseLong(input(body, key)); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 不正确"); } }

  @PostMapping("/auth/login")
  Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
    String studentNo = input(body, "studentNo");
    String password = input(body, "password");
    if (studentNo.isBlank() || password.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写学号和密码");
    loginAttemptGuard.verifyAllowed(studentNo, request);
    var rows = db.queryForList("SELECT * FROM users WHERE student_no=? AND approved=1", studentNo);
    if (rows.isEmpty() || !passwordEncoder.matches(password, String.valueOf(rows.get(0).get("password_hash")))) { loginAttemptGuard.recordFailure(studentNo, request); throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "学号或密码错误，或账号尚未审核"); }
    loginAttemptGuard.recordSuccess(studentNo, request);
    HttpSession previous = request.getSession(false); if (previous != null) previous.invalidate();
    HttpSession session = request.getSession(true);
    var user = rows.get(0); String csrfToken = UUID.randomUUID().toString(); session.setAttribute("uid", ((Number) user.get("id")).longValue()); session.setAttribute("role", user.get("role")); session.setAttribute("csrfToken", csrfToken);
    return Map.of("user", profile(((Number) user.get("id")).longValue()), "csrfToken", csrfToken);
  }

  @PostMapping("/auth/logout")
  Map<String, String> logout(HttpSession session) { session.invalidate(); return Map.of("message", "已退出登录"); }

  @PostMapping("/auth/password")
  @Transactional
  Map<String, String> changePassword(@RequestBody Map<String, String> body, HttpSession session) {
    long userId = uid(session); String currentPassword = input(body, "currentPassword"), nextPassword = input(body, "nextPassword");
    if (nextPassword.length() < 10 || nextPassword.length() > 72) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码长度须为 10 至 72 位");
    var account = one("SELECT password_hash FROM users WHERE id=?", userId);
    if (!passwordEncoder.matches(currentPassword, String.valueOf(account.get("password_hash")))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "当前密码不正确");
    db.update("UPDATE users SET password_hash=? WHERE id=?", passwordEncoder.encode(nextPassword), userId);
    audit(userId, "PASSWORD_CHANGED", "member:" + userId, "用户自主修改密码");
    return Map.of("message", "密码已更新");
  }

  @PostMapping("/auth/register")
  Map<String, String> register(@Valid @RequestBody Register request) {
    if (exists("SELECT id FROM users WHERE student_no=?", request.studentNo())) throw new ResponseStatusException(HttpStatus.CONFLICT, "该学号已注册或已提交申请");
    db.update("INSERT INTO users(student_no,name,gender,password_hash,member_status) VALUES(?,?,?,?,?)", request.studentNo(), request.name(), request.gender(), passwordEncoder.encode(request.password()), "UNCONFIRMED");
    return Map.of("message", "注册申请已提交，请等待管理员审核");
  }

  @GetMapping("/me") Map<String, Object> me(HttpSession session) { return profile(uid(session)); }
  @GetMapping("/csrf") Map<String, String> csrf(HttpSession session) { uid(session); Object token = session.getAttribute("csrfToken"); if (token == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录"); return Map.of("csrfToken", String.valueOf(token)); }
  @GetMapping("/seats") List<Map<String, Object>> seats() { return db.queryForList("SELECT s.*,u.name occupant_name FROM seats s LEFT JOIN users u ON s.occupant_id=u.id ORDER BY row_no,col_no"); }
  @GetMapping("/notifications") List<Map<String, Object>> notifications(HttpSession session) { return db.queryForList("SELECT * FROM notifications WHERE user_id=? ORDER BY id DESC", uid(session)); }
  @PostMapping("/notifications/{id}/read") Map<String, String> readNotice(@PathVariable long id, HttpSession session) { db.update("UPDATE notifications SET read_flag=1 WHERE id=? AND user_id=?", id, uid(session)); return Map.of("message", "已读"); }

  @PostMapping("/seat-applications")
  @Transactional
  Map<String, String> applySeat(@RequestBody Map<String, Object> body, HttpSession session) {
    long userId = uid(session); var member = profile(userId);
    if (!Set.of("ACTIVE", "MOBILE").contains(String.valueOf(member.get("member_status")))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅正式成员或流动成员可申请固定工位");
    if (exists("SELECT id FROM seats WHERE occupant_id=?", userId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "你已拥有固定工位");
    if (exists("SELECT id FROM seat_applications WHERE user_id=? AND status='PENDING'", userId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "你已有待审核工位申请");
    long seatId = id(body, "seatId");
    if (!exists("SELECT id FROM seats WHERE id=? AND type='MOBILE' AND status='AVAILABLE'", seatId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "该工位当前不可申请或已被预留");
    db.update("UPDATE seats SET status='PENDING' WHERE id=? AND status='AVAILABLE'", seatId);
    db.update("INSERT INTO seat_applications(user_id,seat_id,reason) VALUES(?,?,?)", userId, seatId, input(body, "reason"));
    audit(userId, "SEAT_APPLICATION", "seat:" + seatId, input(body, "reason")); return Map.of("message", "固定工位申请已提交");
  }

  @GetMapping("/seat-applications/my") List<Map<String, Object>> mySeatApplications(HttpSession session) { return db.queryForList("SELECT a.*,s.code FROM seat_applications a JOIN seats s ON s.id=a.seat_id WHERE a.user_id=? ORDER BY a.id DESC", uid(session)); }

  @PostMapping("/seat-bookings")
  @Transactional
  Map<String, String> book(@RequestBody Map<String, String> body, HttpSession session) {
    long userId = uid(session); var me = profile(userId);
    if (!Set.of("ACTIVE", "MOBILE").contains(String.valueOf(me.get("member_status"))) || exists("SELECT id FROM seats WHERE occupant_id=?", userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅正式成员或流动成员且未拥有固定工位时可以预约");
    long seatId = Long.parseLong(body.get("seatId")); LocalDateTime start = LocalDateTime.parse(body.get("startAt")); LocalDateTime end = LocalDateTime.parse(body.get("endAt"));
    if (!start.isAfter(LocalDateTime.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预约开始时间必须晚于当前时间");
    if (!end.isAfter(start) || Duration.between(start, end).toMinutes() > 240) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预约时长必须在4小时以内");
    if (start.isAfter(LocalDateTime.now().plusDays(7))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最多只能提前7天申请");
    if (!exists("SELECT id FROM seats WHERE id=? AND type='MOBILE' AND status='AVAILABLE'", seatId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择空闲流动工位");
    if (exists("SELECT id FROM seat_bookings WHERE seat_id=? AND status IN ('PENDING','APPROVED') AND start_at<? AND end_at>?", seatId, end.toString(), start.toString()) || exists("SELECT id FROM seat_bookings WHERE user_id=? AND status IN ('PENDING','APPROVED') AND start_at<? AND end_at>?", userId, end.toString(), start.toString())) throw new ResponseStatusException(HttpStatus.CONFLICT, "该时间段已有预约冲突");
    String status = start.isAfter(LocalDateTime.now().plusDays(3)) ? "PENDING" : "APPROVED";
    db.update("INSERT INTO seat_bookings(user_id,seat_id,start_at,end_at,status) VALUES(?,?,?,?,?)", userId, seatId, start, end, status);
    if ("APPROVED".equals(status)) notice(userId, "流动工位预约已确认", "你的流动工位预约已自动通过，请按时使用工位。");
    audit(userId, "MOBILE_SEAT_BOOKING", "seat:" + seatId, start + " - " + end);
    return Map.of("message", status.equals("PENDING") ? "预约申请已提交，等待管理员审批" : "流动工位预约成功");
  }

  @GetMapping("/seat-bookings/my") List<Map<String, Object>> myBookings(HttpSession session) { return db.queryForList("SELECT b.*,s.code FROM seat_bookings b JOIN seats s ON s.id=b.seat_id WHERE b.user_id=? ORDER BY b.start_at DESC", uid(session)); }
  @PostMapping("/seat-bookings/{id}/cancel") Map<String, String> cancelBooking(@PathVariable long id, HttpSession session) { if (db.update("UPDATE seat_bookings SET status='CANCELLED' WHERE id=? AND user_id=? AND status IN ('PENDING','APPROVED')", id, uid(session)) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预约不存在或不能取消"); return Map.of("message", "预约已取消"); }
  @GetMapping("/seat-bookings/availability")
  Map<String, Object> availability(@RequestParam(required = false) String from, @RequestParam(required = false) String to, HttpSession session) {
    long userId = uid(session);
    LocalDateTime start = from == null || from.isBlank() ? LocalDateTime.now().withMinute(0).withSecond(0).withNano(0) : LocalDateTime.parse(from);
    LocalDateTime end = to == null || to.isBlank() ? start.plusDays(7) : LocalDateTime.parse(to);
    if (!end.isAfter(start) || Duration.between(start, end).toDays() > 8) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "可查看范围最多为未来7天");
    var mobileSeats = db.queryForList("SELECT id,code,status FROM seats WHERE type='MOBILE' AND status='AVAILABLE' ORDER BY row_no,col_no");
    String now = LocalDateTime.now().toString();
    var reservations = db.queryForList("SELECT b.id,b.seat_id,s.code,b.start_at,b.end_at,b.status,CASE WHEN b.user_id=? THEN 1 ELSE 0 END mine,CASE WHEN b.status='APPROVED' AND b.start_at<=? AND b.end_at>? THEN u.name ELSE NULL END current_user_name FROM seat_bookings b JOIN seats s ON s.id=b.seat_id JOIN users u ON u.id=b.user_id WHERE b.status IN ('PENDING','APPROVED') AND b.start_at<? AND b.end_at>? ORDER BY b.start_at", userId, now, now, end.toString(), start.toString());
    return Map.of("from", start.toString(), "to", end.toString(), "seats", mobileSeats, "reservations", reservations);
  }

  @GetMapping("/report-tasks/my")
  List<Map<String, Object>> myReportTasks(HttpSession session) {
    syncStates();
    var rows = db.queryForList("SELECT t.*,r.reason,r.start_at review_start_at,r.end_at,w.sections_json,w.submitted_at FROM weekly_report_tasks t JOIN reviews r ON r.id=t.review_id LEFT JOIN weekly_reports w ON w.task_id=t.id WHERE t.member_id=? AND r.status IN ('PLANNED','ACTIVE','AWAITING_DECISION') ORDER BY t.due_at", uid(session));
    LocalDateTime current = LocalDateTime.now();
    for (var task : rows) {
      long reviewId = ((Number) task.get("review_id")).longValue();
      int periodIndex = ((Number) task.get("period_index")).intValue();
      LocalDateTime periodStart = reportPeriodStart(reviewId, periodIndex, String.valueOf(task.get("review_start_at")));
      task.put("period_start", periodStart.toString());
      task.put("can_submit", !current.isBefore(periodStart));
    }
    return rows;
  }

  @PostMapping("/report-tasks/{taskId}/report")
  @Transactional
  Map<String, String> submitReport(@PathVariable long taskId, @RequestBody Map<String, Object> body, HttpSession session) throws Exception {
    long userId = uid(session); var task = one("SELECT t.*,r.start_at review_start_at FROM weekly_report_tasks t JOIN reviews r ON r.id=t.review_id WHERE t.id=? AND t.member_id=? AND r.status IN ('PLANNED','ACTIVE','AWAITING_DECISION')", taskId, userId); LocalDateTime periodStart = reportPeriodStart(((Number) task.get("review_id")).longValue(), ((Number) task.get("period_index")).intValue(), String.valueOf(task.get("review_start_at"))); if (LocalDateTime.now().isBefore(periodStart)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "下一周周报尚未开始，当前只能填写当周材料"); String mode = input(body, "mode"); if(!Set.of("save","submit").contains(mode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"周报操作不合法"); String status = "save".equals(mode) ? "DRAFT" : (LocalDateTime.now().isAfter(LocalDateTime.parse(String.valueOf(task.get("due_at")))) ? "LATE_SUBMITTED" : "SUBMITTED");
    String sections = json.writeValueAsString(normalizeReportSections(body.getOrDefault("sections", Map.of())));
    if (exists("SELECT id FROM weekly_reports WHERE task_id=?", taskId)) db.update("UPDATE weekly_reports SET sections_json=?,status=?,submitted_at=CASE WHEN ?='DRAFT' THEN submitted_at ELSE CURRENT_TIMESTAMP END,updated_at=CURRENT_TIMESTAMP WHERE task_id=?", sections, status, status, taskId);
    else db.update("INSERT INTO weekly_reports(task_id,user_id,sections_json,status,submitted_at) VALUES(?,?,?,?,CASE WHEN ?='DRAFT' THEN NULL ELSE CURRENT_TIMESTAMP END)", taskId, userId, sections, status, status);
    db.update("UPDATE weekly_report_tasks SET status=? WHERE id=?", status, taskId); audit(userId, "WEEKLY_REPORT_" + status, "task:" + taskId, "考察周报"); return Map.of("message", status.equals("DRAFT") ? "草稿已保存" : "周报已提交", "status", status);
  }

  @GetMapping("/special-circumstances/my") List<Map<String, Object>> myCircumstances(HttpSession session) { return db.queryForList("SELECT * FROM special_circumstances WHERE user_id=? ORDER BY start_at DESC", uid(session)); }
  @PostMapping("/special-circumstances") Map<String, String> addCircumstance(@RequestBody Map<String, Object> body, HttpSession session) { long userId = uid(session); db.update("INSERT INTO special_circumstances(user_id,type,start_at,end_at,description,evidence_url) VALUES(?,?,?,?,?,?)", userId, input(body,"type"), input(body,"startAt"), input(body,"endAt"), input(body,"description"), input(body,"evidenceUrl")); audit(userId,"SPECIAL_CIRCUMSTANCE","member:"+userId,input(body,"type")); return Map.of("message","特殊情况已登记"); }

  @GetMapping("/admin/overview")
  Map<String, Object> overview(HttpSession session) { admin(session); syncStates(); return Map.of("availableSeats", db.queryForObject("SELECT COUNT(*) FROM seats WHERE type='MOBILE' AND status='AVAILABLE'", Integer.class), "pendingRegistrations", db.queryForObject("SELECT COUNT(*) FROM users WHERE approved=0", Integer.class), "pendingApplications", db.queryForObject("SELECT COUNT(*) FROM seat_applications WHERE status='PENDING'", Integer.class), "activeReviews", db.queryForObject("SELECT COUNT(*) FROM reviews WHERE status='ACTIVE'", Integer.class), "awaitingDecision", db.queryForObject("SELECT COUNT(*) FROM reviews WHERE status='AWAITING_DECISION'", Integer.class)); }
  private void ensureNoFutureBookings(long seatId) { if(exists("SELECT id FROM seat_bookings WHERE seat_id=? AND status IN ('PENDING','APPROVED') AND datetime(end_at)>datetime(?)",seatId,now())) throw new ResponseStatusException(HttpStatus.CONFLICT,"工位仍有当前或未来预约，请先处理预约再分配固定工位"); }

  @GetMapping("/admin/members") List<Map<String, Object>> members(HttpSession session) { admin(session); return db.queryForList("SELECT u.id,u.student_no,u.name,u.gender,u.role,u.member_status,u.approved,u.created_at,s.code seat_code FROM users u LEFT JOIN seats s ON s.occupant_id=u.id WHERE u.approved<>-2 ORDER BY u.approved,u.id DESC"); }
  @GetMapping("/admin/registrations") List<Map<String, Object>> registrations(HttpSession session) { admin(session); return db.queryForList("SELECT id,student_no,name,gender,member_status,created_at FROM users WHERE approved=0 ORDER BY id DESC"); }

  @PostMapping("/admin/registrations/{id}/approve") @Transactional
  Map<String, String> approveRegistration(@PathVariable long id, HttpSession session) { long actor=uid(session);admin(session); if(db.update("UPDATE users SET approved=1,member_status='MOBILE' WHERE id=? AND approved=0",id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"申请不存在或已处理"); notice(id,"注册审核通过","你的实验室账号已审核通过，目前为流动成员，可预约流动工位或申请固定工位。"); audit(actor,"REGISTER_APPROVED","member:"+id,"设为流动成员");return Map.of("message","已通过，成员状态为流动成员"); }
  @PostMapping("/admin/registrations/{id}/reject") @Transactional
  Map<String, String> rejectRegistration(@PathVariable long id,@RequestBody(required=false) Map<String,String> body,HttpSession session){long actor=uid(session);admin(session);if(db.update("UPDATE users SET approved=-1 WHERE id=? AND approved=0",id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"申请不存在或已处理");notice(id,"注册审核未通过",body==null?"请联系管理员了解原因。":input(body,"reason"));audit(actor,"REGISTER_REJECTED","member:"+id,body==null?"":input(body,"reason"));return Map.of("message","已拒绝");}
  @PostMapping("/admin/members/{id}/status") @Transactional
  Map<String,String> memberStatus(@PathVariable long id,@RequestBody Map<String,String> body,HttpSession session){admin(session);throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"成员身份由注册审核、固定工位和考察流程自动维护，不能手动修改");}

  @PostMapping("/admin/seats/{seatId}/type") @Transactional
  Map<String,String> setSeatType(@PathVariable long seatId,@RequestBody Map<String,String> body,HttpSession session){long actor=uid(session);admin(session);String type=input(body,"type");if(!Set.of("FIXED","MOBILE","DISABLED").contains(type))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"工位类型不合法");var seat=one("SELECT * FROM seats WHERE id=?",seatId);boolean occupied=seat.get("occupant_id")!=null;boolean pending="PENDING".equals(seat.get("status"));if("DISABLED".equals(type)){if(occupied||pending)throw new ResponseStatusException(HttpStatus.CONFLICT,"工位已有成员或待处理申请，请先释放工位或处理申请后再停用");if(exists("SELECT id FROM seat_bookings WHERE seat_id=? AND status IN ('PENDING','APPROVED') AND datetime(end_at)>datetime(?)",seatId,now()))throw new ResponseStatusException(HttpStatus.CONFLICT,"工位有有效预约，请先取消预约后再停用");db.update("UPDATE seats SET type='DISABLED',status='DISABLED',review_mode=0 WHERE id=?",seatId);}else if(occupied){if(!"FIXED".equals(type))throw new ResponseStatusException(HttpStatus.CONFLICT,"已有固定成员的工位只能保持固定，释放成员后将自动转为流动工位");db.update("UPDATE seats SET type='FIXED',status='OCCUPIED' WHERE id=?",seatId);}else{if("FIXED".equals(type))throw new ResponseStatusException(HttpStatus.CONFLICT,"空工位默认为流动工位；请通过直接分配或批准固定工位申请使其成为固定工位");if(pending)throw new ResponseStatusException(HttpStatus.CONFLICT,"工位有待处理申请，请先审核或拒绝申请后再调整类型");db.update("UPDATE seats SET type='MOBILE',status='AVAILABLE',review_mode=0 WHERE id=?",seatId);}audit(actor,"SEAT_TYPE","seat:"+seatId,type);return Map.of("message","工位类型已更新");}
  @PostMapping("/admin/seats/{seatId}/assign") @Transactional
  Map<String,String> assignSeat(@PathVariable long seatId,@RequestBody Map<String,Object> body,HttpSession session){long actor=uid(session);admin(session);long memberId=id(body,"memberId");if(!exists("SELECT id FROM users WHERE id=? AND approved=1",memberId))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"成员不存在或未审核");if(exists("SELECT id FROM seats WHERE occupant_id=?",memberId))throw new ResponseStatusException(HttpStatus.CONFLICT,"该成员已有固定工位");if(!exists("SELECT id FROM seats WHERE id=? AND status='AVAILABLE'",seatId))throw new ResponseStatusException(HttpStatus.CONFLICT,"工位当前不可分配");ensureNoFutureBookings(seatId);db.update("UPDATE seats SET type='FIXED',status='OCCUPIED',occupant_id=? WHERE id=?",memberId,seatId);db.update("INSERT INTO seat_assignments(seat_id,user_id) VALUES(?,?)",seatId,memberId);db.update("UPDATE users SET member_status='ACTIVE' WHERE id=?",memberId);notice(memberId,"固定工位已分配","管理员已为你分配固定工位。");audit(actor,"SEAT_ASSIGNED","seat:"+seatId,"member:"+memberId);return Map.of("message","固定工位已分配");}
  @PostMapping("/admin/seats/{seatId}/release") @Transactional
  Map<String,String> releaseSeat(@PathVariable long seatId,@RequestBody(required=false) Map<String,String> body,HttpSession session){long actor=uid(session);admin(session);var seat=one("SELECT * FROM seats WHERE id=?",seatId);if(seat.get("occupant_id")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"工位当前没有固定成员");long member=((Number)seat.get("occupant_id")).longValue();String reason=body==null?"管理员释放":input(body,"reason");db.update("UPDATE seats SET type='MOBILE',status='AVAILABLE',occupant_id=NULL,review_mode=0 WHERE id=?",seatId);db.update("UPDATE seat_assignments SET ended_at=CURRENT_TIMESTAMP,end_reason=? WHERE seat_id=? AND ended_at IS NULL",reason,seatId);db.update("UPDATE reviews SET status='CANCELLED',result='SEAT_RELEASED',decision_note='固定工位已释放，考察自动结束' WHERE member_id=? AND seat_id=? AND status IN ('PLANNED','ACTIVE','AWAITING_DECISION')",member,seatId);db.update("UPDATE weekly_report_tasks SET status='CANCELLED' WHERE review_id IN (SELECT id FROM reviews WHERE member_id=? AND seat_id=? AND status='CANCELLED') AND status IN ('OPEN','DRAFT')",member,seatId);db.update("UPDATE users SET member_status='MOBILE' WHERE id=?",member);notice(member,"固定工位已释放","管理员已将你转为流动成员。 ");audit(actor,"SEAT_RELEASED","seat:"+seatId,"member:"+member+":"+reason);return Map.of("message","工位已释放，成员已转为流动成员，关联考察已结束");}

  @GetMapping("/admin/applications") List<Map<String,Object>> applications(HttpSession session){admin(session);return db.queryForList("SELECT a.*,u.name,u.student_no,s.code FROM seat_applications a JOIN users u ON u.id=a.user_id JOIN seats s ON s.id=a.seat_id WHERE a.status='PENDING' ORDER BY a.id DESC");}
  @PostMapping("/admin/applications/{id}/{decision}") @Transactional
  Map<String,String> decideApplication(@PathVariable long id,@PathVariable String decision,@RequestBody(required=false) Map<String,String> body,HttpSession session){long actor=uid(session);admin(session);var app=one("SELECT * FROM seat_applications WHERE id=? AND status='PENDING'",id);boolean approve="approve".equals(decision);long member=((Number)app.get("user_id")).longValue(),seat=((Number)app.get("seat_id")).longValue();if(approve){ensureNoFutureBookings(seat);if(exists("SELECT id FROM seats WHERE occupant_id=?",member))throw new ResponseStatusException(HttpStatus.CONFLICT,"成员已有固定工位");if(db.update("UPDATE seats SET type='FIXED',status='OCCUPIED',occupant_id=? WHERE id=? AND type='MOBILE' AND status='PENDING'",member,seat)==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"工位状态已变化，请刷新后处理");db.update("INSERT INTO seat_assignments(seat_id,user_id) VALUES(?,?)",seat,member);db.update("UPDATE users SET member_status='ACTIVE' WHERE id=?",member);}else db.update("UPDATE seats SET type='MOBILE',status='AVAILABLE' WHERE id=? AND status='PENDING'",seat);db.update("UPDATE seat_applications SET status=?,reviewed_by=?,review_note=? WHERE id=?",approve?"APPROVED":"REJECTED",actor,body==null?"":input(body,"note"),id);notice(member,"固定工位申请结果",approve?"你的固定工位申请已通过。":"你的固定工位申请未通过。");audit(actor,approve?"SEAT_APPLICATION_APPROVED":"SEAT_APPLICATION_REJECTED","application:"+id,"");return Map.of("message","申请已处理");}
  @GetMapping("/admin/bookings") List<Map<String,Object>> bookings(HttpSession session){admin(session);return db.queryForList("SELECT b.*,u.name,u.student_no,s.code FROM seat_bookings b JOIN users u ON u.id=b.user_id JOIN seats s ON s.id=b.seat_id ORDER BY b.start_at DESC");}
  @PostMapping("/admin/bookings/{id}/{decision}") @Transactional
  Map<String,String> decideBooking(@PathVariable long id,@PathVariable String decision,HttpSession session){long actor=uid(session);admin(session);var booking=one("SELECT * FROM seat_bookings WHERE id=? AND status='PENDING'",id);boolean approve="approve".equals(decision);db.update("UPDATE seat_bookings SET status=? WHERE id=?",approve?"APPROVED":"REJECTED",id);long member=((Number)booking.get("user_id")).longValue();notice(member,"流动工位预约结果",approve?"你的流动工位预约已批准。":"你的流动工位预约未获批准。");audit(actor,approve?"BOOKING_APPROVED":"BOOKING_REJECTED","booking:"+id,"");return Map.of("message","预约已处理");}
  @PostMapping("/admin/bookings/{id}/cancel") @Transactional
  Map<String,String> adminCancelBooking(@PathVariable long id,@RequestBody(required=false) Map<String,String> body,HttpSession session){long actor=uid(session);admin(session);var booking=one("SELECT * FROM seat_bookings WHERE id=? AND status IN ('PENDING','APPROVED')",id);db.update("UPDATE seat_bookings SET status='CANCELLED' WHERE id=?",id);long member=((Number)booking.get("user_id")).longValue();String reason=body==null?"管理员取消":input(body,"reason");notice(member,"流动工位预约已取消",reason);audit(actor,"BOOKING_CANCELLED","booking:"+id,reason);return Map.of("message","预约已取消并通知成员");}

  @PostMapping("/admin/reviews") @Transactional
  Map<String,String> createReview(@RequestBody Map<String,Object> body,HttpSession session){long actor=uid(session);admin(session);long member=id(body,"memberId");var seat=one("SELECT * FROM seats WHERE occupant_id=?",member);LocalDateTime start=reviewDate(input(body,"startAt"),false),end=reviewDate(input(body,"endAt"),true);if(!end.isAfter(start))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"考察结束日期必须晚于开始日期");if(exists("SELECT id FROM reviews WHERE member_id=? AND status IN ('PLANNED','ACTIVE','AWAITING_DECISION')",member))throw new ResponseStatusException(HttpStatus.CONFLICT,"该成员已有未结束考察");String status=start.isAfter(LocalDateTime.now())?"PLANNED":"ACTIVE";db.update("INSERT INTO reviews(member_id,seat_id,reason,requirements,start_at,end_at,status,created_by) VALUES(?,?,?,?,?,?,?,?)",member,seat.get("id"),input(body,"reason"),input(body,"requirements"),start,end,status,actor);long reviewId=db.queryForObject("SELECT last_insert_rowid()",Long.class);LocalDate sunday=start.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));int index=1;while(!sunday.atTime(23,59).isAfter(end)){db.update("INSERT INTO weekly_report_tasks(review_id,member_id,period_index,due_at) VALUES(?,?,?,?)",reviewId,member,index++,sunday.atTime(23,59));sunday=sunday.plusWeeks(1);}if(index==1)db.update("INSERT INTO weekly_report_tasks(review_id,member_id,period_index,due_at) VALUES(?,?,?,?)",reviewId,member,1,end);db.update("UPDATE seats SET review_mode=1 WHERE id=?",seat.get("id"));db.update("UPDATE users SET member_status='REVIEW' WHERE id=?",member);notice(member,"固定工位考察已发起","你已进入固定工位考察期，请按时提交考察期间的结构化周报。 ");audit(actor,"REVIEW_CREATED","review:"+reviewId,input(body,"reason"));return Map.of("message","考察已发起，成员已转为考察成员并生成周报任务");}
  @GetMapping("/admin/reviews") List<Map<String,Object>> reviews(HttpSession session){admin(session);syncStates();return db.queryForList("SELECT r.*,u.name,u.student_no,s.code FROM reviews r JOIN users u ON u.id=r.member_id LEFT JOIN seats s ON s.id=r.seat_id ORDER BY r.id DESC");}
  @GetMapping("/admin/reviews/{id}/summary") Map<String,Object> reviewSummary(@PathVariable long id,HttpSession session){admin(session);syncStates();var review=one("SELECT r.*,u.name,u.student_no,u.member_status,s.code FROM reviews r JOIN users u ON u.id=r.member_id LEFT JOIN seats s ON s.id=r.seat_id WHERE r.id=?",id);var tasks=db.queryForList("SELECT t.*,w.sections_json,w.submitted_at,w.status report_status FROM weekly_report_tasks t LEFT JOIN weekly_reports w ON w.task_id=t.id WHERE t.review_id=? ORDER BY t.period_index",id);var circumstances=db.queryForList("SELECT * FROM special_circumstances WHERE user_id=? AND start_at<=? AND end_at>=?",review.get("member_id"),review.get("end_at"),review.get("start_at"));var notes=db.queryForList("SELECT n.*,u.name author_name FROM admin_notes n JOIN users u ON u.id=n.created_by WHERE n.review_id=? ORDER BY n.id DESC",id);return Map.of("review",review,"tasks",tasks,"circumstances",circumstances,"notes",notes);}
  @PostMapping("/admin/reviews/{id}/notes") Map<String,String> addNote(@PathVariable long id,@RequestBody Map<String,String> body,HttpSession session){long actor=uid(session);admin(session);var review=one("SELECT * FROM reviews WHERE id=?",id);db.update("INSERT INTO admin_notes(member_id,review_id,content,created_by) VALUES(?,?,?,?)",review.get("member_id"),id,input(body,"content"),actor);audit(actor,"REVIEW_NOTE","review:"+id,input(body,"content"));return Map.of("message","管理员记录已保存");}
  @PostMapping("/admin/reviews/{id}/decision") @Transactional
  Map<String,String> decision(@PathVariable long id,@RequestBody Map<String,String> body,HttpSession session){long actor=uid(session);admin(session);var review=one("SELECT * FROM reviews WHERE id=?",id);if(!"AWAITING_DECISION".equals(review.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"仅到期且待决策的考察可以处理");String result=input(body,"result");if(!Set.of("KEEP_SEAT","MOVE_TO_MOBILE","EXEMPTED").contains(result))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"决策结果不合法");long member=((Number)review.get("member_id")).longValue();if("MOVE_TO_MOBILE".equals(result)){Object seatId=review.get("seat_id");if(seatId!=null){db.update("UPDATE seats SET type='MOBILE',status='AVAILABLE',occupant_id=NULL,review_mode=0 WHERE id=?",seatId);db.update("UPDATE seat_assignments SET ended_at=CURRENT_TIMESTAMP,end_reason='考察决策转流动成员' WHERE seat_id=? AND ended_at IS NULL",seatId);}db.update("UPDATE users SET member_status='MOBILE' WHERE id=?",member);}else{if(review.get("seat_id")!=null)db.update("UPDATE seats SET review_mode=0 WHERE id=?",review.get("seat_id"));db.update("UPDATE users SET member_status='ACTIVE' WHERE id=?",member);}db.update("UPDATE reviews SET status='COMPLETED',result=?,decision_note=? WHERE id=?",result,input(body,"note"),id);notice(member,"考察结果已出","管理员已完成本次固定工位考察决策："+result);audit(actor,"REVIEW_DECISION","review:"+id,result+":"+input(body,"note"));return Map.of("message","考察决策已保存");}
  @PostMapping("/admin/reviews/{id}/extend") @Transactional
  Map<String,String> extendReview(@PathVariable long id,@RequestBody Map<String,String> body,HttpSession session){long actor=uid(session);admin(session);var old=one("SELECT * FROM reviews WHERE id=?",id);if(!"AWAITING_DECISION".equals(old.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"仅到期且待决策的考察可以延长");String end=input(body,"endAt");if(end.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请填写新的结束日期");db.update("UPDATE reviews SET status='COMPLETED',result='EXTEND_REVIEW',decision_note=? WHERE id=?",input(body,"note"),id);Map<String,Object> next=new HashMap<>();next.put("memberId",old.get("member_id"));next.put("startAt",LocalDate.now().toString());next.put("endAt",end);next.put("reason","延长考察："+old.get("reason"));next.put("requirements",old.get("requirements"));return createReview(next,session);}
  @GetMapping("/admin/audit-logs") List<Map<String,Object>> auditLogs(HttpSession session){admin(session);return db.queryForList("SELECT a.*,u.name actor_name FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_id ORDER BY a.id DESC LIMIT 200");}

  public record Register(@NotBlank @Size(min=4,max=32) String studentNo,@NotBlank @Size(max=50) String name,@NotBlank @Size(max=10) String gender,@NotBlank @Size(min=8,max=72) String password) {}
}
