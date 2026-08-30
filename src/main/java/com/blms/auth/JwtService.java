package com.blms.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与解析（HS256）。
 * claims：sub=username, name, role, driverId。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(@Value("${blms.jwt.secret}") String secret,
                      @Value("${blms.jwt.ttl-minutes}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = ttlMinutes * 60_000;
    }

    public String issue(Operator op) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(op.getUsername())
                .claim("name", op.getName())
                .claim("role", op.getRole())
                .claim("driverId", op.getDriverId())
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key)
                .compact();
    }

    /** 解析并校验；失败返回 null（由过滤器按未登录处理） */
    public Operator parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return new Operator(
                    c.get("name", String.class),
                    c.getSubject(),
                    c.get("role", String.class),
                    c.getOrDefault("driverId", "") == null ? "" : c.get("driverId", String.class));
        } catch (Exception e) {
            return null;
        }
    }
}
