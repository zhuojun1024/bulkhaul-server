package com.blms;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 大宗物流综合管理平台 - 后端服务入口
 *
 * 与前端 bulkhaul-manage-web 的对应关系：
 * - src/mock/flow.js 的 150 个函数 = 本服务的 API endpoint（1:1 映射）
 * - src/permission-table.js 的角色×操作码矩阵 = RBAC 权限数据
 * - src/mock/base.js 的 db 37 个集合 = 37 张表（Flyway V1__init.sql）
 * - scripts/verify-flow.mjs 的 554 条断言 = 后端集成测试验收标准
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.blms.domain.mapper")
public class BulkhaulServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BulkhaulServerApplication.class, args);
    }
}
