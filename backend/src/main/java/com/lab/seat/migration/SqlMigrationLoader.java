package com.lab.seat.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迁移脚本加载与解析。
 *
 * <p>脚本放在 classpath 的 {@code db/migration} 下，命名规则 {@code V<数字>__<描述>.sql}，
 * 与 Flyway 的习惯保持一致，但本类不依赖 Flyway。按版本号升序返回。
 *
 * <p>每个脚本会计算 SHA-256 校验和，用于检测"已应用的脚本被事后修改"。
 */
final class SqlMigrationLoader {
  private static final String LOCATION = "db/migration";
  private static final Pattern NAME = Pattern.compile("V(\\d+)__(.+)\\.sql");

  private SqlMigrationLoader() {}

  static List<SqlMigration> load() {
    List<String> names;
    try {
      names = scanClasspath();
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取迁移脚本目录 " + LOCATION, e);
    }
    List<SqlMigration> migrations = new ArrayList<>();
    for (String name : names) {
      Matcher matcher = NAME.matcher(name);
      if (!matcher.matches()) continue;
      String sql = read(name);
      migrations.add(new SqlMigration(matcher.group(1), matcher.group(2), sha256(sql), statementsOf(sql)));
    }
    migrations.sort(Comparator.comparingInt(m -> Integer.parseInt(m.version())));
    return migrations;
  }

  /**
   * 枚举 classpath 下的迁移脚本。
   *
   * <p>必须同时支持两种部署形态：
   * <ul>
   *   <li><b>开发/测试</b>：{@code target/classes/db/migration} 是普通目录（file: URL）。</li>
   *   <li><b>Spring Boot 可执行 fat jar</b>：脚本在 {@code BOOT-INF/classes/db/migration}，
   *       URL 形如 {@code jar:file:/app.jar!/BOOT-INF/classes!/db/migration}，
   *       这个 URI <b>不是层级式的</b>，{@code new File(url.toURI())} 会抛
   *       {@code IllegalArgumentException: URI is not hierarchical}。</li>
   * </ul>
   * 因此这里不自己去解析 URL 字符串，而是通过 {@link java.net.JarURLConnection}
   * 拿到真正的 jar 文件与条目前缀 —— 它对两种嵌套布局都成立。
   */
  private static List<String> scanClasspath() throws IOException {
    var resource = SqlMigrationLoader.class.getClassLoader().getResource(LOCATION);
    if (resource == null) throw new IOException("classpath 中不存在 " + LOCATION);
    List<String> names = new ArrayList<>();

    if ("file".equals(resource.getProtocol())) {
      try (var paths = java.nio.file.Files.list(java.nio.file.Path.of(resource.toURI()))) {
        paths.filter(java.nio.file.Files::isRegularFile)
             .map(path -> path.getFileName().toString())
             .filter(name -> name.endsWith(".sql"))
             .forEach(names::add);
      } catch (java.net.URISyntaxException e) {
        throw new IOException("迁移脚本目录路径无法解析", e);
      }
    } else {
      var connection = resource.openConnection();
      if (!(connection instanceof java.net.JarURLConnection jarConnection)) {
        throw new IOException("无法识别的 classpath 形态: " + resource);
      }
      String prefix = jarConnection.getEntryName();
      if (prefix == null) throw new IOException("无法解析迁移脚本目录条目: " + resource);
      // lambda 捕获要求 effectively final，因此重新赋值给一个局部常量。
      final String directory = prefix.endsWith("/") ? prefix : prefix + "/";
      try (var jar = jarConnection.getJarFile()) {
        jar.stream()
           .map(java.util.jar.JarEntry::getName)
           .filter(name -> name.startsWith(directory) && name.endsWith(".sql"))
           .map(name -> name.substring(directory.length()))
           .filter(name -> !name.contains("/"))
           .forEach(names::add);
      }
    }

    names.sort(Comparator.naturalOrder());
    return names;
  }

  private static String read(String name) {
    try (InputStream in = SqlMigrationLoader.class.getClassLoader().getResourceAsStream(LOCATION + "/" + name)) {
      if (in == null) throw new IOException("迁移脚本不存在: " + name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("读取迁移脚本失败: " + name, e);
    }
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("运行环境缺少 SHA-256", e);
    }
  }

  /**
   * 按分号切分语句，同时正确处理：
   * 单引号字符串（含 {@code ''} 转义）、双引号与反引号标识符、{@code --} 行注释、
   * {@code /* *}{@code /} 块注释，以及括号嵌套内的分号。
   *
   * <p>注释会被剥离。不使用 SQLite 不支持的 {@code BEGIN...END} 触发器体，
   * 因此括号深度足以判定语句边界。
   */
  static List<String> statementsOf(String sql) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    char quote = 0;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (quote != 0) {
        current.append(c);
        if (c == quote) {
          if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
            current.append(sql.charAt(++i));
          } else {
            quote = 0;
          }
        }
        continue;
      }
      if (c == '\'' || c == '"' || c == '`') {
        quote = c;
        current.append(c);
        continue;
      }
      if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
        while (i < sql.length() && sql.charAt(i) != '\n') i++;
        current.append('\n');
        continue;
      }
      if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
        i += 2;
        while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
        i++;
        continue;
      }
      if (c == '(') depth++;
      if (c == ')') depth--;
      if (c == ';' && depth == 0) {
        addIfNotBlank(statements, current);
        current.setLength(0);
        continue;
      }
      current.append(c);
    }
    addIfNotBlank(statements, current);
    return statements;
  }

  private static void addIfNotBlank(List<String> target, StringBuilder buffer) {
    String statement = buffer.toString().trim();
    if (!statement.isEmpty()) target.add(statement);
  }
}
