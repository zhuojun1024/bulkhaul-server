package com.blms.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D1 OpenAPI/Swagger：springdoc 全局配置（/v3/api-docs + Swagger UI）。
 * 机器可读契约——118 端点的 schema 自动生成，前端可据此生成类型；关键端点补 @Operation/@Schema（见各 Controller）。
 * 安全：全局 JWT Bearer（POST /api/auth/login 获取 token）；dev 公开可访问，生产需认证（blms.openapi.public，见 SecurityConfig）。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bulkhaulOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("bulkhaul-server API")
                        .version("0.1.0")
                        .description("大宗物流综合管理平台 - 后端服务（与前端 bulkhaul-manage-web 的 mock 服务层 1:1 对应）。"
                                + "所有接口返回统一包裹 { ok, data | error, code }；写端点需 JWT 认证（Authorization: Bearer <token>），"
                                + "行级数据范围按操作人区域过滤（A1），写端点带乐观锁 expectedVersion（B3，冲突 409）。")
                        .contact(new Contact().name("blms")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().name("bearer").type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")
                                .description("JWT Bearer Token（POST /api/auth/login 获取，480min 有效）")));
    }
}
