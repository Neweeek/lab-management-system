package com.lab.seat;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQLite 并发写入的辅助工具。
 *
 * <h2>为什么需要显式抢写锁</h2>
 * SQLite 在 WAL 模式下允许多个读者与一个写者并存。事务的默认行为是
 * <b>延迟（deferred）</b>：先执行 SELECT 时只拿到读锁，直到第一次写入才尝试升级为写锁。
 * 如果两个事务都先读过、再同时写，就会出现"锁升级死锁" —— SQLite 为了避免死锁会
 * <b>立即</b>返回 {@code SQLITE_BUSY}，此时 {@code busy_timeout} 完全不起作用
 * （busy_timeout 只在"等待别人释放锁"时生效，对升级冲突无效）。
 *
 * <p>本项目的业务校验（"该工位是否空闲""是否已有待审核申请"）都是先读后写。因此所有
 * 会修改工位/席位状态的写事务，都应当在**任何读操作之前**先用一次无害的写语句拿到写锁：
 *
 * <pre>{@code
 * SqliteLocks.acquire(db, "seats", seatId);   // 第一句就抢写锁
 * ... 之后再做各种 SELECT 校验 ...
 * }</pre>
 *
 * <p>由于 SQLite 全库只有一个写者，这样把"检测—行动"变成互斥的：后来的事务会在
 * {@code busy_timeout} 内正常排队，而不是抛 SQLITE_BUSY，也不会因为读到过期快照而重复占用。
 */
public final class SqliteLocks {
  private SqliteLocks() {}

  /**
   * 对指定行执行一次无副作用的 UPDATE，从而在事务内提前取得 SQLite 写锁。
   *
   * @param table 表名，必须是代码内的字面量（不接受外部输入，避免 SQL 注入）
   * @param id    目标行的主键
   */
  public static void acquire(JdbcTemplate db, String table, long id) {
    if (!SAFE_TABLES.contains(table)) throw new IllegalArgumentException("不允许加锁的表: " + table);
    db.update("UPDATE " + table + " SET id=id WHERE id=?", id);
  }

  /** 加锁语句只允许出现在这些表上；白名单避免把表名拼进 SQL 时引入注入面。 */
  private static final java.util.Set<String> SAFE_TABLES =
      java.util.Set.of("seats", "projects", "users", "seat_bookings", "reviews");

  /**
   * 判断异常是否为"唯一约束冲突"。
   *
   * <p>sqlite-jdbc 对部分唯一索引（{@code CREATE UNIQUE INDEX ... WHERE ...}）的冲突
   * 不会映射成 Spring 的 {@code DuplicateKeyException}，而是抛
   * {@code UncategorizedSQLException}，因此必须按消息判断。
   */
  public static boolean isUniqueViolation(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      String message = current.getMessage();
      if (message != null && (message.contains("UNIQUE constraint failed") || message.contains("SQLITE_CONSTRAINT"))) {
        return true;
      }
    }
    return false;
  }
}
