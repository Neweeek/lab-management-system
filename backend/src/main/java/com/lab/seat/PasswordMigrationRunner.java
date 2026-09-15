package com.lab.seat;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

@Configuration
public class PasswordMigrationRunner {
  @Bean
  CommandLineRunner migratePlaintextPasswords(JdbcTemplate db, PasswordEncoder encoder) {
    return args -> {
      for (Map<String, Object> user : db.queryForList("SELECT id,password_hash FROM users")) {
        String hash = String.valueOf(user.get("password_hash"));
        if (!hash.startsWith("$2a$") && !hash.startsWith("$2b$") && !hash.startsWith("$2y$")) {
          db.update("UPDATE users SET password_hash=? WHERE id=?", encoder.encode(hash), user.get("id"));
        }
      }
    };
  }
}
