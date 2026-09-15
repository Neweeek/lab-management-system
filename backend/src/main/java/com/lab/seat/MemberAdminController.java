package com.lab.seat;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class MemberAdminController {
  private final JdbcTemplate db;
  MemberAdminController(JdbcTemplate db){this.db=db;}
  private long admin(HttpSession s){if(!(s.getAttribute("uid") instanceof Number n))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录"); if(!"ADMIN".equals(s.getAttribute("role")) || db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND role='ADMIN' AND approved=1",Integer.class,n.longValue())!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要管理员权限");return n.longValue();}
  private ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
  private Map<String,Object> member(long id){var rows=db.queryForList("SELECT id,student_no,name,role,approved FROM users WHERE id=?",id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"账号不存在");return rows.get(0);}
  private int count(String sql,long id){return db.queryForObject(sql,Integer.class,id);}
  private void audit(long actor,String action,long id,String detail){db.update("INSERT INTO audit_logs(actor_id,action,target,detail) VALUES(?,?,?,?)",actor,action,"member:"+id,detail);}
  public record Batch(@NotEmpty @Size(max=500) List<@NotNull @Positive Long> ids){}
  public record Removal(@NotBlank String confirmStudentNo,@NotBlank @Size(max=500) String reason){}
  public record Cancellation(@NotBlank @Size(max=1000) String reason){}
  @PostMapping("/reviews/{id}/cancel") @Transactional
  Map<String,String> cancelReview(@PathVariable long id,@Valid @RequestBody Cancellation body,HttpSession s){
    long actor=admin(s);db.update("UPDATE reviews SET status=status WHERE id=?",id);
    var rows=db.queryForList("SELECT * FROM reviews WHERE id=?",id);
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"考察不存在");
    var review=rows.get(0);if(!Set.of("PLANNED","ACTIVE","AWAITING_DECISION").contains(review.get("status")))throw conflict("考察已结束或已取消，不能重复取消");
    long member=((Number)review.get("member_id")).longValue();
    db.update("UPDATE reviews SET status='CANCELLED',result='ADMIN_CANCELLED',decision_note=? WHERE id=?",body.reason().trim(),id);
    db.update("UPDATE weekly_report_tasks SET status='CANCELLED' WHERE review_id=? AND status IN ('OPEN','DRAFT','MISSED')",id);
    db.update("UPDATE seats SET review_mode=0 WHERE occupant_id=?",member);
    db.update("UPDATE users SET member_status=CASE WHEN EXISTS(SELECT 1 FROM seats WHERE occupant_id=users.id) THEN 'ACTIVE' ELSE 'MOBILE' END WHERE id=? AND approved=1",member);
    db.update("INSERT INTO notifications(user_id,title,content) VALUES(?,'考察已取消',?)",member,"管理员已取消本次考察，已有材料保留。原因："+body.reason().trim());
    db.update("INSERT INTO audit_logs(actor_id,action,target,detail) VALUES(?,'REVIEW_CANCELLED',?,?)",actor,"review:"+id,body.reason().trim());
    return Map.of("message","考察已取消，固定工位和历史材料保留，成员已收到通知");
  }
  @PostMapping("/registrations/batch-approve") @Transactional
  Map<String,Object> batch(@Valid @RequestBody Batch body,HttpSession s){
    long actor=admin(s);int approved=0,skipped=0;
    for(long id:new LinkedHashSet<>(body.ids())){
      if(db.update("UPDATE users SET approved=1,member_status='MOBILE' WHERE id=? AND role='MEMBER' AND approved=0",id)==0){skipped++;continue;}
      approved++;db.update("INSERT INTO notifications(user_id,title,content) VALUES(?,'注册审核通过','你的账号已审核通过，可登录并使用实验室服务。')",id);audit(actor,"REGISTER_APPROVED",id,"批量审核：设为流动成员");
    }
    return Map.of("approved",approved,"skipped",skipped,"message","已通过 "+approved+" 人；跳过已处理或不存在的申请 "+skipped+" 人");
  }
  @GetMapping("/members/{id}/delete-preview")
  Map<String,Object> preview(@PathVariable long id,HttpSession s){admin(s);var user=member(id);Map<String,Object> result=new LinkedHashMap<>();result.put("user",user);
    result.put("seats",count("SELECT COUNT(*) FROM seats WHERE occupant_id=?",id));
    result.put("bookings",db.queryForObject("SELECT COUNT(*) FROM seat_bookings WHERE user_id=? AND status IN ('PENDING','APPROVED') AND datetime(end_at)>datetime(?)",Integer.class,id,LocalDateTime.now().toString()));
    result.put("teams",count("SELECT COUNT(*) FROM project_members m JOIN projects p ON p.id=m.project_id WHERE m.user_id=? AND p.status<>'COMPLETED'",id));
    result.put("ownedProjects",count("SELECT COUNT(*) FROM projects WHERE created_by=? AND status<>'COMPLETED'",id));
    result.put("reports",count("SELECT COUNT(*) FROM weekly_reports WHERE user_id=?",id));return result;}
  @PostMapping("/members/{id}/delete") @Transactional
  Map<String,String> delete(@PathVariable long id,@Valid @RequestBody Removal body,HttpSession s){
    long actor=admin(s);
    // Writer lock before validation protects concurrent bookings, joins and approvals.
    db.update("UPDATE users SET approved=approved WHERE id=?",id);
    var user=member(id);
    if(id==actor || "ADMIN".equals(user.get("role"))) throw conflict("不能删除自己或管理员账号");
    if(((Number)user.get("approved")).intValue()==-2)throw conflict("账号已经删除");
    if(!user.get("student_no").equals(body.confirmStudentNo()))throw conflict("确认学号不匹配");
    if(count("SELECT COUNT(*) FROM projects WHERE created_by=? AND status<>'COMPLETED'",id)>0)throw conflict("该成员仍负责未结束项目，请先结束相关项目再删除账号");
    String now=LocalDateTime.now().toString();
    db.update("UPDATE users SET approved=-2,member_status='DELETED' WHERE id=?",id);
    db.update("UPDATE seats SET type='MOBILE',status='AVAILABLE',occupant_id=NULL,review_mode=0 WHERE occupant_id=?",id);
    db.update("UPDATE seat_assignments SET ended_at=?,end_reason='账号删除' WHERE user_id=? AND ended_at IS NULL",now,id);
    db.update("UPDATE seat_applications SET status='CANCELLED',review_note='账号删除',reviewed_by=? WHERE user_id=? AND status='PENDING'",actor,id);
    db.update("UPDATE seats SET type='MOBILE',status='AVAILABLE' WHERE occupant_id IS NULL AND status='PENDING' AND id IN (SELECT seat_id FROM seat_applications WHERE user_id=?) AND NOT EXISTS (SELECT 1 FROM seat_applications a WHERE a.seat_id=seats.id AND a.status='PENDING')",id);
    db.update("UPDATE seat_bookings SET status='CANCELLED' WHERE user_id=? AND status IN ('PENDING','APPROVED') AND datetime(end_at)>datetime(?)",id,now);
    db.update("UPDATE weekly_report_tasks SET status='CANCELLED' WHERE member_id=? AND status IN ('OPEN','DRAFT','MISSED') AND review_id IN (SELECT id FROM reviews WHERE status IN ('PLANNED','ACTIVE','AWAITING_DECISION'))",id);
    db.update("UPDATE reviews SET status='CANCELLED',result='ACCOUNT_DELETED',decision_note='账号删除，考察终止' WHERE member_id=? AND status IN ('PLANNED','ACTIVE','AWAITING_DECISION')",id);
    db.update("UPDATE project_applications SET status='WITHDRAWN',review_note='账号删除' WHERE user_id=? AND status='PENDING'",id);
    db.update("DELETE FROM project_members WHERE user_id=? AND project_id IN (SELECT id FROM projects WHERE status<>'COMPLETED')",id);
    audit(actor,"ACCOUNT_DELETED",id,body.reason());
    return Map.of("message","账号已删除并禁止登录；工位和有效预约已释放，历史周报及审计记录保留");
  }
}
