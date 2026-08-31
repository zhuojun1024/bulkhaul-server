package com.blms.common;

import com.blms.auth.LoginLockoutService;
import com.blms.service.admin.DataScopeService;
import com.blms.service.scheduler.SchedulerLeaderService;
import com.blms.store.DataStore;
import com.blms.store.FlowCtx;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端联调快照端点（阶段 6 收尾）：
 *   GET /api/snapshot → 34 个集合（29 数组型 + 5 对象型）+ logs（op_log 审计，最近 1000，时间倒序）。
 * 前端 mock 层用它 hydrate/refresh 本地响应式 db（后端为权威态）。需 JWT 认证（非 permitAll）。
 * 行级数据范围（A1）：dispatches/plans/contracts/transportRequests/settlements/weighings 按当前操作人
 * 装货侧区域过滤（regions 为空=全量）；其余集合与 logs 不过滤（customers.region 为省份非数据区域，
 * logs/messages 为菜单/角色门控）。
 *   POST /api/admin/reset-demo → 把内存数据仓库重置回启动时的种子态（演示/测试用：跨场景恢复种子前置数据）。
 */
@RestController
public class SnapshotController {

    private final DataStore store;
    private final AuditLog audit;
    private final DataScopeService scope;
    private final LoginLockoutService lockout;
    private final RateLimitService rateLimit;
    private final SchedulerLeaderService leader;
    private final FlowCtx ctx;

    public SnapshotController(DataStore store, AuditLog audit, DataScopeService scope,
                              LoginLockoutService lockout, RateLimitService rateLimit,
                              SchedulerLeaderService leader, FlowCtx ctx) {
        this.store = store;
        this.audit = audit;
        this.scope = scope;
        this.lockout = lockout;
        this.rateLimit = rateLimit;
        this.leader = leader;
        this.ctx = ctx;
    }

    @GetMapping("/api/snapshot")
    public ApiResult<Map<String, Object>> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String coll : DataStore.LIST_COLLS) {
            data.put(coll, DataScopeService.REGION_SCOPED.contains(coll)
                    ? scope.filter(coll, store.list(coll))
                    : store.list(coll));
        }
        for (String coll : DataStore.OBJ_COLLS) {
            data.put(coll, store.obj(coll));
        }
        data.put("logs", audit.recent(1000));
        return ApiResult.success(data);
    }

    @GetMapping("/api/logs")
    public ApiResult<List<Map<String, Object>>> logs() {
        return ApiResult.success(audit.recent(1000));
    }

    /**
     * 重置演示数据（Phase 4 决策 2：持久化恢复演示数据版本）：
     *   1. RBAC 单点校验：仅平台管理员（actions=null 全放行）可触发，其余角色 403（前端按钮权限仅体验层）。
     *   2. 内存数据仓库重置回种子态（seed_* 只读快照基线，不受 commitAll 污染）。
     *   3. commitAll() 把种子态回写 biz_*（薄客户端化后 DB 为权威——只重置内存则重启后脏数据复活；
     *      回写后重启仍为种子态，演示数据版本持久化）。
     *   4. 自恢复：清空防爆破锁定（A2）/限流计数（A3）/定时任务 leader 租约（C4），避免残留影响后续场景。
     * 供测试/演示跨场景恢复种子前置数据（等价前端旧架构 resetDb）。
     */
    @PostMapping("/api/admin/reset-demo")
    public ApiResult<Map<String, Object>> resetDemo() {
        ctx.requireAction("admin"); // RBAC 单点校验：仅平台管理员可重置（无权限 → ForbiddenException → 403）
        store.resetToSeed();
        store.commitAll(); // 持久化：种子态回写 biz_*（薄客户端化后 DB 权威，重启不复活脏数据）
        lockout.clearAll(); // A2：自恢复——清空防爆破锁定，避免某账号被锁后影响后续场景登录
        rateLimit.clearAll(); // A3：自恢复——清空限流计数，避免某 IP/账号被限后影响后续场景
        leader.clear(); // C4：自恢复——清空定时任务 leader 租约，避免旧租约残留影响后续接管
        audit.log("系统", "reset-demo", "重置演示数据：内存数据仓库恢复种子态并回写 biz_*，防爆破/限流/leader 租约已清空", "success", ctx.op(), null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reset", true);
        data.put("note", "数据仓库已重置回种子态并回写 DB（持久化）、防爆破锁定/限流计数/定时任务 leader 租约已清空");
        return ApiResult.success(data);
    }
}
