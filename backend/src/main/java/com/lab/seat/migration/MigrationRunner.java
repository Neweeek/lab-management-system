package com.lab.seat.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 轻量级数据库迁移执行器。
 *
 * <p>为什么不用 Flyway：本项目只支持 SQLite，而 Flyway 10+ 起把 SQLite 支持拆成了
 * 独立的 {@code flyway-database-sqlite} 模块，本项目未能验证该模块在目标环境中可用。
 * 与其引入一个未经部署验证的依赖，这里实现一个约 100 行、行为完全可控的迁移器。
 *
 * <p>行为约定：
 * <ul>
 *   <li>{@code schema_migrations} 是台账表，记录已应用版本、描述、校验和与应用时间。</li>
 *   <li>每个脚本在**单个事务**内执行：SQLite 支持事务性 DDL，失败时整脚本回滚，
 *       不会留下半应用的中间状态。</li>
 *   <li>已应用脚本若校验和变化，启动时直接失败并给出明确指引，避免结构悄悄漂移。</li>
 *   <li>{@code CREATE TABLE IF NOT EXISTS} 与 {@code CREATE INDEX IF NOT EXISTS} 本身幂等；
 *       {@code ALTER TABLE ADD COLUMN} 在 SQLite 没有 IF NOT EXISTS，因此对
 *       "duplicate column name" 错误做容忍处理（见 {@link #isAlreadyAppliedError}）。</li>
 * </ul>
 */
public final class MigrationRunner {
  private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

  private static final String CREATE_LEDGER = """
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version TEXT PRIMARY KEY,
        description TEXT NOT NULL,
        checksum TEXT NOT NULL,
        applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
      )""";

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final boolean allowChangedScripts;

  public MigrationRunner(DataSource dataSource) {
    this(dataSource, false);
  }

  /**
   * @param allowChangedScripts 开发用逃生阀：允许"已应用的迁移脚本被改动"时继续启动，
   *        并按新校验和更新台账。
   *
   *        <p>为什么需要它：校验和保护的目的是防止生产环境里脚本与库结构悄悄漂移，
   *        这是正确的默认值。但如果**开发阶段**改了迁移脚本，环境会永久卡在
   *        "校验和不一致"上启动失败，而唯一的补救方式（手工改台账）既不安全也不可发现。
   *        因此提供一个显式开关，把"跳过保护"变成一次有意识的、可审计的操作，
   *        而不是逼着人去手工篡改数据库。
   *
   *        <p>生产环境必须保持关闭（默认关闭）。
   */
  public MigrationRunner(DataSource dataSource, boolean allowChangedScripts) {
    if (dataSource == null) throw new IllegalArgumentException("dataSource 不能为空");
    this.db = new JdbcTemplate(dataSource);
    this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    this.allowChangedScripts = allowChangedScripts;
  }

  /** 应用所有尚未应用的迁移。返回本次实际应用的版本号列表。 */
  public List<String> migrate() {
    db.execute(CREATE_LEDGER);
    repairEarlyV4Schema();
    Map<String, String> applied = appliedChecksums();
    List<SqlMigration> migrations = SqlMigrationLoader.load();

    List<String> newlyApplied = new java.util.ArrayList<>();
    for (SqlMigration migration : migrations) {
      String existing = applied.get(migration.version());
      if (existing != null) {
        if (!existing.equals(migration.checksum())) {
          if (!allowChangedScripts) {
            throw new IllegalStateException(
                "迁移 V" + migration.version() + " 在应用之后被修改过（校验和不一致）。"
                    + "请勿改动已发布的迁移脚本；如需变更，请新增一个更高版本的迁移。"
                    + "若这是开发环境且你确实刚改过脚本，可显式设置 app.migration.allow-changed-scripts=true 继续启动。");
          }
          log.warn("迁移 V{} 的校验和与脚本不一致，但 allow-changed-scripts 已开启："
              + "按新脚本更新台账。此选项仅应用于开发环境。", migration.version());
          db.update("UPDATE schema_migrations SET checksum=?, description=? WHERE version=?",
              migration.checksum(), migration.description(), migration.version());
        }
        continue;
      }
      apply(migration);
      newlyApplied.add(migration.version());
    }
    // V6 会重建 lab_terms；重建后把改名保留下来的旧学期数据抢救回去。
    restoreTermsFromLegacy();
    backfillMissingMeetings();
    reexpandMissingOccurrences();
    if (newlyApplied.isEmpty()) {
      log.info("数据库结构已是最新，已应用 {} 个迁移", applied.size());
    } else {
      log.info("已应用数据库迁移: {}", newlyApplied);
    }
    return newlyApplied;
  }

  /**
   * 预修复：把"应用过早期 V4"的库对齐到目标结构。
   *
   * <h2>为什么必须在 Java 里做，而不是写在 SQL 迁移里</h2>
   * SQLite 在**解析阶段**就会拒绝引用不存在的列 ——
   * {@code SELECT ... WHERE EXISTS(...)} 或 {@code CASE WHEN EXISTS(...)} 都兜不住
   * {@code no such column}。因此"先探测列结构再决定怎么改"无法用纯 SQL 表达
   * （这是实际踩过的坑，试了三种 SQL 写法都失败）。
   * 放在 Java 里读 {@code pragma_table_info} 再分支，逻辑清楚且可测试。
   *
   * <h2>它修什么</h2>
   * 早期 V4 的形状：
   * <ul>
   *   <li>{@code lab_terms} 是"教学日历"形状：没有 {@code is_current}、
   *       多了 {@code closed_dates}/{@code extra_workdays}</li>
   *   <li>{@code courses} 没有 {@code term_id} / {@code source}</li>
   *   <li>{@code course_occurrences} 没有 {@code term_id}</li>
   * </ul>
   * 对全新安装（V4 已是最终形态），本方法什么都不做。
   *
   * <p>注意 {@code lab_terms} 这里只是**改名**：V6 会用同样的探测方式决定
   * 是抢救旧数据还是原样恢复，从而避免"新库丢表"。
   */
  private void repairEarlyV4Schema() {
    if (!columnExists("lab_terms", "is_current")) {
      if (tableExists("lab_terms")) {
        db.execute("DROP TABLE IF EXISTS _legacy_lab_terms");
        db.execute("ALTER TABLE lab_terms RENAME TO _legacy_lab_terms");
        log.info("检测到早期 V4 的 lab_terms 结构，已重命名为 _legacy_lab_terms 以便重建");
      }
    }

    // course_meetings 是 V4 后期才加进 V4 脚本的。早期 V4 建库时它还不存在，
    // 而**已应用过的 V4 不会重跑**，所以必须在这里补建，否则"可编辑的上课时间"整块功能失效。
    // 用 IF NOT EXISTS 保证对全新安装是 no-op。
    if (!tableExists("course_meetings")) {
      db.execute("""
          CREATE TABLE IF NOT EXISTS course_meetings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            course_id INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
            weekday INTEGER NOT NULL,
            period_start INTEGER NOT NULL,
            period_end INTEGER NOT NULL,
            weeks TEXT NOT NULL DEFAULT '[]',
            location TEXT NOT NULL DEFAULT '',
            UNIQUE(course_id, weekday, period_start, period_end)
          )""");
      db.execute("CREATE INDEX IF NOT EXISTS idx_course_meetings_course ON course_meetings(course_id)");
      log.info("已补建缺失的 course_meetings 表（早期 V4 的库没有这张表）");
    }

    // courses.term_id / source 与 course_occurrences.term_id 由 V6 用
    // ALTER TABLE ... ADD COLUMN 补齐，重复加列会被本类容忍。

    rebuildCoursesIfLegacyUnique();
    rebuildOccurrencesIfOverConstrained();
  }

  /**
   * 把 {@code courses} 重建为目标结构，去掉早期 V4 留下的 {@code UNIQUE(user_id, uid)}。
   *
   * <h2>为什么必须重建而不是只加列</h2>
   * 早期 V4 的 courses 用 {@code UNIQUE(user_id, uid)} 做唯一键。新模型是
   * "一个成员一个学期一份课表"，唯一键要含 {@code term_id}。旧约束会让同一门课
   * 在另一个学期导入时直接撞唯一键 → 导入 500。
   *
   * <p>SQLite 删不掉表级 UNIQUE 约束，只能重建表。重建时把
   * {@code course_meetings}（用户可编辑的时段）一并搬运，避免丢用户数据；
   * {@code course_occurrences} 是可推导的派生数据，交给
   * {@link #backfillMissingMeetings()} 之后的流程重建，因此先清空。
   */
  private void rebuildCoursesIfLegacyUnique() {
    if (!tableExists("courses")) return;
    String sql = db.queryForObject("SELECT sql FROM sqlite_master WHERE type='table' AND name='courses'", String.class);
    if (sql == null) return;
    String normalized = sql.replaceAll("\\s+", " ").toLowerCase();
    boolean legacyUnique = normalized.contains("unique(user_id, uid)")
        && !normalized.contains("unique(user_id, term_id, uid)");
    if (!legacyUnique && columnExists("courses", "term_id")) return;

    log.warn("检测到 courses 仍是早期 V4 结构（唯一键不含 term_id），正在重建并搬运可编辑时段");

    db.execute("DROP TABLE IF EXISTS _new_courses");
    db.execute("""
        CREATE TABLE _new_courses (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id INTEGER NOT NULL,
          term_id INTEGER,
          uid TEXT NOT NULL,
          source TEXT NOT NULL DEFAULT 'ICS',
          summary TEXT NOT NULL DEFAULT '',
          location TEXT NOT NULL DEFAULT '',
          description TEXT NOT NULL DEFAULT '',
          dtstart TEXT NOT NULL,
          dtend TEXT NOT NULL,
          rrule TEXT NOT NULL DEFAULT '',
          periods_raw TEXT NOT NULL DEFAULT '',
          dtstamp TEXT NOT NULL DEFAULT '',
          imported_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours')),
          UNIQUE(user_id, term_id, uid)
        )""");

    boolean hasTerm = columnExists("courses", "term_id");
    boolean hasSource = columnExists("courses", "source");
    db.execute("INSERT INTO _new_courses(id,user_id,term_id,uid,source,summary,location,description,dtstart,dtend,rrule,periods_raw,dtstamp,imported_at) "
        + "SELECT id,user_id," + (hasTerm ? "term_id" : "NULL") + ",uid," + (hasSource ? "source" : "'ICS'")
        + ",summary,location,description,dtstart,dtend,rrule,periods_raw,dtstamp,imported_at FROM courses");

    // 搬运可编辑时段：保留 id 即可让 course_id 继续对应（两边都沿用原 id）。
    boolean hasMeetings = tableExists("course_meetings");
    if (hasMeetings) db.execute("DROP TABLE IF EXISTS _new_meetings");
    if (hasMeetings) {
      db.execute("""
          CREATE TABLE _new_meetings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            course_id INTEGER NOT NULL REFERENCES _new_courses(id) ON DELETE CASCADE,
            weekday INTEGER NOT NULL,
            period_start INTEGER NOT NULL,
            period_end INTEGER NOT NULL,
            weeks TEXT NOT NULL DEFAULT '[]',
            location TEXT NOT NULL DEFAULT '',
            UNIQUE(course_id, weekday, period_start, period_end)
          )""");
      db.execute("INSERT INTO _new_meetings(id,course_id,weekday,period_start,period_end,weeks,location) "
          + "SELECT id,course_id,weekday,period_start,period_end,weeks,location FROM course_meetings");
    }

    db.execute("DROP TABLE IF EXISTS _old_courses");
    db.execute("ALTER TABLE courses RENAME TO _old_courses");
    db.execute("ALTER TABLE _new_courses RENAME TO courses");

    // course_occurrences 引用课程表，且是派生数据（可由 courses + meetings 重新展开），
    // 因此直接重建为空表：既摆脱旧唯一约束，也避免残留对已改名表的引用。
    if (tableExists("course_occurrences")) {
      db.execute("DROP INDEX IF EXISTS idx_course_occ_user_date");
      db.execute("DROP INDEX IF EXISTS idx_course_occ_date");
      db.execute("DROP INDEX IF EXISTS idx_course_occ_course");
      db.execute("DROP TABLE course_occurrences");
    }
    db.execute("""
        CREATE TABLE course_occurrences (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          course_id INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
          user_id INTEGER NOT NULL,
          term_id INTEGER,
          on_date TEXT NOT NULL,
          period_start INTEGER NOT NULL,
          period_end INTEGER NOT NULL,
          UNIQUE(course_id, on_date, period_start)
        )""");
    db.execute("CREATE INDEX IF NOT EXISTS idx_course_occ_user_date ON course_occurrences(user_id, on_date)");
    db.execute("CREATE INDEX IF NOT EXISTS idx_course_occ_date ON course_occurrences(on_date, period_start, period_end)");
    db.execute("CREATE INDEX IF NOT EXISTS idx_course_occ_course ON course_occurrences(course_id)");

    db.execute("DROP TABLE _old_courses");

    if (hasMeetings) {
      db.execute("DROP TABLE IF EXISTS course_meetings");
      db.execute("ALTER TABLE _new_meetings RENAME TO course_meetings");
      db.execute("CREATE INDEX IF NOT EXISTS idx_course_meetings_course ON course_meetings(course_id)");
    }

    db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_courses_user_term_uid ON courses(user_id, term_id, uid)");

    log.warn("courses 重建完成；占用为派生数据已清空，将由自愈流程按可编辑时段重新展开");
  }

  /**
   * 去掉 {@code course_occurrences} 上过严的旧唯一约束。
   *
   * <h2>为什么必须处理</h2>
   * 早期 V4 给这张表加了 {@code UNIQUE(course_id, on_date)}。但一门课**可以在同一天有多段课**
   * —— 真实课表里就有（学校在周中某个日期补课又照常排课），此时插入第二条就撞唯一键，
   * 表现为导入直接 500。
   *
   * <p>新模型的主键语义是"同一课程、同一天、同一讲课起点"，因此要把表重建为
   * {@code UNIQUE(course_id, on_date, period_start)}。
   *
   * <p>这个缺陷在全新安装上不存在（V4 已是新定义），所以单元测试覆盖不到 ——
   * 只在"从早期 V4 升级"的库上出现，属于必须靠真实升级路径才能发现的类别。
   */
  private void rebuildOccurrencesIfOverConstrained() {
    if (!tableExists("course_occurrences")) return;

    String sql = db.queryForObject(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='course_occurrences'", String.class);
    if (sql == null) return;
    String normalized = sql.replaceAll("\\s+", " ").toLowerCase();
    boolean overConstrained = normalized.contains("unique(course_id, on_date)")
        && !normalized.contains("unique(course_id, on_date, period_start)");
    if (!overConstrained) return;

    log.warn("检测到 course_occurrences 仍带旧约束 UNIQUE(course_id, on_date)，正在重建以允许同一天多段课");
    db.execute("DROP INDEX IF EXISTS idx_course_occ_user_date");
    db.execute("DROP INDEX IF EXISTS idx_course_occ_date");
    db.execute("DROP INDEX IF EXISTS idx_course_occ_course");
    db.execute("ALTER TABLE course_occurrences RENAME TO _old_course_occurrences");
    db.execute("""
        CREATE TABLE course_occurrences (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          course_id INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
          user_id INTEGER NOT NULL,
          term_id INTEGER,
          on_date TEXT NOT NULL,
          period_start INTEGER NOT NULL,
          period_end INTEGER NOT NULL,
          UNIQUE(course_id, on_date, period_start)
        )""");
    // 用 INSERT OR IGNORE 迁移旧数据：重建的唯一键更宽松，理论上不会丢行，
    // 但历史上可能已存在重复数据，IGNORE 保证重建过程不会失败。
    db.execute("INSERT OR IGNORE INTO course_occurrences(id,course_id,user_id,term_id,on_date,period_start,period_end) "
        + "SELECT id,course_id,user_id,term_id,on_date,period_start,period_end FROM _old_course_occurrences");
    db.execute("DROP TABLE _old_course_occurrences");
    db.execute("CREATE INDEX IF NOT EXISTS idx_course_occ_user_date ON course_occurrences(user_id, on_date)");
    db.execute("CREATE INDEX IF NOT EXISTS idx_course_occ_date ON course_occurrences(on_date, period_start, period_end)");
    db.execute("CREATE INDEX IF NOT EXISTS idx_course_occ_course ON course_occurrences(course_id)");
    log.warn("course_occurrences 重建完成");
  }

  /**
   * 学期表重建：把 {@code _legacy_lab_terms} 的数据按旧表实际形状抢救回 {@code lab_terms}。
   *
   * <p>这里必须在 Java 里分支：SQLite 解析阶段就会拒绝不存在的列，
   * 因此"旧表有没有 is_current"决定了能写哪条 INSERT，无法用一条 SQL 兼顾。
   * 早期形态没有 id，所以只能按聚合挑出最早的一条作为当前学期（实际部署通常只有一条）。
   *
   * <p>对全新安装（{@code lab_terms} 已是目标形状）本方法是 no-op。
   */
  private void restoreTermsFromLegacy() {
    if (!tableExists("_legacy_lab_terms")) return;

    if (columnExists("_legacy_lab_terms", "is_current")) {
      db.update("INSERT INTO lab_terms(name,start_date,end_date,is_current) "
          + "SELECT name,start_date,end_date,is_current FROM _legacy_lab_terms");
    } else {
      var rows = db.queryForList("SELECT name,start_date,end_date FROM _legacy_lab_terms");
      if (!rows.isEmpty()) {
        // 按开始日期取最早的一条，行为确定且可解释
        var earliest = rows.stream()
            .min(java.util.Comparator.comparing(row -> String.valueOf(row.get("start_date"))))
            .orElseThrow();
        db.update("INSERT INTO lab_terms(name,start_date,end_date,is_current) VALUES(?,?,?,1)",
            String.valueOf(earliest.get("name")), String.valueOf(earliest.get("start_date")),
            String.valueOf(earliest.get("end_date")));
        log.info("已把早期 V4 的学期配置（{} 起）保留为当前学期", earliest.get("start_date"));
      }
    }
    db.execute("DROP TABLE IF EXISTS _legacy_lab_terms");
  }

  private boolean tableExists(String table) {
    Integer count = db.queryForObject(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table);
    return count != null && count > 0;
  }

  private boolean columnExists(String table, String column) {
    if (!tableExists(table)) return false;
    Integer count = db.queryForObject(
        "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name=?", Integer.class, table, column);
    return count != null && count > 0;
  }

  /**
   * 自愈：给"有课程却没有可编辑时段"的库补回上课时间。
   *
   * <h2>为什么需要</h2>
   * 迁移台账按版本记录"已应用"，但一次迁移可能因为其中某条语句失败而只完成一部分
   * （V6 就真实出现过：结构补齐了、聚合回填没跑，而版本已被记入台账）。
   * 这种"结构已应用、数据没补"的库用台账判断不出来，
   * 结果就是"可编辑的上课时间"整块功能对老课程失效 —— 界面能打开但每门课都没有时段。
   *
   * <p>因此这里不看台账，只看**数据是否自相矛盾**：
   * 有展开出来的占用、却有课程一条时段都没有 ⇒ 需要回填。
   * 判定是幂等的，正常库不会命中。
   */
  private void backfillMissingMeetings() {
    if (!tableExists("course_meetings") || !tableExists("course_occurrences")) return;
    if (!columnExists("courses", "term_id")) return;

    Integer missing = db.queryForObject("""
        SELECT COUNT(*) FROM courses c
         WHERE EXISTS (SELECT 1 FROM course_occurrences o WHERE o.course_id = c.id)
           AND NOT EXISTS (SELECT 1 FROM course_meetings m WHERE m.course_id = c.id)""", Integer.class);
    if (missing == null || missing == 0) return;

    int inserted = db.update("""
        INSERT INTO course_meetings(course_id, weekday, period_start, period_end, weeks, location)
        SELECT o.course_id,
               (CAST(strftime('%w', o.on_date) AS INTEGER) + 6) % 7,
               o.period_start,
               o.period_end,
               '[' || group_concat(
                   CAST((julianday(o.on_date) - julianday(t.start_date)) / 7 AS INTEGER) + 1
               ) || ']',
               COALESCE(c.location, '')
          FROM course_occurrences o
          JOIN courses c ON c.id = o.course_id
          JOIN lab_terms t ON t.id = c.term_id
         WHERE NOT EXISTS (SELECT 1 FROM course_meetings m WHERE m.course_id = o.course_id)
         GROUP BY o.course_id,
                  (CAST(strftime('%w', o.on_date) AS INTEGER) + 6) % 7,
                  o.period_start,
                  o.period_end""");
    log.warn("检测到 {} 门课程缺少可编辑的上课时间，已自动回填 {} 条时段", missing, inserted);
  }

  /**
   * 自愈：为"有时段却没有展开占用"的课程重新展开。
   *
   * <p>重建 {@code courses} 时清空了派生占用（因为唯一约束变了），
   * 因此必须能从可编辑时段把它重新算出来 —— 时段是用户数据，不能丢。
   *
   * <p>判据同样只看数据是否自相矛盾：有时段、却没有占用 ⇒ 需要展开。
   * 展开规则与 {@code CourseImportService.expand} 保持一致：
   * 周次 w、周几 d ⇒ 日期 = 学期起始日 + (w-1) 周 + d 天（d 以周一为 0）。
   */
  private void reexpandMissingOccurrences() {
    if (!tableExists("course_occurrences") || !tableExists("course_meetings")) return;
    if (!columnExists("courses", "term_id")) return;

    Integer missing = db.queryForObject("""
        SELECT COUNT(*) FROM courses c
         WHERE EXISTS (SELECT 1 FROM course_meetings m WHERE m.course_id = c.id)
           AND NOT EXISTS (SELECT 1 FROM course_occurrences o WHERE o.course_id = c.id)""", Integer.class);
    if (missing == null || missing == 0) return;

    List<Map<String, Object>> rows = db.queryForList("""
        SELECT m.course_id, m.weekday, m.period_start, m.period_end, m.weeks,
               c.user_id, c.term_id, t.start_date
          FROM course_meetings m
          JOIN courses c ON c.id = m.course_id
          JOIN lab_terms t ON t.id = c.term_id
         WHERE NOT EXISTS (SELECT 1 FROM course_occurrences o WHERE o.course_id = m.course_id)""");

    int inserted = 0;
    for (Map<String, Object> row : rows) {
      java.time.LocalDate termStart = java.time.LocalDate.parse(String.valueOf(row.get("start_date")));
      int weekday = ((Number) row.get("weekday")).intValue();
      int periodStart = ((Number) row.get("period_start")).intValue();
      int periodEnd = ((Number) row.get("period_end")).intValue();
      for (int week : parseWeeks(String.valueOf(row.get("weeks")))) {
        if (week < 1) continue;
        java.time.LocalDate date = termStart.plusWeeks(week - 1L).plusDays(weekday);
        db.update("INSERT OR IGNORE INTO course_occurrences(course_id,user_id,term_id,on_date,period_start,period_end) "
                + "VALUES(?,?,?,?,?,?)",
            row.get("course_id"), row.get("user_id"), row.get("term_id"),
            date.toString(), periodStart, periodEnd);
        inserted++;
      }
    }
    log.warn("为 {} 门课程重新展开出 {} 条上课占用（结构重建后自愈）", missing, inserted);
  }

  /** 解析 weeks JSON 数组，容错：格式异常时返回空集合。 */
  private static java.util.Set<Integer> parseWeeks(String json) {
    java.util.Set<Integer> weeks = new java.util.TreeSet<>();
    if (json == null) return weeks;
    for (String piece : json.replaceAll("[\\[\\]\\s]", "").split(",")) {
      if (piece.isEmpty()) continue;
      try {
        weeks.add(Integer.parseInt(piece));
      } catch (NumberFormatException ignored) {
        // 单个脏值忽略，不影响其余周次
      }
    }
    return weeks;
  }

  private void apply(SqlMigration migration) {    List<String> statements = migration.statements();
    log.info("正在应用迁移 V{} ({})，共 {} 条语句", migration.version(), migration.description(), statements.size());
    // PRAGMA 必须在事务外执行：journal_mode 的切换在事务内会被 SQLite 拒绝
    // （"cannot change into wal mode from within a transaction"）。
    // 迁移脚本里的 PRAGMA 都是幂等的连接级/库级设置，重复执行无副作用。
    statements.stream().filter(MigrationRunner::isPragma).forEach(this::executeOutsideTransaction);
    tx.executeWithoutResult(status -> {
      for (int index = 0; index < statements.size(); index++) {
        String statement = statements.get(index);
        if (isPragma(statement)) continue;
        try {
          db.execute(statement);
        } catch (RuntimeException e) {
          if (isAlreadyAppliedError(e)) {
            log.debug("迁移 V{} 语句已生效，跳过: {}", migration.version(), abbreviate(statement));
            continue;
          }
          throw new IllegalStateException(
              "迁移 V" + migration.version() + " 第 " + (index + 1) + "/" + statements.size()
                  + " 条语句执行失败: " + abbreviate(statement), e);
        }
      }
      db.update("INSERT INTO schema_migrations(version,description,checksum) VALUES(?,?,?)",
          migration.version(), migration.description(), migration.checksum());
    });
  }

  private void executeOutsideTransaction(String statement) {
    var connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(Objects.requireNonNull(db.getDataSource()));
    try (var prepared = connection.createStatement()) {
      prepared.execute(statement);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("迁移 PRAGMA 执行失败: " + statement, e);
    } finally {
      org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection, db.getDataSource());
    }
  }

  private static boolean isPragma(String statement) {
    return statement.stripLeading().regionMatches(true, 0, "PRAGMA", 0, 6);
  }

  private Map<String, String> appliedChecksums() {
    return db.query("SELECT version, checksum FROM schema_migrations",
        rs -> {
          Map<String, String> result = new java.util.HashMap<>();
          while (rs.next()) result.put(rs.getString("version"), rs.getString("checksum"));
          return result;
        });
  }

  /**
   * 让部分语句可以安全地重跑或在不适用时跳过：
   *
   * <ul>
   *   <li>{@code duplicate column name} —— SQLite 没有
   *       {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}，重复加列视为已应用。</li>
   *   <li>{@code no such table} —— 形如
   *       {@code CREATE TABLE ... AS SELECT * FROM reports} 的"归档遗留表"语句，
   *       在遗留表本就不存在的新库上必须跳过，否则新库无法完成初始化。</li>
   * </ul>
   */
  private static boolean isAlreadyAppliedError(RuntimeException e) {
    String message = e.getMessage();
    if (message == null) return false;
    return message.contains("duplicate column name") || message.contains("no such table");
  }

  private static String abbreviate(String statement) {
    String single = statement.replaceAll("\\s+", " ").trim();
    return single.length() <= 80 ? single : single.substring(0, 77) + "...";
  }
}
