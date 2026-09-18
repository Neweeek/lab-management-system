package com.lab.seat;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiErrorHandler.class);

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException exception) {
    String message = exception.getReason() == null || exception.getReason().isBlank() ? "请求未能完成" : exception.getReason();
    return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", message));
  }
  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ResponseEntity<Map<String, String>> invalidInput(Exception exception) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "提交内容不符合要求，请检查后重试"));
  }
  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> unexpected(Exception exception) {
    // 对外只给一句通用提示（不暴露堆栈），但**必须**记到服务端日志：
    // 否则 500 变成完全不可诊断的黑盒，只能靠猜（这个坑真实踩过）。
    log.error("请求处理失败", exception);
    return ResponseEntity.status(500).body(Map.of("message", "服务器暂时无法处理该请求，请稍后重试"));
  }
}
