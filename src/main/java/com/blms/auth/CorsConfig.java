package com.blms.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * C5 CORS / 部署拓扑：Spring CORS 白名单（限定前端域名，跨域被正确限制）。
 * 主部署拓扑走 Nginx 反代（/api → 8081，同源，见 C1）；**反代会转发浏览器 Origin 同时改写 Host**，
 * 后端看到 Origin≠Host 即判跨域——若默认拒绝跨域会误伤同源反代（verify-ui 8086 代理 / 生产 Nginx 均中招，
 * 登录 POST 被 403 拦截）。
 * 故 CORS 采用**白名单显式启用（opt-in）**：
 *   - 默认空（allowed-origins 未配置）= **不做 CORS 处理**，请求直通——同源反代正常工作；跨域访问由浏览器同源策略
 *     限制（无 ACAO 头 → 浏览器拦截跨域读；认证端点 token 在 Authorization 头非 Cookie，跨域无法携带 → 401）。
 *   - 配置白名单（CORS_ALLOWED_ORIGINS，逗号分隔+通配）= **启用 CORS 强制**，仅白名单来源可跨域（纵深防御，
 *     用于前端与后端不同源部署的显式场景）。
 * 经 Spring Security 的 http.cors() 处理（预检 OPTIONS 先于认证，避免被 401 拦截）。
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${blms.cors.allowed-origins:}") String allowedOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = new ArrayList<>();
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            origins.addAll(Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        }
        // 默认空 = 不做 CORS 处理（同源反代直通；跨域由浏览器同源策略限制）
        if (origins.isEmpty()) {
            return source;
        }
        // 显式配置白名单 = 启用 CORS 强制（仅白名单来源可跨域，纵深防御）
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(origins); // 支持通配（*.example.com）
        cfg.setAllowCredentials(true);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/**", cfg);
        source.registerCorsConfiguration("/actuator/health", cfg);
        source.registerCorsConfiguration("/actuator/info", cfg);
        return source;
    }
}
