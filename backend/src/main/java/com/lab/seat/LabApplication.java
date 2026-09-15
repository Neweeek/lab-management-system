package com.lab.seat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@SpringBootApplication
public class LabApplication {
  public static void main(String[] args) { SpringApplication.run(LabApplication.class, args); }
  @Bean WebMvcConfigurer cors(@Value("${app.cors-origins}") String origins) { return new WebMvcConfigurer() { public void addCorsMappings(CorsRegistry r) { r.addMapping("/api/**").allowedOriginPatterns(Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isBlank()).toArray(String[]::new)).allowedMethods("GET","POST","OPTIONS").allowedHeaders("Content-Type","X-CSRF-Token").allowCredentials(true).maxAge(3600); } }; }
  @Bean org.springframework.boot.CommandLineRunner initializeData(JdbcTemplate db, PasswordEncoder encoder, @Value("${app.bootstrap.admin-student-no}") String adminStudentNo, @Value("${app.bootstrap.admin-name}") String adminName, @Value("${app.bootstrap.admin-password}") String adminPassword) { return args -> { for(int row=1;row<=4;row++) for(int col=1;col<=8;col++) db.update("INSERT OR IGNORE INTO seats(code,row_no,col_no,area,type,status) VALUES(?,?,?,?,?,?)", "S"+row+"-"+col,row,col,"主实验室","MOBILE","AVAILABLE"); db.update("UPDATE seats SET type='FIXED',status='OCCUPIED' WHERE type='DISABLED' AND occupant_id IS NOT NULL"); Integer admins=db.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN'",Integer.class); if(admins!=null&&admins==0){if(adminStudentNo.isBlank()||adminPassword.isBlank())throw new IllegalStateException("首次启动必须配置 APP_BOOTSTRAP_ADMIN_STUDENT_NO 与 APP_BOOTSTRAP_ADMIN_PASSWORD");db.update("INSERT INTO users(student_no,name,password_hash,role,member_status,approved) VALUES(?,?,?,?,?,1)",adminStudentNo,adminName,encoder.encode(adminPassword),"ADMIN","ACTIVE");} }; }
}
