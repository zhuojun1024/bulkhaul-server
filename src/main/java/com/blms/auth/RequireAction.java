package com.blms.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 服务层权限守卫（等价前端 requireAction）：标注在 Service 方法上，
 * 无权限抛 ForbiddenException（由全局异常处理器转 403 + 审计失败日志）。
 * 前端按钮权限（usePerm）仅为体验层；本注解是"后端单点校验"，改 localStorage 也无法绕过。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAction {

    /** 操作码（与前端 permission-table.js 的 ACTION_OPTIONS 一致） */
    String value();

    /** 审计模块名（写 op_log 的 module 字段） */
    String module() default "";

    /** 审计动作名（写 op_log 的 action 字段；缺省用方法名） */
    String action() default "";
}
