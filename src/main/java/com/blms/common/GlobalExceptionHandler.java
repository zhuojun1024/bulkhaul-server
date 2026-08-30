package com.blms.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> internal(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail("服务器内部错误: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), "internal"));
    }
}
