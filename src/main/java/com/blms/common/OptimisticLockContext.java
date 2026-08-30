package com.blms.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B3 乐观锁：请求级"期望版本"上下文（ThreadLocal，等价 Operator.current() 读 SecurityContext 的模式）。
 *
 * 写端点（controller）在调用 service 前登记：本请求要改的记录 id + 客户端最后看到的 version。
 * DataStore.commitAll() 在写锁内 drain 本上下文：逐条比对当前 version，不匹配抛 OptimisticLockException（→ 409），
 * 匹配则 version+1（乐观锁写标记）后随 payload 持久化。
 *
 * 未登记（直接 service 调用 / 定时任务 / 未参与乐观锁的写）→ drain 为空 → no-op，保持既有"最后写入胜出"行为。
 * 每次 commitAll() 都 drain（remove），线程复用不残留脏值。
 */
public final class OptimisticLockContext {

    private static final ThreadLocal<Map<String, Integer>> EXPECTED =
            ThreadLocal.withInitial(LinkedHashMap::new);

    private OptimisticLockContext() {
    }

    /** 登记：本请求将修改记录 recordId，客户端最后看到的版本为 version。 */
    public static void expect(String recordId, int version) {
        if (recordId == null || recordId.isEmpty()) return;
        EXPECTED.get().put(recordId, version);
    }

    /** 取出并清空本请求的全部期望版本（commitAll 写锁内调用）。 */
    public static Map<String, Integer> drain() {
        Map<String, Integer> m = new LinkedHashMap<>(EXPECTED.get());
        EXPECTED.remove();
        return m;
    }

    /** 本请求是否登记了期望版本。 */
    public static boolean hasExpectation() {
        return !EXPECTED.get().isEmpty();
    }

    /** 强制清空（请求收尾兜底，防线程复用残留）。 */
    public static void clear() {
        EXPECTED.remove();
    }
}