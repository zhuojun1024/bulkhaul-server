package com.blms.auth;

import com.blms.store.DataStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RBAC 判定（等价前端 operatorCan）：
 * 判定顺序 sys_role_perm（数据化，角色管理页可编辑）→ 内置表 ROLE_ACTIONS → 默认拒绝。
 * actions: null=全部放行 / []=全拒绝 / [..]=列表匹配。
 * 权限表带 5 分钟内存缓存（角色管理页修改后调 /api/system/roles/refresh-perms 失效）。
 *
 * 菜单/操作判定（menuAllowed/actionAllowed）读 DataStore 的 rolePerms（与前端 permission.js 同口径）：
 * rolePerms[角色].menus/actions → 内置表 ROLE_MENUS/ROLE_ACTIONS → 默认拒绝。
 */
@Service
public class RbacService {

    /** 内置表（与前端 permission-table.js 的 ROLE_ACTIONS 一致，sys_role_perm 缺行时兜底） */
    public static final Map<String, List<String>> ROLE_ACTIONS = Map.of(
            "调度员", List.of("contract", "plan", "dispatch", "exception", "vehicle", "driver"),
            "结算专员", List.of("settlement", "invoice", "customer", "rate"),
            "场站操作员", List.of("dispatch", "weighing", "warehouse", "terminal", "warehouse-maint"),
            "安全管理员", List.of("dispatch", "exception", "safety", "insurance"),
            "客户", List.of("customer-confirm", "customer-request"),
            "只读用户", List.of(),
            "司机", List.of());
    // 平台管理员 = null（全部放行），单独处理

    /** 内置菜单表（与前端 permission-table.js 的 ROLE_MENUS 一致，rolePerms 缺角色时兜底）；null=全部菜单 */
    public static final Map<String, List<String>> ROLE_MENUS = Map.of(
            "调度员", List.of("/workbench", "/message", "/monitor", "/contract", "/plan", "/dispatch", "/track",
                    "/exception", "/vehicle", "/driver", "/terminal", "/terminal/weighing", "/document"),
            "结算专员", List.of("/workbench", "/message", "/monitor", "/contract", "/customer", "/settlement",
                    "/settlement/invoice", "/report", "/document"),
            "场站操作员", List.of("/workbench", "/message", "/dispatch", "/terminal", "/terminal/weighing",
                    "/warehouse", "/warehouse/inventory", "/document"),
            "安全管理员", List.of("/workbench", "/message", "/dispatch", "/exception", "/safety"),
            "客户", List.of("/workbench", "/portal", "/message"),
            "司机", List.of("/workbench"));
    // 平台管理员/只读用户 = null（全部菜单），单独处理

    private final JdbcTemplate jdbc;
    private final DataStore store;
    private final ObjectMapper om = new ObjectMapper();
    /** 角色 → 操作码列表；"全放行"角色（actions=NULL）单独放 allowAll（ConcurrentHashMap 不允许 null 值） */
    private final Map<String, List<String>> cache = new ConcurrentHashMap<>();
    private final Set<String> allowAll = ConcurrentHashMap.newKeySet();
    private volatile long cacheLoadedAt = 0;
    private static final long CACHE_TTL = 5 * 60_000;

    public RbacService(JdbcTemplate jdbc, DataStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    /**
     * 菜单级判定（等价前端 permission.js menuAllowed）：
     * rolePerms[角色].menus → 内置 ROLE_MENUS → 默认拒绝；null=全部菜单。
     */
    public boolean menuAllowed(String role, String path) {
        Object menus = resolveMenus(role);
        if (menus == null) return true;
        if (menus instanceof List<?> l) return l.contains(path);
        return false;
    }

    /**
     * 操作级判定（等价前端 permission.js actionAllowed）：
     * rolePerms[角色].actions → 内置 ROLE_ACTIONS → 默认拒绝；null=全部操作，[]=只读。
     */
    public boolean actionAllowed(String role, String action) {
        Object actions = resolveActionsData(role);
        if (actions == null) return true;
        if (actions instanceof List<?> l) return l.contains(action);
        return false;
    }

    /** 取角色菜单配置（rolePerms 优先，缺角色用内置表；未注册角色返回 undefined 语义→false） */
    private Object resolveMenus(String role) {
        if (role == null || role.isBlank()) return new Object(); // 空角色：未注册→默认拒绝
        if ("平台管理员".equals(role) || "只读用户".equals(role)) return null; // 内置 null=全部菜单
        Map<String, Object> rolePerms = store.obj("rolePerms");
        Object permObj = rolePerms.get(role);
        if (permObj instanceof Map) {
            Object m = ((Map<String, Object>) permObj).get("menus");
            if (m != null) return m; // rolePerms 有该角色（menus 可能为 null=全部 / List）
        }
        List<String> builtin = ROLE_MENUS.get(role);
        if (builtin != null) return builtin;
        return new Object(); // 未注册角色→默认拒绝（非 null 非 List）
    }

    /** 取角色操作配置（rolePerms 优先，缺角色用内置表；未注册角色默认拒绝） */
    private Object resolveActionsData(String role) {
        if (role == null || role.isBlank()) return new Object();
        if ("平台管理员".equals(role)) return null; // 内置 null=全部操作
        Map<String, Object> rolePerms = store.obj("rolePerms");
        Object permObj = rolePerms.get(role);
        if (permObj instanceof Map) {
            Object a = ((Map<String, Object>) permObj).get("actions");
            if (a != null) return a;
        }
        List<String> builtin = ROLE_ACTIONS.get(role);
        if (builtin != null) return builtin;
        return new Object(); // 未注册角色→默认拒绝
    }

    /** 角色是否有某操作权限；未登录（role 空）一律 false（默认拒绝） */
    public boolean can(String role, String action) {
        if (role == null || role.isBlank()) return false;
        if ("平台管理员".equals(role) || isAllowAll(role)) return true;
        List<String> actions = resolveActions(role);
        return actions != null && actions.contains(action);
    }

    private boolean isAllowAll(String role) {
        if (System.currentTimeMillis() - cacheLoadedAt > CACHE_TTL) reload();
        return allowAll.contains(role);
    }

    /** 列表（可能为空）；sys_role_perm 缺行时用内置表兜底；仍无则空列表（默认拒绝） */
    private List<String> resolveActions(String role) {
        if (System.currentTimeMillis() - cacheLoadedAt > CACHE_TTL) reload();
        if (cache.containsKey(role)) return cache.get(role);
        return ROLE_ACTIONS.getOrDefault(role, List.of());
    }

    private synchronized void reload() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role_name, actions FROM sys_role_perm");
        cache.clear();
        allowAll.clear();
        for (Map<String, Object> row : rows) {
            String role = (String) row.get("role_name");
            Object actionsRaw = row.get("actions");
            if (actionsRaw == null) {
                allowAll.add(role); // NULL=全部放行
            } else {
                try {
                    cache.put(role, om.readValue(String.valueOf(actionsRaw), new TypeReference<List<String>>() {}));
                } catch (Exception e) {
                    cache.put(role, List.of()); // 解析失败按拒绝
                }
            }
        }
        cacheLoadedAt = System.currentTimeMillis();
    }

    /** 角色管理页修改权限后调用，立即失效缓存 */
    public void invalidateCache() {
        cacheLoadedAt = 0;
    }
}
