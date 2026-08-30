package com.blms.auth;

import com.blms.common.RateLimitFilter;
import com.blms.common.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * 安全配置：STATELESS + JWT 过滤器（认证）+ RBAC 切面（授权，RequireActionAspect）。
 * /api/health 与 /api/auth/** 放行；其余接口要求已认证（401 JSON）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RateLimitService rateLimit;
    private final ObjectMapper om;

    /** D1 OpenAPI/Swagger 公开开关：dev 公开（联调看文档），生产需认证（不暴露 API 面，见 application-prod.yml） */
    @Value("${blms.openapi.public:true}")
    private boolean openApiPublic;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, RateLimitService rateLimit, ObjectMapper om) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.rateLimit = rateLimit;
        this.om = om;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                // C5：CORS 白名单（预检先于认证；默认拒绝跨域，见 CorsConfig）
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/health", "/api/auth/captcha", "/api/auth/login",
                            "/actuator/health", "/actuator/info").permitAll();
                    // D1 OpenAPI/Swagger：dev 公开（联调看文档），生产需认证（不暴露 API 面）
                    if (openApiPublic) {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write(om.writeValueAsString(java.util.Map.of(
                            "ok", false, "error", "未登录或登录已过期", "code", "unauthenticated")));
                }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // A3 全局限流：在 JWT 认证之后（写档可按用户限流），未认证按 IP 兜底
                .addFilterAfter(new RateLimitFilter(rateLimit, om), JwtAuthFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
