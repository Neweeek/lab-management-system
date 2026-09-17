package com.lab.seat;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Spring 上下文的启动冒烟测试。
 *
 * <p>为什么需要它：其余测试都是 standaloneSetup 的单元级测试，不加载 Spring 上下文，
 * 因此抓不到"CommandLineRunner 执行顺序""Hikari connection-init-sql 是否生效"
 * "迁移脚本能否在打包后的 classpath 布局下枚举"这类集成缺陷。
 *
 * <p>开发过程中确实出现过两个只有真实启动才能发现的问题：
 * <ol>
 *   <li>{@code DataIntegrityRunner} 与 {@code initializeData} 的执行顺序未定义，
 *       前者在迁移建表之前运行，启动直接 {@code no such table: reviews} 失败；</li>
 *   <li>迁移加载器用 {@code new File(url.toURI())} 定位脚本目录，在 Spring Boot
 *       可执行 jar 里因 {@code URI is not hierarchical} 失败。</li>
 * </ol>
 * 本测试会在测试阶段就复现这两个场景（测试的 classpath 形态与打包不同，
 * 因此第 2 点仍需在部署前用真实 jar 验证一次）。
 */
@SpringBootTest(classes = LabApplication.class)
class ApplicationStartupTest {

  /**
   * 数据库文件放在 target 下而不使用 {@code @TempDir}：Spring 上下文持有的连接池
   * 会存活到 JVM 退出，Windows 上会锁住 {@code .db-wal}/{@code .db-shm}，
   * 使 {@code @TempDir} 清理失败并让测试报错。target 会被构建清理，无需手动删除。
   */
  private static final Path DATABASE = Path.of("target", "startup-smoke", "smoke.db");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    try {
      java.nio.file.Files.createDirectories(DATABASE.getParent());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("无法创建测试数据库目录", e);
    }
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE.toAbsolutePath());
    registry.add("app.bootstrap.admin-student-no", () -> "smoke-admin");
    registry.add("app.bootstrap.admin-password", () -> "Smoke-Test-Str0ng-Passw0rd");
    registry.add("app.bootstrap.admin-name", () -> "冒烟管理员");
    registry.add("app.cors-origins", () -> "http://localhost:5173");
    registry.add("server.port", () -> "0");
  }

  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate db;

  @Test void contextStartsAndAllMigrationsAreRecorded() {
    assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM schema_migrations", Integer.class),
        "启动时应应用 V1/V2/V3 三个迁移");
  }

  @Test void seatsAndBootstrapAdminAreSeeded() {
    assertEquals(32, db.queryForObject("SELECT COUNT(*) FROM seats", Integer.class));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND approved=1", Integer.class));
  }

  @Test void legacyReportsTableIsGoneAndGuardsExist() {
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='reports'", Integer.class));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_seat_app_pending_seat'", Integer.class));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_seat_app_pending_user'", Integer.class));
  }

  /**
   * connection-init-sql 里用分号串了两条 PRAGMA。若 sqlite-jdbc 只执行第一条，
   * 外键约束会静默失效 —— 而 schema.sql 里声明的 REFERENCES 就都形同注释。
   */
  @Test void connectionInitSqlEnablesForeignKeysAndBusyTimeout() throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      try (ResultSet rs = statement.executeQuery("PRAGMA foreign_keys")) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1), "应用连接必须启用 foreign_keys，否则声明式外键完全不生效");
      }
      try (ResultSet rs = statement.executeQuery("PRAGMA busy_timeout")) {
        assertTrue(rs.next());
        assertEquals(10000, rs.getInt(1), "应用连接必须设置 busy_timeout");
      }
    }
  }

  @Test void foreignKeyConstraintsAreActuallyEnforced() {
    assertThrows(org.springframework.dao.DataAccessException.class,
        () -> db.update("INSERT INTO project_members(project_id,user_id) VALUES(1,999999)"),
        "外键约束应当真正阻止孤儿团队成员行");
    assertThrows(org.springframework.dao.DataAccessException.class,
        () -> db.update("INSERT INTO project_applications(project_id,user_id,reason,skills,availability) VALUES(999999,999999,'x','y','z')"),
        "外键约束应当真正阻止引用不存在的项目");
  }

  @Test void bootstrapTimestampUsesLabLocalTime() {
    String created = db.queryForObject("SELECT created_at FROM users WHERE student_no='smoke-admin'", String.class);
    long driftSeconds = Math.abs(java.time.Duration.between(LabTime.storedTime(created), LabTime.now()).getSeconds());
    assertTrue(driftSeconds <= 120, "首个管理员的 created_at 应与实验室本地时钟一致，实际相差 " + driftSeconds + " 秒");
  }
}
