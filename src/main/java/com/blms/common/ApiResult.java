package com.blms.common;

import lombok.Data;

/** 统一响应包装（前端 mock 层返回 { ok, user } / { error, code } 的等价物） */
@Data
public class ApiResult<T> {

    private boolean ok;
    private T data;
    private String error;
    private String code;

    public static <T> ApiResult<T> success(T data) {
        ApiResult<T> r = new ApiResult<>();
        r.ok = true;
        r.data = data;
        return r;
    }

    public static <T> ApiResult<T> fail(String error, String code) {
        ApiResult<T> r = new ApiResult<>();
        r.ok = false;
        r.error = error;
        r.code = code;
        return r;
    }

    /** 带 data 的失败（A5 字段级校验：data.fieldErrors = { 字段: 首条错误消息 }） */
    public static <T> ApiResult<T> fail(String error, String code, T data) {
        ApiResult<T> r = fail(error, code);
        r.data = data;
        return r;
    }
}
