package com.blms.service.scheduler;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * C4 服务端定时任务：单实例 leader 租约（Redis 原子 SET/RENEW，多实例下定时任务只跑一次）。
 * 生产多实例部署时，仅持有 leader 租约的实例执行 runSchedulerTick；leader 宕机后租约过期（TTL），
 * 其余实例在 ≤TTL 内接管（自愈）。Redis 不可用 → **fail-close**（跳过本轮，宁停勿双跑，避免重复围栏/遥测/升级）。
 * 单实例部署：本实例恒为 leader（租约始终可获取/续期），行为与无租约一致。
 * 租约 Lua 返回单字符串 "1"/"0"（A3 验证过的单 bulk 反序列化模式，规避多 bulk 表反序列化失败）。
 */
@Service
public class SchedulerLeaderService {

    /** leader 租约键（reset-demo 自恢复时清空） */
    public static final String KEY = "blms:scheduler:leader";
    /** 租约 TTL：> 3s tick 间隔（leader 每轮续期）；leader 宕机后 ≤10s 由其余实例接管 */
    private static final long TTL_MS = 10_000;
    /** 本实例唯一 id（每 JVM 一个，租约持有者标识） */
    private final String instanceId = UUID.randomUUID().toString();
    private final StringRedisTemplate redis;

    private static final DefaultRedisScript<String> LEASE_SCRIPT;
    static {
        LEASE_SCRIPT = new DefaultRedisScript<>();
        LEASE_SCRIPT.setScriptText(
                "local v = redis.call('get', KEYS[1]) "
                        + "if v == false then redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2]) return '1' "
                        + "elseif v == ARGV[1] then redis.call('pexpire', KEYS[1], ARGV[2]) return '1' "
                        + "else return '0' end");
        LEASE_SCRIPT.setResultType(String.class);
    }

    public SchedulerLeaderService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 尝试获取/续期 leader 租约：true=本实例为 leader（可执行定时任务），false=非 leader（跳过）。Redis 不可用 → false（fail-close）。 */
    public boolean tryAcquireOrRenew() {
        try {
            String r = redis.execute(LEASE_SCRIPT, List.of(KEY), instanceId, String.valueOf(TTL_MS));
            return "1".equals(r);
        } catch (Exception e) {
            return false; // fail-close：Redis 不可用 → 宁停勿双跑
        }
    }

    /** 自恢复：清空 leader 租约（reset-demo 时调用，避免旧租约残留影响后续接管）。 */
    public void clear() {
        try { redis.delete(KEY); } catch (Exception ignored) { }
    }

    public String instanceId() { return instanceId; }
}
