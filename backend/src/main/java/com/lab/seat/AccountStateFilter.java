package com.lab.seat;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** A removed account cannot keep using an older browser session. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class AccountStateFilter extends OncePerRequestFilter {
  private final JdbcTemplate db;
  AccountStateFilter(JdbcTemplate db){this.db=db;}
  @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().startsWith("/api/");}
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
    var session=request.getSession(false);
    if(session!=null && session.getAttribute("uid") instanceof Number id) {
      var users=db.queryForList("SELECT role,approved FROM users WHERE id=?",id.longValue());
      if(users.isEmpty() || ((Number)users.get(0).get("approved")).intValue()!=1 || !users.get(0).get("role").equals(session.getAttribute("role"))) {
        session.invalidate();response.setStatus(401);response.setCharacterEncoding("UTF-8");response.setContentType("application/json");response.getWriter().write("{\"message\":\"账号已停用或权限已变更，请重新登录\"}");return;
      }
    }
    chain.doFilter(request,response);
  }
}
