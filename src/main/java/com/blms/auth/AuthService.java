package com.blms.auth;

import com.blms.common.AuditLog;
import com.blms.domain.entity.SysUser;
import com.blms.domain.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 登录（等价前端 flow.js login）：验证码 + bcrypt 密码 + 账号状态。
 * 成功/凭据失败/停用均记审计日志；会话建立由 JWT 承接（等价 setOperator）。
 */
@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder encoder;
    private final CaptchaService captcha;
    private final JwtService jwt;
    private final AuditLog audit;
    private final JdbcTemplate jdbc;

    public AuthService(SysUserMapper userMapper, PasswordEncoder encoder, CaptchaService captcha,
                       JwtService jwt, AuditLog audit, JdbcTemplate jdbc) {
        this.userMapper = userMapper;
        this.encoder = encoder;
        this.captcha = captcha;
        this.jwt = jwt;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public record LoginResult(boolean ok, String token, SysUser user, String error, String code) {}

    public LoginResult login(String username, String password, String captchaId, String captchaCode, String ip) {
        String id = String.valueOf(username == null ? "" : username).trim();
        if (!captcha.verify(captchaId, captchaCode)) {
            return new LoginResult(false, null, null, "验证码错误或已过期", "captcha");
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, id).or().eq(SysUser::getPhone, id).last("LIMIT 1"));
        if (user == null || !encoder.matches(password == null ? "" : password, user.getPasswordHash())) {
            audit.log("系统", "登录系统", "账号 " + id + " 登录失败（用户名或密码错误）", "fail", null, ip);
            return new LoginResult(false, null, null, "用户名或密码错误", "credential");
        }
        if (!"active".equals(user.getStatus())) {
            audit.log("系统", "登录系统", "账号 " + user.getUsername() + " 登录失败（账号已停用）", "fail", null, ip);
            return new LoginResult(false, null, null, "账号已停用，请联系管理员", "disabled");
        }
        Operator op = new Operator(user.getName(), user.getUsername(), user.getRole(),
                user.getDriverId() == null ? "" : user.getDriverId());
        String token = jwt.issue(op);
        // 更新 last_login（与前端 userStore.login 行为一致）
        jdbc.update("UPDATE sys_user SET last_login = ? WHERE id = ?", LocalDateTime.now(), user.getId());
        audit.log("系统", "登录系统", "账号 " + user.getUsername() + "（" + user.getRole() + "）登录成功", "success", op, ip);
        return new LoginResult(true, token, user, null, null);
    }
}
