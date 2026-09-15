package com.lab.seat;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
class LoginAttemptGuard {
  private record Attempt(int failures, Instant firstFailureAt, Instant lockedUntil) {}

  private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
  private final int maximumAttempts;
  private final Duration lockDuration;

  LoginAttemptGuard(@Value("${app.security.login-max-attempts}") int maximumAttempts, @Value("${app.security.login-lock-minutes}") long lockMinutes) {
    this.maximumAttempts = maximumAttempts;
    this.lockDuration = Duration.ofMinutes(lockMinutes);
  }

  void verifyAllowed(String studentNo, HttpServletRequest request) {
    Attempt attempt = attempts.get(key(studentNo, request));
    if (attempt != null && attempt.lockedUntil() != null && Instant.now().isBefore(attempt.lockedUntil())) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "登录尝试过多，请 15 分钟后再试");
    }
  }

  void recordFailure(String studentNo, HttpServletRequest request) {
    String key = key(studentNo, request);
    attempts.compute(key, (ignored, previous) -> {
      Instant current = Instant.now();
      if (previous == null || Duration.between(previous.firstFailureAt(), current).compareTo(lockDuration) > 0) return new Attempt(1, current, null);
      int failures = previous.failures() + 1;
      return failures >= maximumAttempts ? new Attempt(failures, previous.firstFailureAt(), current.plus(lockDuration)) : new Attempt(failures, previous.firstFailureAt(), null);
    });
  }

  void recordSuccess(String studentNo, HttpServletRequest request) { attempts.remove(key(studentNo, request)); }

  private String key(String studentNo, HttpServletRequest request) { return studentNo + "|" + request.getRemoteAddr(); }
}
