package com.lab.seat;

import com.lab.seat.migration.MigrationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.util.Arrays;

@SpringBootApplication
public class LabApplication {
  public static void main(String[] args) { SpringApplication.run(LabApplication.class, args); }
  @Bean WebMvcConfigurer cors(@Value("${app.cors-origins}") String origins) { return new WebMvcConfigurer() { public void addCorsMappings(CorsRegistry r) { r.addMapping("/api/**").allowedOriginPatterns(Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isBlank()).toArray(String[]::new)).allowedMethods("GET","POST","OPTIONS").allowedHeaders("Content-Type","X-CSRF-Token").allowCredentials(true).maxAge(3600); } }; }

  /**
   * 启动顺序由 {@code @Order} 显式固定，不能依赖 bean 声明顺序：
   * 1. {@link #initializeData}（order 1）先跑数据库迁移，再播种工位与首个管理员；
   * 2. {@link DataIntegrityRunner}（order 2）修复派生状态；
   * 3. {@link PasswordMigrationRunner}（order 3）做一次性口令迁移。
   *
   * <p>曾经没有 {@code @Order}，Spring 按 bean 名称排序执行 CommandLineRunner，
   * 结果 DataIntegrityRunner 在迁移建表之前就执行，启动直接
   * {@code no such table: reviews} 失败。这类顺序缺陷单元测试抓不到，
   * 必须靠真实启动验证。
   */
  @Bean
  @org.springframework.core.annotation.Order(1)
  org.springframework.boot.CommandLineRunner initializeData(DataSource dataSource, JdbcTemplate db, PasswordEncoder encoder, @Value("${app.bootstrap.admin-student-no}") String adminStudentNo, @Value("${app.bootstrap.admin-name}") String adminName, @Value("${app.bootstrap.admin-password}") String adminPassword) { return args -> {
    new MigrationRunner(dataSource).migrate();
    for(int row=1;row<=4;row++) for(int col=1;col<=8;col++) db.update("INSERT OR IGNORE INTO seats(code,row_no,col_no,area,type,status) VALUES(?,?,?,?,?,?)", "S"+row+"-"+col,row,col,"主实验室","MOBILE","AVAILABLE");
    db.update("UPDATE seats SET type='FIXED',status='OCCUPIED' WHERE type='DISABLED' AND occupant_id IS NOT NULL");
    Integer admins=db.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN'",Integer.class);
    if(admins!=null&&admins==0){
      if(adminStudentNo.isBlank()||adminPassword.isBlank())throw new IllegalStateException("首次启动必须配置 APP_BOOTSTRAP_ADMIN_STUDENT_NO 与 APP_BOOTSTRAP_ADMIN_PASSWORD");
      requireNonPlaceholderPassword(adminPassword);
      db.update("INSERT INTO users(student_no,name,password_hash,role,member_status,approved) VALUES(?,?,?,?,?,1)",adminStudentNo,adminName,encoder.encode(adminPassword),"ADMIN","ACTIVE");
    }
  }; }

  /**
   * 拒绝使用仓库里公开的示例占位密码创建管理员。
   * `.env.example` 的默认值一旦被原样保留，公网部署就会有一个密码写在公开仓库里的管理员账号。
   *
   * <p>注意：本校验只在"库中还没有管理员、即将创建首个管理员"时执行。
   * 已有管理员的库不会触发，因此不会把在跑的部署锁在门外。
   */
  private static void requireNonPlaceholderPassword(String password) {
    var placeholders = java.util.Set.of(
        "change-this-to-a-long-unique-password",
        "请替换为长随机密码",
        "replace-with-a-long-random-password",
        "changeme-please-change-me");
    String normalized = password.trim().toLowerCase(java.util.Locale.ROOT);
    if (placeholders.contains(normalized)) throw new IllegalStateException("APP_BOOTSTRAP_ADMIN_PASSWORD 仍是示例占位值，请改为唯一的长随机密码后再启动");
    if (password.trim().length() < 12) throw new IllegalStateException("APP_BOOTSTRAP_ADMIN_PASSWORD 至少需要 12 位，请使用唯一的长随机密码");
  }
}
