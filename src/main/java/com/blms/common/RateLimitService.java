package com.blms.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 全局限流（A3，服务端权威）：Redis 固定窗口计数，Lua 原子 INCR+EXPIRE（无竞态）。
 * 维度：登录/验证码按 IP（严格）；写端点按用户（适度，未认证按 IP 兜底）；GET 不限。
 * 超限 → 429 + Retry-After（剩余秒数）+ code=rate_limited。
 * 存储：Redis key=rl:{tier}:{dim}（计数，TTL=窗口 60s 滑动）；窗口到期自动清零。
 * 降级：Redis 不可用 fail-open（放行 + warn 日志）——限流是防护层，不应因 Redis 抖动阻断全部请求。
 * 配置：blms.rate-limit.enabled / login-per-minute / write-per-minute（dev 宽松，prod 严格，见 application-prod.yml）。
 */
@Service
public class RateLimitService {

    public static final String TIER_LOGIN = "login";
    public static final String TIER_WRITE = "write";
    private static final String KEY_PREFIX = "rl:";
    private static final Duration WINDOW = Duration.ofSeconds(60);

    /** 固定窗口：INCR 到 1 时设 TTL=60s；返回单字符串 "计数:剩余秒数"（避免 StringRedisTemplate 反序列化 Lua 多 bulk 表失败） */
    private static final DefaultRedisScript<String> WINDOW_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) " +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "local ttl = redis.call('TTL', KEYS[1]) " +
            "if ttl < 0 then ttl = tonumber(ARGV[1]) end " +
            "return tostring(c) .. ':' .. tostring(ttl)", String.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final long loginPerMinute;
    private final long writePerMinute;

    public RateLimitService(StringRedisTemplate redis,
                            @Value("${blms.rate-limit.enabled:true}") boolean enabled,
                            @Value("${blms.rate-limit.login-per-minute:120}") long loginPerMinute,
                            @Value("${blms.rate-limit.write-per-minute:600}") long writePerMinute) {
        this.redis = redis;
        this.enabled = enabled;
        this.loginPerMinute = loginPerMinute;
        this.writePerMinute = writePerMinute;
    }

    /** 记一次请求：未超限返回 null；超限返回 Retry-After 剩余秒数。Redis 异常 fail-open（放行）。 */
    public Long tryAcquire(String tier, String dimension) {
        if (!enabled) return null;
        long limit = TIER_LOGIN.equals(tier) ? loginPerMinute : writePerMinute;
        if (limit <= 0) return null; // 0=不限（该档关闭）
        try {
            String r = redis.execute(WINDOW_SCRIPT, List.of(KEY_PREFIX + tier + ":" + dimension),
                    String.valueOf(WINDOW.getSeconds()));
            if (r == null || r.indexOf(':') < 0) return null;
            long c = Long.parseLong(r.substring(0, r.indexOf(':')));
            long ttl = Long.parseLong(r.substring(r.indexOf(':') + 1));
            return c > limit ? Math.max(1, ttl) : null;
        } catch (Exception e) {
            // fail-open：限流是防护层，Redis 抖动不应阻断全部请求
            org.slf4j.LoggerFactory.getLogger(RateLimitService.class)
                    .warn("rate-limit: Redis 不可用，放行 {} {}（fail-open）", tier, dimension, e);
            return null;
        }
    }

    /** 清空所有限流计数（演示/测试用：reset-demo 时自恢复，避免某 IP/账号被限后影响后续场景） */
    public void clearAll() {
        try {
            java.util.Set<String> keys = redis.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        } catch (Exception ignored) { /* fail-open：清不掉不影响主流程 */ }
    }
}
