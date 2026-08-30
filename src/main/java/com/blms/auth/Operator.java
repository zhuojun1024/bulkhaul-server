package com.blms.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前操作人（等价前端 flow.js 的 operator 对象）。
 * 由 JWT 认证过滤器解析后写入 SecurityContext，RBAC 切面与审计切面读取。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Operator {

    /** 未登录态（默认拒绝） */
    public static final Operator ANONYMOUS = new Operator("未登录", "", "", "");

    private String name;
    private String username;
    private String role;
    /** 司机账号绑定的司机档案 ID（司机端身份守卫用） */
    private String driverId;

    public static Operator current() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Operator op) return op;
        return ANONYMOUS;
    }
}
