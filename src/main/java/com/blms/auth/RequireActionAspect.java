package com.blms.auth;

import com.blms.common.AuditLog;
import com.blms.common.ForbiddenException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * RBAC 切面：拦截 @RequireAction 标注的 Service 方法。
 * 无权限 → 记审计失败日志 + 抛 ForbiddenException（前端按钮权限仅为体验层，此处是单点校验）。
 */
@Aspect
@Component
public class RequireActionAspect {

    private final RbacService rbac;
    private final AuditLog audit;

    public RequireActionAspect(RbacService rbac, AuditLog audit) {
        this.rbac = rbac;
        this.audit = audit;
    }

    @Around("@annotation(com.blms.auth.RequireAction)")
    public Object guard(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RequireAction ra = method.getAnnotation(RequireAction.class);
        Operator op = Operator.current();
        String ip = currentIp();
        String module = ra.module().isBlank() ? "系统" : ra.module();
        String action = ra.action().isBlank() ? method.getName() : ra.action();
        if (!rbac.can(op.getRole(), ra.value())) {
            audit.log(module, action, "权限拦截：角色「" + (op.getRole().isBlank() ? "未登录" : op.getRole()) + "」无 " + ra.value() + " 权限", "fail", op, ip);
            throw new ForbiddenException("当前角色「" + (op.getRole().isBlank() ? "未登录" : op.getRole()) + "」无此操作权限，操作已被服务层拦截");
        }
        Object result = pjp.proceed();
        audit.log(module, action, "操作成功", "success", op, ip);
        return result;
    }

    private String currentIp() {
        try {
            var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                return AuditLog.clientIp(sra.getRequest());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
