package com.lab.seat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
class CsrfTokenFilter extends OncePerRequestFilter {
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    String method = request.getMethod();
    return !path.startsWith("/api/") || HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method) || path.equals("/api/auth/login") || path.equals("/api/auth/register");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    String expected = session == null ? null : (String) session.getAttribute("csrfToken");
    String supplied = request.getHeader("X-CSRF-Token");
    if (expected == null || !expected.equals(supplied)) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setContentType("application/json");
      response.getWriter().write("{\"message\":\"安全校验已失效，请重新登录\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
