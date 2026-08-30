package com.blms.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器：
 * ForbiddenException / AccessDeniedException → 403（RBAC 拦截，与前端 requireAction 错误文案一致）
 * 其余异常 → 500（带 trace 摘要，生产环境应脱敏）
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResult<Void>> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(e.getMessage(), "forbidden"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResult<Void>> accessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail("无访问权限", "forbidden"));
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ApiResult<Void>> conflict(OptimisticLockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResult.fail(e.getMessage(), "conflict"));
    }

    /** A5：@Valid DTO 字段级校验失败 → 400 + 字段级错误（field → 首条消息），前端可逐字段提示 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Map<String, Object>>> invalid(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        String first = fieldErrors.values().stream().findFirst().orElse("参数校验失败");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.fail(first, "validation_error", data));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> internal(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail("服务器内部错误: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), "internal"));
    }
}
