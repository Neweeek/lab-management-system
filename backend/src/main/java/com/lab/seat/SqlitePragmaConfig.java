package com.lab.seat;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * 为每条 SQLite 连接设置必需的 PRAGMA。
 *
 * <h2>为什么不能只靠 connection-init-sql</h2>
 * 曾经把两条 PRAGMA 用分号串在 {@code spring.datasource.hikari.connection-init-sql}
 * 里：{@code "PRAGMA busy_timeout=10000; PRAGMA foreign_keys=ON"}。
 * 实测 sqlite-jdbc 只执行了第一条 —— 连接上的 {@code PRAGMA foreign_keys} 读回来是 0，
 * 于是 schema 里所有 {@code REFERENCES} 声明都形同注释，孤儿行不会被拦下。
 * 这个错误很隐蔽：应用能正常启动，只有专门去读 PRAGMA 才会发现。
 *
 * <p>因此改为显式包装数据源，在**每次**取连接时逐条执行 PRAGMA。相比 URL 参数，
 * 这种写法与驱动版本无关，且能明确断言生效。
 */
@Component
class SqlitePragmaConfig implements BeanPostProcessor {

  /** 每条连接都要设置的 PRAGMA；顺序无关，都是幂等的连接级设置。 */
  private static final String[] PRAGMAS = {
      "PRAGMA foreign_keys=ON",
      "PRAGMA busy_timeout=10000"
  };

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    if (bean instanceof DataSource dataSource && !(bean instanceof PragmaDataSource)) {
      return new PragmaDataSource(dataSource);
    }
    return bean;
  }

  /** 轻量包装：只在取连接时补 PRAGMA，其余全部委托。 */
  static final class PragmaDataSource implements DataSource {
    private final DataSource delegate;

    PragmaDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    @Override public Connection getConnection() throws SQLException {
      return prepare(delegate.getConnection());
    }

    @Override public Connection getConnection(String username, String password) throws SQLException {
      return prepare(delegate.getConnection(username, password));
    }

    private static Connection prepare(Connection connection) throws SQLException {
      try {
        applyPragmas(connection);
        return connection;
      } catch (SQLException e) {
        try {
          connection.close();
        } catch (SQLException ignored) {
          // 关闭失败不应掩盖真正的原因
        }
        throw new SQLException("无法为 SQLite 连接设置 PRAGMA: " + e.getMessage(), e);
      }
    }

    private static void applyPragmas(Connection connection) throws SQLException {
      try (Statement statement = connection.createStatement()) {
        for (String pragma : PRAGMAS) statement.execute(pragma);
      }
    }

    // javax.sql.DataSource 声明这两个方法抛 SQLException，必须如实声明。
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() { return Logger.getLogger("global"); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
  }
}
