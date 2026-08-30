package com.blms;

import com.blms.service.scheduler.SchedulerLeaderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C4 服务端定时任务：单实例 leader 租约（多实例下定时任务只跑一次）。
 * 独立 @SpringBootTest（连测试 Redis 127.0.0.1:6379，与 A3 限流测试同环境）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SchedulerLeaderServiceTest {

    @Autowired SchedulerLeaderService leader;
    @Autowired StringRedisTemplate redis;

    @Test
    void leaderLeaseSingleInstance() {
        leader.clear();
        assertTrue(leader.tryAcquireOrRenew(), "首个实例获取 leader 租约");
        assertTrue(leader.tryAcquireOrRenew(), "leader 续期成功（每轮 tick 续期）");
        // 模拟另一实例持有租约（key 值 != 本实例 id）→ 本实例非 leader（多实例只跑一次）
        redis.opsForValue().set(SchedulerLeaderService.KEY, "other-instance-id", Duration.ofSeconds(10));
        assertFalse(leader.tryAcquireOrRenew(), "他实例持租约 → 本实例非 leader（跳过，不双跑）");
        // 自恢复：clear 释放 → 本实例重新获取（leader 宕机/租约清空后接管）
        leader.clear();
        assertTrue(leader.tryAcquireOrRenew(), "clear 释放后本实例重新获取 leader（接管）");
        leader.clear();
    }
}
