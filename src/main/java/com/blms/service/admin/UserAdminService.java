package com.blms.service.admin;

import com.blms.auth.RbacService;
import com.blms.domain.entity.SysUser;
import com.blms.domain.mapper.SysUserMapper;
import com.blms.store.FlowCtx;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 用户/角色/权限/数据范围管理（与前端 flow.js 系统管理域 1:1）。
 *
 * 双存储一致性：登录走 sys_user（BCrypt，AuthService），业务展示走 biz_users（SHA-256，前端口径）。
 * 用户增删改/重置密码/启停须同步两张表，保证"能登录"与"列表展示"一致。
 * 角色权限走 sys_role_perm（RbacService 带 5min 缓存，改后 invalidateCache）+ biz_roles 展示 + rolePerms 对象。
 */
@Service
public class UserAdminService {

    private final FlowCtx ctx;
    private final SysUserMapper userMapper;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final RbacService rbac;

    public UserAdminService(FlowCtx ctx, SysUserMapper userMapper, PasswordEncoder encoder,
                            JdbcTemplate jdbc, RbacService rbac) {
        this.ctx = ctx;
        this.userMapper = userMapper;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.rbac = rbac;
    }

    private void commit() { ctx.store().commitAll(); }

    /* ================= 用户 ================= */
    public Map<String, Object> saveUser(Map<String, Object> p) {
        ctx.requireAction("user");
        String username = str(p, "username").trim();
        String name = str(p, "name").trim();
        if (name.isEmpty()) return err("请填写姓名");
        if (username.isEmpty()) return err("请填写登录账号");
        List<Map<String, Object>> users = ctx.store().list("users");
        if (p.get("id") != null) {
            Map<String, Object> u = ctx.byId("users", str(p, "id"));
            if (u == null) return err("用户不存在");
            u.put("name", name);
            if (p.get("role") != null) u.put("role", p.get("role"));
            if (p.get("phone") != null) u.put("phone", p.get("phone"));
            if (p.get("email") != null) u.put("email", p.get("email"));
            syncSysUser(u);
            ctx.logAction("系统管理", "编辑用户", "用户 " + u.get("username") + " 信息更新", "success");
            commit();
            return ok(u.get("id"));
        }
        if (users.stream().anyMatch(x -> username.equals(x.get("username")))) return err("账号 " + username + " 已存在，请更换登录账号");
        String plain = p.get("password") == null ? "" : String.valueOf(p.get("password"));
        if (plain.isEmpty()) return err("请设置登录密码");
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", ctx.genId("U", 3, users));
        u.put("username", username);
        u.put("name", name);
        u.put("passwordHash", sha256("blms:" + plain));
        u.put("role", p.get("role") != null ? p.get("role") : "调度员");
        u.put("phone", p.get("phone") != null ? p.get("phone") : "-");
        u.put("email", p.get("email") != null ? p.get("email") : "-");
        u.put("status", "active");
        u.put("lastLogin", "-");
        u.put("createdAt", ctx.today());
        users.add(u);
        upsertSysUser(u, plain);
        ctx.logAction("系统管理", "新增用户", "新增用户 " + u.get("username") + "（" + u.get("role") + "）", "success");
        commit();
        return ok(u.get("id"));
    }

    public Map<String, Object> removeUser(String id) {
        ctx.requireAction("user");
        Map<String, Object> u = ctx.byId("users", id);
        if (u == null) return err("用户不存在");
        if (ctx.op().getUsername().equals(u.get("username"))) return err("不能删除当前登录账号");
        ctx.store().list("users").removeIf(x -> id.equals(x.get("id")));
        userMapper.delete(new LambdaQueryWrapper<SysUser>().eq(SysUser::getId, id));
        ctx.logAction("系统管理", "删除用户", "删除用户 " + u.get("username"), "success");
        commit();
        return ok(null);
    }

