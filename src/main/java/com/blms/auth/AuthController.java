package com.blms.auth;

import com.blms.common.ApiResult;
import com.blms.common.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录/验证码（前端登录页契约：
 *   GET  /api/auth/captcha → { id, code, svg }
 *   POST /api/auth/login   { username, password, captchaId, captchaCode } → { ok, token, user } | { ok:false, error, code }
 *   GET  /api/auth/me      → 当前操作人（JWT 有效时）
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CaptchaService captcha;
    private final AuthService auth;

    public AuthController(CaptchaService captcha, AuthService auth) {
        this.captcha = captcha;
        this.auth = auth;
    }

    @GetMapping("/captcha")
    public ApiResult<CaptchaService.Captcha> captcha() {
        return ApiResult.success(captcha.generate());
    }

    @PostMapping("/login")
    public ApiResult<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest req) {
        AuthService.LoginResult r = auth.login(
                body.get("username"), body.get("password"),
                body.get("captchaId"), body.get("captchaCode"),
                AuditLog.clientIp(req));
        if (!r.ok()) return ApiResult.fail(r.error(), r.code());
        // 不返回密码哈希等敏感字段
        var user = r.user();
        Map<String, Object> safe = Map.of(
                "id", user.getId(), "username", user.getUsername(), "name", user.getName(),
                "role", user.getRole(), "customerId", user.getCustomerId() == null ? "" : user.getCustomerId(),
                "driverId", user.getDriverId() == null ? "" : user.getDriverId());
        return ApiResult.success(Map.of("token", r.token(), "user", safe));
    }

    @GetMapping("/me")
    public ApiResult<Operator> me() {
        Operator op = Operator.current();
        if (op.getUsername().isBlank()) return ApiResult.fail("未登录", "unauthenticated");
        return ApiResult.success(op);
    }
}
