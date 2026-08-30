package com.blms.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：解析 Authorization: Bearer <token>，
 * 有效则把 Operator 写入 SecurityContext（principal=Operator，等价前端 setOperator）；
 * 无效/缺失则保持匿名（RBAC 切面按默认拒绝拦截）。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Operator op = jwt.parse(header.substring(7));
            if (op != null && op.getUsername() != null && !op.getUsername().isBlank()) {
                var token = new UsernamePasswordAuthenticationToken(
                        op, null, List.of(new SimpleGrantedAuthority("ROLE_" + op.getRole())));
                SecurityContextHolder.getContext().setAuthentication(token);
            }
        }
        chain.doFilter(req, res);
    }
}