    public Map<String, Object> toggleUserStatus(String id, boolean active) {
        ctx.requireAction("user");
        Map<String, Object> u = ctx.byId("users", id);
        if (u == null) return err("用户不存在");
        if (ctx.op().getUsername().equals(u.get("username")) && !active) return err("不能停用当前登录账号");
        u.put("status", active ? "active" : "disabled");
        syncSysUser(u);
        ctx.logAction("系统管理", active ? "启用用户" : "停用用户", "用户 " + u.get("username") + (active ? " 启用" : " 停用"), "success");
        commit();
        return ok(null);
    }

    public Map<String, Object> resetPassword(String id, String newPassword) {
        ctx.requireAction("user");
        Map<String, Object> u = ctx.byId("users", id);
        if (u == null) return err("用户不存在");
        String pw = newPassword == null ? "" : newPassword;
        if (pw.isEmpty()) return err("请设置新密码");
        if (pw.length() < 6) return err("密码至少 6 位");
        u.put("passwordHash", sha256("blms:" + pw));
        syncPasswordToSysUser(String.valueOf(u.get("id")), pw);
        ctx.logAction("系统管理", "重置密码", "管理员重置用户 " + u.get("username") + " 的登录密码", "success");
        if ("active".equals(u.get("status"))) {
            ctx.notify("登录密码已重置", "system", "/login",
                    "您的账号 " + u.get("username") + " 登录密码已被管理员重置，请使用新密码登录", List.of(str(u, "role")));
        }
        commit();
        return ok(null);
    }

    /* ================= 角色 ================= */
    public Map<String, Object> saveRole(Map<String, Object> p) {
        ctx.requireAction("role");
        String name = str(p, "name").trim();
        String code = str(p, "code").trim();
        if (name.isEmpty() || code.isEmpty()) return err("请填写角色名称和编码");
        List<Map<String, Object>> roles = ctx.store().list("roles");
        if (roles.stream().anyMatch(r -> name.equals(r.get("name")) || code.equals(r.get("code"))))
            return err("角色名称或编码已存在，请更换");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", ctx.genId("R", 3, roles));
        r.put("name", name);
        r.put("code", code);
        r.put("userCount", 0);
        r.put("description", p.get("description") != null ? p.get("description") : "—");
        r.put("builtIn", false);
        roles.add(r);
        Map<String, Object> rolePerms = ctx.store().obj("rolePerms");
        rolePerms.put(name, Map.of("menus", List.of(), "actions", List.of()));
        ctx.logAction("系统管理", "新增角色", "新增角色 " + name + "（" + code + "），默认无权限", "success");
        commit();
        return ok(r.get("id"));
    }

    public Map<String, Object> removeRole(String id) {
        ctx.requireAction("role");
        Map<String, Object> role = ctx.byId("roles", id);
        if (role == null) return err("角色不存在");
        if (Boolean.TRUE.equals(role.get("builtIn"))) return err("内置角色 " + role.get("name") + " 不可删除");
        long count = ctx.store().list("users").stream().filter(u -> role.get("name").equals(u.get("role"))).count();
        if (count > 0) return err("角色下还有 " + count + " 名用户，无法删除");
        ctx.store().list("roles").removeIf(r -> id.equals(r.get("id")));
        ctx.store().obj("rolePerms").remove(role.get("name"));
        jdbc.update("DELETE FROM sys_role_perm WHERE role_name = ?", role.get("name"));
        rbac.invalidateCache();
        ctx.logAction("系统管理", "删除角色", "删除角色 " + role.get("name"), "success");
        commit();
        return ok(null);
    }

