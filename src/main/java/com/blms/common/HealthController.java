package com.blms.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查 + 冒烟接口（阶段 1 验证用）
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Integer dbOk = jdbc.queryForObject("SELECT 1", Integer.class);
        Integer tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()", Integer.class);
        return Map.of(
                "status", "UP",
                "db", dbOk == 1 ? "connected" : "error",
                "tables", tables
        );
    }
}
