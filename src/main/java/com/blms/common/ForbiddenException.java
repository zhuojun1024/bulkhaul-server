package com.blms.common;

/** 权限不足异常（@RequireAction 切面抛出，由全局异常处理器转 403） */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