    public Map<String, Object> updateRolePerms(String roleName, Map<String, Object> perm) {
        ctx.requireAction("role");
        Map<String, Object> rolePerms = ctx.store().obj("rolePerms");
        rolePerms.put(roleName, perm);
        // 同步 sys_role_perm（RbacService 实际读取源），menus/actions null=全部
        Object menus = perm.get("menus");
        Object actions = perm.get("actions");
        String menusJson = menus == null ? null : toJson(menus);
        String actionsJson = actions == null ? null : toJson(actions);
        jdbc.update("INSERT INTO sys_role_perm (role_name, menus, actions) VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE menus = VALUES(menus), actions = VALUES(actions)",
                roleName, menusJson, actionsJson);
        rbac.invalidateCache();
        String desc = (menus == null && actions == null) ? "全部权限"
                : "菜单 " + sizeOf(menus) + " 项、操作 " + sizeOf(actions) + " 项";
        ctx.logAction("系统管理", "角色权限更新", "角色 " + roleName + " 权限更新：" + desc, "success");
        commit();
        return ok(null);
    }

    /* ================= 数据范围 / 免打扰 ================= */
    public Map<String, Object> setDataScope(String username, List<String> regions) {
        ctx.requireAction("user");
        Map<String, Object> u = ctx.store().list("users").stream().filter(x -> username.equals(x.get("username"))).findFirst().orElse(null);
        if (u == null) return err("账号 " + username + " 不存在");
        if ("admin".equals(username)) return err("平台管理员为全量数据，不可设置数据范围");
        if (regions == null) return err("数据范围须为区域数组");
        List<String> valid = dataRegions();
        List<String> list = new ArrayList<>();
        for (String r : regions) if (valid.contains(r)) list.add(r);
        if (list.size() != regions.size()) return err("包含无效区域");
        Map<String, Object> dataScopes = ctx.store().obj("dataScopes");
        if (!list.isEmpty()) dataScopes.put(username, Map.of("regions", list));
        else dataScopes.remove(username);
        ctx.logAction("系统管理", "设置数据范围", "账号 " + username + " 数据范围：" + (list.isEmpty() ? "全量数据" : String.join("、", list)), "success");
        commit();
        return ok(null);
    }

    public Map<String, Object> setDnd(Map<String, Object> settings) {
        String username = ctx.op().getUsername();
        if (username == null || username.isBlank()) return err("未登录，无法保存免打扰设置");
        Map<String, Object> dnd = ctx.store().obj("dnd");
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("enabled", Boolean.TRUE.equals(settings.get("enabled")));
        rec.put("quietStart", settings.get("quietStart") != null ? settings.get("quietStart") : "22:00");
        rec.put("quietEnd", settings.get("quietEnd") != null ? settings.get("quietEnd") : "08:00");
        rec.put("mutedTypes", settings.get("mutedTypes") instanceof List<?> l ? l : List.of());
        dnd.put(username, rec);
        commit();
        return ok(null);
    }

    /* ================= 读取（数据范围/消息） ================= */
    public Map<String, Object> dataScopeOf() {
        if ("平台管理员".equals(ctx.op().getRole())) return Map.of("regions", List.of());
        Map<String, Object> dataScopes = ctx.store().obj("dataScopes");
        Object scope = dataScopes.get(ctx.op().getUsername());
        if (scope instanceof Map<?, ?> m && m.get("regions") instanceof List<?> l) return Map.of("regions", l);
        return Map.of("regions", List.of());
    }

