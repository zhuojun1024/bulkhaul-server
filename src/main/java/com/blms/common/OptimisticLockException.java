package com.blms.common;

/**
 * B3 乐观锁冲突：客户端提交的期望版本与记录当前版本不一致（期间被其他会话改动）。
 * 由 GlobalExceptionHandler 映射为 409 + code=conflict，前端提示"数据已变更，请刷新"。
 */
public class OptimisticLockException extends RuntimeException {

    public OptimisticLockException(String message) {
        super(message);
    }
}