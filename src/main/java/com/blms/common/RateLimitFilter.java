package com.blms.common;

import com.blms.auth.Operator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 全局限流过滤器（A3）：在 JwtAuthFilter 之后运行（SecurityContext 已填充，写档可按用户限流）。
 * 分档：
 *  - 登录档（/api/auth/login、/api/auth/captcha）：按 IP（严格，防刷登录/验证码）；
 *  - 写档（/api/** 的 POST/PUT/DELETE）：按用户（适度，防单账号高频写）；未认证按 IP 兜底；
 *  - GET 不限（读多写少，快照/列表高频轮询不受限）。
 * 排除：/api/scheduler/tick（前端每 3s 心跳，系统行为非用户写）+ /api/admin/reset-demo（自恢复端点）。
 * 超限 → 429 + Retry-After（秒）+ {ok:false, error, code:rate_limited}（前端 fetch 层不崩，仅该写失败）。
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String[] LOGIN_PATHS = {"/api/auth/login", "/api/auth/captcha"};
    private static final String[] EXCLUDED_PATHS = {"/api/scheduler/tick", "/api/admin/reset-demo"};

    private final RateLimitService rateLimit;
    private final ObjectMapper om;

    public RateLimitFilter(RateLimitService rateLimit, ObjectMapper om) {
        this.rateLimit = rateLimit;
        this.om = om;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        String tier = null;
        String dim = null;
        if (isOneOf(path, LOGIN_PATHS)) {
            tier = RateLimitService.TIER_LOGIN;
            dim = "ip:" + AuditLog.clientIp(req);
        } else if (!isOneOf(path, EXCLUDED_PATHS) && isWrite(req.getMethod())) {
            tier = RateLimitService.TIER_WRITE;
            dim = "user:" + currentUser(req);
        }
        if (tier != null) {
            Long retryAfter = rateLimit.tryAcquire(tier, dim);
            if (retryAfter != null) {
                res.setStatus(429); // 429 Too Many Requests（servlet API 无 SC_TOO_MANY_REQUESTS 常量）
                res.setHeader("Retry-After", String.valueOf(retryAfter));
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write(om.writeValueAsString(Map.of(
                        "ok", false, "error", "请求过于频繁，请稍后再试", "code", "rate_limited")));
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private static boolean isWrite(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private static boolean isOneOf(String path, String[] candidates) {
        for (String c : candidates) if (c.equals(path)) return true;
        return false;
    }

    /** 当前登录用户名（归一）；未认证（permitAll 端点）按 IP 兜底 */
    private static String currentUser(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Operator op && op.getUsername() != null) {
            return op.getUsername().trim().toLowerCase(java.util.Locale.ROOT);
        }
        return "ip:" + AuditLog.clientIp(req);
    }
}
