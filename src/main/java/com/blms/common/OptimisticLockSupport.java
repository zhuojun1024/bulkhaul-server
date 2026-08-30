package com.blms.common;

import java.util.Map;

/**
 * B3 乐观锁：写端点登记"期望版本"的便捷入口（controller 调用）。
 * 从请求体读 expectedVersion（客户端最后看到的记录 version）+ 记录 id，登记到 OptimisticLockContext；
 * 缺省（未传 expectedVersion）→ 不登记 → 该写不参与乐观锁（保持既有行为，兼容旧客户端 / 直接 service 调用）。
 */
public final class OptimisticLockSupport {

    private OptimisticLockSupport() {
    }

    /** 登记：body 含 expectedVersion 且 id 有效时，记录本请求对 recordId 的期望版本。 */
    public static void expectFromBody(String recordId, Map<String, Object> body) {
        if (recordId == null || recordId.isEmpty() || body == null) return;
        Object v = body.get("expectedVersion");
        if (v instanceof Number n) {
            OptimisticLockContext.expect(recordId, n.intValue());
        } else if (v != null) {
            try {
                OptimisticLockContext.expect(recordId, Integer.parseInt(String.valueOf(v).trim()));
            } catch (NumberFormatException ignore) {
                // 非法版本号：不登记（不参与乐观锁），避免误伤
            }
        }
    }

    /** 登记：无请求体的状态流转端点用查询参数 ?expectedVersion=N（缺省 null → 不登记，兼容旧客户端）。 */
    public static void expectFromQuery(String recordId, Integer expectedVersion) {
        if (recordId == null || recordId.isEmpty() || expectedVersion == null) return;
        OptimisticLockContext.expect(recordId, expectedVersion);
    }
}