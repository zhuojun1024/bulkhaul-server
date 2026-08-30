package com.blms.common;

import com.blms.auth.LoginLockoutService;
import com.blms.service.admin.DataScopeService;
import com.blms.store.DataStore;
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

    public SnapshotController(DataStore store, AuditLog audit, DataScopeService scope, LoginLockoutService lockout) {
        this.store = store;
        this.audit = audit;
        this.scope = scope;
        this.lockout = lockout;
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

    /** 重置演示数据：把内存数据仓库重置回启动时的种子态（等价前端旧架构 resetDb，供测试/演示跨场景恢复种子前置数据） */
    @PostMapping("/api/admin/reset-demo")
    public ApiResult<Map<String, Object>> resetDemo() {
        store.resetToSeed();
        lockout.clearAll(); // A2：自恢复——清空防爆破锁定，避免某账号被锁后影响后续场景登录
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reset", true);
        data.put("note", "内存数据仓库已重置回种子态、防爆破锁定已清空（仅内存/Redis，不回写 DB）");
        return ApiResult.success(data);
    }
}
