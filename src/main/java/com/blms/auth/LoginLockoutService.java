package com.blms.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录防爆破（服务端权威，等价前端 M8：连续 5 次失败 → 锁定 5 分钟，成功清零）。
 * 维度：仅按用户名（与前端 M8 口径一致；换浏览器/换 IP 仍按账号锁定）。
 * 存储：Redis key=login:fail:{user}（失败计数，TTL 5 分钟滑动窗口）+ login:lock:{user}（锁定标记，TTL 5 分钟）。
 * 前端 M8 的 localStorage 锁定为体验层（换浏览器可绕过），本服务为权威拦截。
 */
@Service
public class LoginLockoutService {

    public static final int MAX_FAILS = 5;
    public static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final String FAIL_PREFIX = "login:fail:";
    private static final String LOCK_PREFIX = "login:lock:";

    private final StringRedisTemplate redis;

    public LoginLockoutService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String norm(String username) {
        return String.valueOf(username == null ? "" : username).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 当前账号是否处于锁定期；锁定返回剩余秒数，未锁定返回 null */
    public Long lockRemainingSeconds(String username) {
        String key = LOCK_PREFIX + norm(username);
        if (Boolean.FALSE.equals(redis.hasKey(key))) return null;
        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        return ttl == null || ttl < 0 ? 0L : ttl;
    }

    /** 记一次凭据失败：返回锁定剩余信息。
     *  达到上限 → 进入 5 分钟锁定（清计数、置锁），返回 {locked=true, remaining}；
     *  未达上限 → 返回 {locked=false, remaining=MAX_FAILS-新计数}。 */
    public LockResult recordFailure(String username) {
        String u = norm(username);
        String failKey = FAIL_PREFIX + u;
        String lockKey = LOCK_PREFIX + u;
        Long count = redis.opsForValue().increment(failKey);
        long c = count == null ? 1 : count;
        if (c >= MAX_FAILS) {
            redis.delete(failKey);
            redis.opsForValue().set(lockKey, "1", LOCK_TTL);
            return new LockResult(true, LOCK_TTL.getSeconds());
        }
        redis.expire(failKey, LOCK_TTL); // 滑动窗口：5 分钟无新失败则计数过期
        return new LockResult(false, MAX_FAILS - c);
    }

    /** 登录成功：清零该账号失败计数与锁定 */
    public void clear(String username) {
        String u = norm(username);
        redis.delete(FAIL_PREFIX + u);
        redis.delete(LOCK_PREFIX + u);
    }

    /** 清空所有账号的防爆破计数与锁定（演示/测试用：reset-demo 时自恢复，避免某账号被锁后影响后续场景登录） */
    public void clearAll() {
        java.util.Set<String> failKeys = redis.keys(FAIL_PREFIX + "*");
        if (failKeys != null) for (String k : failKeys) redis.delete(k);
        java.util.Set<String> lockKeys = redis.keys(LOCK_PREFIX + "*");
        if (lockKeys != null) for (String k : lockKeys) redis.delete(k);
    }

    public record LockResult(boolean locked, long remaining) {}
}
