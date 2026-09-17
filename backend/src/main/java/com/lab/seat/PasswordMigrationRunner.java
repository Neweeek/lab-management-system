package com.lab.seat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * 一次性口令哈希迁移。
 *
 * <p>修复前它每次启动都遍历全部用户、对每个哈希跑一次 BCrypt，带来三个问题：
 * <ol>
 *   <li>O(用户数) 次 BCrypt（故意设计得很慢），用户上百后启动明显变慢；</li>
 *   <li>会把任何"不是 BCrypt"的哈希二次哈希，永久锁死那些账号；</li>
 *   <li>没有版本标记，无法判断是否已经迁移过。</li>
 * </ol>
 *
 * <p>现在改为：
 * <ul>
 *   <li>先用一条 SQL 做 canary 判断——只有当表中确实存在非 BCrypt 哈希时才进入慢路径。
 *       新库（全部是 BCrypt）在启动时只付一次索引扫描，不做任何 BCrypt 运算。</li>
 *   <li>用 {@code app_flags} 台账记录完成状态，迁移完成后不再重复扫描。</li>
 *   <li>遇到无法重新编码的畸形哈希时跳过并告警，而不是让启动失败。</li>
 * </ul>
 */
@Configuration
public class PasswordMigrationRunner {
  private static final Logger log = LoggerFactory.getLogger(PasswordMigrationRunner.class);
  private static final String FLAG = "password_hash_migrated";

  /**
   * 见 {@code LabApplication} 中关于 CommandLineRunner 执行顺序的说明：
   * 必须在数据库迁移与派生状态修复之后运行。
   */
  @Bean
  @org.springframework.core.annotation.Order(3)
  CommandLineRunner migratePlaintextPasswords(JdbcTemplate db, PasswordEncoder encoder) {
    return args -> {
      // SQLite 要求 DEFAULT 里的函数表达式必须带括号（与 V1 迁移里的写法一致）。
      db.execute("CREATE TABLE IF NOT EXISTS app_flags (name TEXT PRIMARY KEY, note TEXT NOT NULL DEFAULT '', updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours')))");
      if (isFlagged(db)) return;

      Integer legacy = db.queryForObject(
          "SELECT COUNT(*) FROM users WHERE password_hash NOT LIKE '$2a$%' AND password_hash NOT LIKE '$2b$%' AND password_hash NOT LIKE '$2y$%'",
          Integer.class);
      if (legacy == null || legacy == 0) {
        markDone(db, "无历史明文口令，无需迁移");
        return;
      }

      log.warn("检测到 {} 个非 BCrypt 口令哈希，正在执行一次性迁移", legacy);
      int migrated = 0;
      for (Map<String, Object> user : db.queryForList("SELECT id,password_hash FROM users")) {
        String hash = String.valueOf(user.get("password_hash"));
        if (isBcrypt(hash)) continue;
        try {
          db.update("UPDATE users SET password_hash=? WHERE id=?", encoder.encode(hash), user.get("id"));
          migrated++;
        } catch (RuntimeException e) {
          // 单个畸形哈希不应阻止应用启动；该账号需要管理员重置密码。
          log.error("用户 id={} 的口令哈希无法迁移，已跳过（需管理员重置密码）", user.get("id"), e);
        }
      }
      markDone(db, "迁移 " + migrated + " 个用户");
      log.info("口令哈希一次性迁移完成，共处理 {} 个用户", migrated);
    };
  }

  private static boolean isBcrypt(String hash) {
    return hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$");
  }

  private static boolean isFlagged(JdbcTemplate db) {
    return db.queryForObject("SELECT COUNT(*) FROM app_flags WHERE name=?", Integer.class, FLAG) > 0;
  }

  private static void markDone(JdbcTemplate db, String note) {
    db.update("INSERT OR REPLACE INTO app_flags(name,note,updated_at) VALUES(?,?,(strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours')))", FLAG, note);
  }
}
