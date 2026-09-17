package com.lab.seat;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
  private final JdbcTemplate db;
  ProjectController(JdbcTemplate db) { this.db=db; }
  private ResponseStatusException fail(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
  private long uid(HttpSession s) {
    if (!(s.getAttribute("uid") instanceof Number n)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
    return n.longValue();
  }
  private boolean admin(HttpSession s) { return "ADMIN".equals(s.getAttribute("role")); }
  private long requireAdmin(HttpSession s) { long id=uid(s); if(!admin(s)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要管理员权限"); return id; }
  private boolean manages(HttpSession s,Map<String,Object> p) { return admin(s) || ("COMPETITION".equals(p.get("type")) && ((Number)p.get("created_by")).longValue()==uid(s)); }
  private long requireManager(HttpSession s,Map<String,Object> p) { long actor=uid(s); if(!manages(s,p)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只能管理自己发布的竞赛"); if(!admin(s)) eligible(actor); return actor; }
  private Map<String,Object> project(long id) {
    var rows=db.queryForList("SELECT * FROM projects WHERE id=?",id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"项目不存在");
    return rows.get(0);
  }
  // Acquire SQLite's writer lock before membership checks, so simultaneous approvals cannot exceed capacity.
  private Map<String,Object> lock(long id) { db.update("UPDATE projects SET capacity=capacity WHERE id=?",id); return project(id); }
  private void eligible(long user) {
    if(db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND role='MEMBER' AND approved=1 AND member_status IN ('MOBILE','ACTIVE','REVIEW')",Integer.class,user)==0)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅已确认的实验室成员可加入项目");
  }
  private void recruiting(Map<String,Object> p) {
    if(!"RECRUITING".equals(p.get("status")) || LocalDate.parse(p.get("deadline").toString()).isBefore(LabTime.today())) throw fail("项目已关闭招募或申请已截止");
  }
  private void event(long actor,long id,String action,String detail) { db.update("INSERT INTO audit_logs(actor_id,action,target,detail) VALUES(?,?,?,?)",actor,action,"project:"+id,detail); }
  private void notice(long user,String text) { db.update("INSERT INTO notifications(user_id,title,content) VALUES(?,'项目团队通知',?)",user,text); }
  private void add(long id,long member,Map<String,Object> p) {
    eligible(member);
    if(db.queryForObject("SELECT COUNT(*) FROM project_members WHERE project_id=? AND user_id=?",Integer.class,id,member)>0) throw fail("该成员已在团队中");
    if(db.queryForObject("SELECT COUNT(*) FROM project_members WHERE project_id=?",Integer.class,id)>=((Number)p.get("capacity")).intValue()) throw fail("团队人数已满");
    db.update("INSERT INTO project_members(project_id,user_id) VALUES(?,?)",id,member);
  }
  public record Draft(@NotBlank @Size(max=100) String name,@NotBlank String type,@NotBlank @Size(max=6000) String description,@NotBlank @Size(max=3000) String requirements,@Min(1) @Max(200) int capacity,@NotNull LocalDate deadline) {}
  public record Application(@NotBlank @Size(max=2000) String reason,@NotBlank @Size(max=2000) String skills,@NotBlank @Size(max=1000) String availability) {}
  public record Decision(@NotBlank String decision,@Size(max=2000) String note) {}
  public record Member(@Positive long memberId) {}

  @GetMapping
  Map<String,Object> list(HttpSession s,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="newest") String sort) {
    long user=uid(s);
    if(!Set.of("newest","oldest").contains(sort)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"排序方式不合法");
    if(page<1 || page>100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"页码不合法");
    String order="oldest".equals(sort)?"p.created_at ASC,p.id ASC":"p.created_at DESC,p.id DESC";
    String where=" WHERE p.name LIKE ? AND (?='' OR p.status=?)";
    String query=q.length()>100?q.substring(0,100):q;
    int total=db.queryForObject("SELECT COUNT(*) FROM projects p"+where,Integer.class,"%"+query+"%",status,status);
    // 服务端把请求页码夹到有效范围内，并把最终页码回传，
    // 否则前端下一页按钮会越过末页却仍显示递增的页码。
    int actualPage=Math.min(page,Math.max(1,(total+11)/12));
    int offset=(actualPage-1)*12;
    var items=db.queryForList("SELECT p.*,(SELECT name FROM users WHERE id=p.created_by) creator_name,(SELECT COUNT(*) FROM project_members m WHERE m.project_id=p.id) member_count,EXISTS(SELECT 1 FROM project_members m WHERE m.project_id=p.id AND m.user_id=?) joined FROM projects p"+where+" ORDER BY "+order+" LIMIT 12 OFFSET ?",user,"%"+query+"%",status,status,offset);
    return Map.of("items",items,"total",total,"page",actualPage);
  }
  @GetMapping("/mine")
  List<Map<String,Object>> mine(HttpSession s) { return db.queryForList("SELECT p.id,p.name,p.type,p.status FROM projects p JOIN project_members m ON m.project_id=p.id WHERE m.user_id=? ORDER BY p.id DESC",uid(s)); }
  @GetMapping("/{id}")
  Map<String,Object> detail(@PathVariable long id,HttpSession s) {
    long user=uid(s); var p=project(id);
    var apps=manages(s,p)?db.queryForList("SELECT a.*,u.name FROM project_applications a JOIN users u ON u.id=a.user_id WHERE a.project_id=? ORDER BY a.id DESC",id):db.queryForList("SELECT * FROM project_applications WHERE project_id=? AND user_id=? ORDER BY id DESC",id,user);
    p.put("creator_name",db.queryForObject("SELECT name FROM users WHERE id=?",String.class,p.get("created_by")));
    var team=db.queryForList("SELECT u.id,u.name,m.joined_at FROM project_members m JOIN users u ON u.id=m.user_id WHERE m.project_id=? ORDER BY m.joined_at,u.id",id);
    return Map.of("project",p,"applications",apps,"team",team,"can_manage",manages(s,p));
  }
  @PostMapping @Transactional
  Map<String,Object> create(@Valid @RequestBody Draft d,HttpSession s) {
    long actor=uid(s); if(!admin(s)) { eligible(actor); if(!"COMPETITION".equals(d.type())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"普通成员只能发布竞赛招募"); } validate(d);
    db.update("INSERT INTO projects(name,type,description,requirements,capacity,deadline,created_by) VALUES(?,?,?,?,?,?,?)",d.name().trim(),d.type(),d.description(),d.requirements(),d.capacity(),d.deadline().toString(),actor);
    long id=db.queryForObject("SELECT last_insert_rowid()",Long.class);
    if(!admin(s)) db.update("INSERT INTO project_members(project_id,user_id) VALUES(?,?)",id,actor);
    event(actor,id,"PROJECT_CREATED",d.name()); return Map.of("id",id,"message",admin(s)?"项目已发布":"竞赛招募已发布，你已加入团队（计入人数上限）");
  }
  private void validate(Draft d) {
    if(!Set.of("COMPETITION","HORIZONTAL","VERTICAL","OTHER").contains(d.type())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"项目类型不合法");
    if(d.deadline().isBefore(LabTime.today())) throw fail("申请截止日期不能早于今天");
  }
  @PostMapping("/{id}/edit") @Transactional
  Map<String,String> edit(@PathVariable long id,@Valid @RequestBody Draft d,HttpSession s) {
    var p=lock(id); long actor=requireManager(s,p); validate(d);
    if(!admin(s) && !"COMPETITION".equals(d.type())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"普通成员不能修改为其他课题类型");
    if("COMPLETED".equals(p.get("status"))) throw fail("已结束项目不可编辑");
    if(d.capacity()<db.queryForObject("SELECT COUNT(*) FROM project_members WHERE project_id=?",Integer.class,id)) throw fail("人数上限不能小于现有团队人数");
    db.update("UPDATE projects SET name=?,type=?,description=?,requirements=?,capacity=?,deadline=? WHERE id=?",d.name().trim(),d.type(),d.description(),d.requirements(),d.capacity(),d.deadline().toString(),id);
    event(actor,id,"PROJECT_UPDATED",d.name()); return Map.of("message","项目已更新");
  }
  @PostMapping("/{id}/apply") @Transactional
  Map<String,String> apply(@PathVariable long id,@Valid @RequestBody Application a,HttpSession s) {
    long user=uid(s); var p=lock(id); eligible(user); recruiting(p);
    if(db.queryForObject("SELECT COUNT(*) FROM project_members WHERE project_id=? AND user_id=?",Integer.class,id,user)>0) throw fail("你已加入团队");
    if(db.queryForObject("SELECT COUNT(*) FROM project_members WHERE project_id=?",Integer.class,id)>=((Number)p.get("capacity")).intValue()) throw fail("团队人数已满");
    if(db.queryForObject("SELECT COUNT(*) FROM project_applications WHERE project_id=? AND user_id=? AND status='PENDING'",Integer.class,id,user)>0) throw fail("你已有待审核申请");
    db.update("INSERT INTO project_applications(project_id,user_id,reason,skills,availability) VALUES(?,?,?,?,?)",id,user,a.reason(),a.skills(),a.availability());
    event(user,id,"PROJECT_APPLIED","提交加入申请"); return Map.of("message","申请已提交，等待管理员审核");
  }
  @PostMapping("/{id}/applications/{applicationId}/withdraw") @Transactional
  Map<String,String> withdraw(@PathVariable long id,@PathVariable long applicationId,HttpSession s) {
    long user=uid(s); lock(id);
    if(db.update("UPDATE project_applications SET status='WITHDRAWN' WHERE id=? AND project_id=? AND user_id=? AND status='PENDING'",applicationId,id,user)==0) throw fail("申请不存在或已处理");
    event(user,id,"PROJECT_WITHDRAWN","撤回申请"); return Map.of("message","申请已撤回");
  }
  @PostMapping("/{id}/applications/{applicationId}/decision") @Transactional
  Map<String,String> decide(@PathVariable long id,@PathVariable long applicationId,@Valid @RequestBody Decision d,HttpSession s) {
    var p=lock(id); long actor=requireManager(s,p);
    if(!Set.of("approve","reject").contains(d.decision())) throw fail("审核操作不合法");
    var rows=db.queryForList("SELECT * FROM project_applications WHERE id=? AND project_id=? AND status='PENDING'",applicationId,id);
    if(rows.isEmpty()) throw fail("申请已处理"); long member=((Number)rows.get(0).get("user_id")).longValue();
    boolean approved=d.decision().equals("approve");
    if(approved) { if("COMPLETED".equals(p.get("status"))) throw fail("项目已结束"); add(id,member,p); }
    db.update("UPDATE project_applications SET status=?,review_note=?,reviewed_by=? WHERE id=?",approved?"APPROVED":"REJECTED",d.note()==null?"":d.note(),actor,applicationId);
    notice(member,p.get("name")+"：你的加入申请"+(approved?"已通过":"未通过")+"。"+(d.note()==null?"":d.note()));
    event(actor,id,"PROJECT_APPLICATION_DECIDED",applicationId+":"+d.decision()); return Map.of("message","审核已完成");
  }
  @PostMapping("/{id}/members") @Transactional
  Map<String,String> directAdd(@PathVariable long id,@Valid @RequestBody Member m,HttpSession s) {
    long actor=requireAdmin(s); var p=lock(id); if("COMPLETED".equals(p.get("status"))) throw fail("项目已结束");
    add(id,m.memberId(),p);
    db.update("UPDATE project_applications SET status='APPROVED',review_note='管理员直接添加',reviewed_by=? WHERE project_id=? AND user_id=? AND status='PENDING'",actor,id,m.memberId());
    notice(m.memberId(),"管理员已将你加入项目："+p.get("name")); event(actor,id,"PROJECT_MEMBER_ADDED","member:"+m.memberId()); return Map.of("message","成员已添加");
  }
  @PostMapping("/{id}/members/{memberId}/remove") @Transactional
  Map<String,String> remove(@PathVariable long id,@PathVariable long memberId,HttpSession s) {
    var p=lock(id); long actor=requireManager(s,p); if("COMPLETED".equals(p.get("status"))) throw fail("已结束项目保留团队记录");
    if(memberId==((Number)p.get("created_by")).longValue()) throw fail("不能移出项目发布者，请先结束项目");
    if(db.update("DELETE FROM project_members WHERE project_id=? AND user_id=?",id,memberId)==0) throw fail("成员不在团队中");
    notice(memberId,"你已被移出项目："+p.get("name")); event(actor,id,"PROJECT_MEMBER_REMOVED","member:"+memberId); return Map.of("message","已移出团队");
  }
  @PostMapping("/{id}/status") @Transactional
  Map<String,String> status(@PathVariable long id,@RequestBody Map<String,String> body,HttpSession s) {
    var p=lock(id); long actor=requireManager(s,p); String next=body.getOrDefault("status","");
    if("COMPLETED".equals(p.get("status"))||!Set.of("RECRUITING","CLOSED","COMPLETED").contains(next)) throw fail("项目状态不能这样变更");
    if("RECRUITING".equals(next)&&LocalDate.parse(p.get("deadline").toString()).isBefore(LabTime.today())) throw fail("请先更新招募截止日期");
    db.update("UPDATE projects SET status=? WHERE id=?",next,id);
    if("COMPLETED".equals(next)) {
      for(var a:db.queryForList("SELECT user_id FROM project_applications WHERE project_id=? AND status='PENDING'",id)) notice(((Number)a.get("user_id")).longValue(),p.get("name")+"已结束，你的待审核申请已关闭。");
      db.update("UPDATE project_applications SET status='REJECTED',review_note='项目已结束',reviewed_by=? WHERE project_id=? AND status='PENDING'",actor,id);
      for(var m:db.queryForList("SELECT user_id FROM project_members WHERE project_id=?",id)) notice(((Number)m.get("user_id")).longValue(),"项目已结束："+p.get("name"));
    }
    event(actor,id,"PROJECT_STATUS",next); return Map.of("message","项目状态已更新");
  }
}
