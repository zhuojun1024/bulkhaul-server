package com.blms.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

/**
 * 验证码（等价前端 generateCaptcha/verifyCaptcha）：
 * 4 位（去易混淆字符 0/O/1/I），60 秒有效，一次性（校验即删除）。
 * 存储：Redis key=captcha:{id}，TTL 60s。
 * 前端契约：GET /api/auth/captcha 返回 { id, code, svg }（演示环境回传 code 供自动化测试；
 * 生产部署应去掉 code 字段，仅返回 svg）。
 */
@Service
public class CaptchaService {

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "captcha:";

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public CaptchaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Captcha(String id, String code, String svg) {}

    public Captcha generate() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 4; i++) code.append(CHARS.charAt(random.nextInt(CHARS.length())));
        String id = "CAP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
        redis.opsForValue().set(KEY_PREFIX + id, code.toString(), TTL);
        return new Captcha(id, code.toString(), svgOf(code.toString()));
    }

    /** 校验并消费（一次性；不存在/过期/不符均 false） */
    public boolean verify(String id, String input) {
        if (id == null) return false;
        String key = KEY_PREFIX + id;
        String stored = redis.opsForValue().get(key);
        if (stored == null) return false;
        redis.delete(key); // 一次性：无论对错都消费
        return stored.equals(String.valueOf(input == null ? "" : input).trim().toUpperCase());
    }

    /** 验证码 SVG（与前端 captchaSvgOf 同构：字符为 <text> 元素，e2e 可读） */
    private String svgOf(String code) {
        int width = 120, height = 40;
        String[] colors = {"#2b5ce6", "#0f9d58", "#d97706", "#dc2626", "#7c3aed"};
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
           .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ").append(width).append(' ').append(height).append("\">");
        svg.append("<rect width=\"").append(width).append("\" height=\"").append(height).append("\" rx=\"6\" fill=\"#f1f5f9\"/>");
        for (int i = 0; i < 3; i++) {
            svg.append("<line x1=\"").append(round(random.nextDouble() * width))
               .append("\" y1=\"").append(round(random.nextDouble() * height))
               .append("\" x2=\"").append(round(random.nextDouble() * width))
               .append("\" y2=\"").append(round(random.nextDouble() * height))
               .append("\" stroke=\"").append(colors[i % colors.length]).append("\" stroke-width=\"1\" opacity=\"0.35\"/>");
        }
        double step = width / (code.length() + 1);
        for (int i = 0; i < code.length(); i++) {
            double x = step * (i + 1) + (random.nextDouble() * 6 - 3);
            double y = height / 2 + (random.nextDouble() * 8 - 4);
            double rot = random.nextDouble() * 30 - 15;
            svg.append("<text x=\"").append(round(x)).append("\" y=\"").append(round(y))
               .append("\" font-size=\"22\" font-family=\"monospace\" fill=\"").append(colors[i % colors.length]).append("\"")
               .append(" transform=\"rotate(").append(round(rot)).append(' ').append(round(x)).append(' ').append(round(y)).append(")\">")
               .append(code.charAt(i)).append("</text>");
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private static String round(double d) {
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }
}