    public Map<String, Object> getDnd() {
        Map<String, Object> dnd = ctx.store().obj("dnd");
        Object d = dnd.get(ctx.op().getUsername());
        if (d instanceof Map<?, ?> m) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("enabled", m.get("enabled"));
            r.put("quietStart", m.get("quietStart"));
            r.put("quietEnd", m.get("quietEnd"));
            r.put("mutedTypes", m.get("mutedTypes") instanceof List<?> l ? l : List.of());
            return r;
        }
        return Map.of("enabled", false, "quietStart", "22:00", "quietEnd", "08:00", "mutedTypes", List.of());
    }

    public List<Map<String, Object>> visibleMessages() {
        if ("平台管理员".equals(ctx.op().getRole())) return new ArrayList<>(ctx.store().list("messages"));
        List<Map<String, Object>> res = new ArrayList<>();
        for (Map<String, Object> m : ctx.store().list("messages")) {
            Object to = m.get("to");
            if (to == null || (to instanceof List<?> l && l.contains(ctx.op().getRole()))) res.add(m);
        }
        return res;
    }

    public int unreadCount() {
        int n = 0;
        for (Map<String, Object> m : visibleMessages()) {
            if (Boolean.FALSE.equals(m.get("read")) && !isMuted(m)) n++;
        }
        return n;
    }

    public int markAllMessagesRead() {
        int n = 0;
        for (Map<String, Object> m : visibleMessages()) {
            if (Boolean.FALSE.equals(m.get("read"))) { m.put("read", true); n++; }
        }
        commit();
        return n;
    }

    public Map<String, Object> markMessageRead(String id) {
        Map<String, Object> m = ctx.byId("messages", id);
        if (m != null && Boolean.FALSE.equals(m.get("read"))) {
            Object to = m.get("to");
            if (to == null || (to instanceof List<?> l && l.contains(ctx.op().getRole())) || "平台管理员".equals(ctx.op().getRole())) {
                m.put("read", true);
                commit();
            }
        }
        return ok(null);
    }

    private boolean isMuted(Map<String, Object> m) {
        Map<String, Object> d = getDnd();
        if (!Boolean.TRUE.equals(d.get("enabled"))) return false;
        if (d.get("mutedTypes") instanceof List<?> l && l.contains(m.get("type"))) return true;
        String t = str(m, "time");
        if (t.length() < 16) return false;
        String hm = t.substring(11, 16);
        String start = str(d, "quietStart"), end = str(d, "quietEnd");
        return start.compareTo(end) <= 0 ? (hm.compareTo(start) >= 0 && hm.compareTo(end) < 0)
                : (hm.compareTo(start) >= 0 || hm.compareTo(end) < 0);
    }

    /* ================= 双存储同步 ================= */
    /** biz_users → sys_user（按 id 更新非密码字段；BCrypt 哈希仅在密码重置时单独同步） */
    private void syncSysUser(Map<String, Object> u) {
        SysUser s = userMapper.selectById(String.valueOf(u.get("id")));
        if (s == null) return; // 仅 biz 侧存在（演示数据）；登录不受影响
        s.setName(str(u, "name"));
        s.setRole(str(u, "role"));
        s.setPhone(str(u, "phone"));
        s.setEmail(str(u, "email"));
        s.setStatus(str(u, "status"));
        userMapper.updateById(s);
    }

    private void upsertSysUser(Map<String, Object> u, String plainPassword) {
        SysUser s = new SysUser();
        s.setId(String.valueOf(u.get("id")));
        s.setUsername(str(u, "username"));
        s.setName(str(u, "name"));
        s.setRole(str(u, "role"));
        s.setPasswordHash(encoder.encode(plainPassword));
        s.setPhone(str(u, "phone"));
        s.setEmail(str(u, "email"));
        s.setStatus(str(u, "status"));
        s.setCreatedAt(LocalDate.parse(str(u, "createdAt")));
        userMapper.insert(s);
    }

    /** resetPassword 专用：biz 侧 sha256 + sys 侧 BCrypt 用同一明文 */
    public void syncPasswordToSysUser(String id, String plainPassword) {
        SysUser s = userMapper.selectById(id);
        if (s != null) {
            s.setPasswordHash(encoder.encode(plainPassword));
            userMapper.updateById(s);
        }
    }

    private List<String> dataRegions() {
        Set<String> s = new LinkedHashSet<>();
        for (Map<String, Object> t : ctx.store().list("terminals")) if (t.get("region") != null) s.add(String.valueOf(t.get("region")));
        return new ArrayList<>(s);
    }

    private static int sizeOf(Object o) { return o instanceof List<?> l ? l.size() : 0; }

    private static String toJson(Object o) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o); }
        catch (Exception e) { return "[]"; }
    }

    private static String sha256(String s) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Map<String, Object> ok(Object id) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        if (id != null) r.put("id", id);
        return r;
    }
    private static Map<String, Object> err(String e) { return Map.of("error", e); }
    private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? "" : String.valueOf(v); }
}
