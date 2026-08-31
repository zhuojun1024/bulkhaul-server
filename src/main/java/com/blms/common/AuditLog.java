package com.blms.common;

import com.blms.auth.Operator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 审计日志（等价前端 logAction）：写操作实时落 op_log 表，含失败记录。
 * ID 生成复刻前端 genId 语义：前缀 + 5 位序列，删除不复用（每进程内存自增 + 启动时取库内最大值）。
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_LOGS = 1000;

    private final JdbcTemplate jdbc;
    private final AtomicInteger seq = new AtomicInteger(0);

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        resyncSeq(); // 启动时取库内 op_log 最大值初始化 seq
    }

    /**
     * 从库内 op_log 最大值重同步 seq（只升不降）。
     * 多 Spring 上下文（各持一个 AuditLog bean）共享 op_log 时，先建上下文的 bean seq 可能落后于库内最大值
     * → incrementAndGet 生成的 id 与后建上下文已写入的行主键冲突（DuplicateKeyException）。
     * 冲突时调用本方法对齐到最新，再重试（见 log 的重试循环），使审计 ID 生成与上下文创建顺序无关。
     */
    private void resyncSeq() {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(SUBSTRING(id, 5) AS UNSIGNED)), 0) FROM op_log", Integer.class);
        if (max != null && max > seq.get()) seq.set(max);
    }

    /** 写审计日志；operator 为 null 时按"未登录"记录（默认拒绝语义下不应出现写成功，但失败日志可能来自匿名态） */
    public void log(String module, String action, String detail, String result, Operator operator, String ip) {
        String user = operator != null ? operator.getName() : "未登录";
        String username = operator != null ? operator.getUsername() : "";
        String time = LocalDateTime.now().format(FMT);
        // ID 生成：内存自增；多 Spring 上下文共享 op_log 时 seq 可能落后 → 主键冲突，
        // 冲突时从库内最大值重同步并重试（有界），与上下文创建顺序无关。
        int retries = 0;
        while (true) {
            String id = "LOG-" + String.format("%05d", seq.incrementAndGet());
            try {
                jdbc.update(
                        "INSERT INTO op_log (id, time, user, username, action, module, detail, ip, result) VALUES (?,?,?,?,?,?,?,?,?)",
                        id, time, user, username, action, module,
                        detail == null ? "" : detail, ip == null ? "" : ip, result);
                break;
            } catch (DuplicateKeyException e) {
                if (++retries > 5) throw e; // 有界重试：避免极端并发下死循环
                resyncSeq();
            }
        }
        // 与前端一致：超上限裁剪最旧
        jdbc.update("DELETE FROM op_log WHERE id NOT IN (SELECT id FROM (SELECT id FROM op_log ORDER BY time DESC, id DESC LIMIT " + MAX_LOGS + ") t)");
        log.debug("audit: [{}] {} {} result={}", username, module, action, result);
    }

    /** 当前请求 IP（X-Forwarded-For 优先） */
    public static String clientIp(HttpServletRequest req) {
        if (req == null) return "";
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    /**
     * 查询最近审计日志（等价前端 db.logs，时间倒序，新日志在最前）。
     * 供集成测试断言审计写入（环节 6/9）与日志倒序校验。
     */
    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList(
                "SELECT id, time, user, username, action, module, detail, result FROM op_log ORDER BY time DESC, id DESC LIMIT ?",
                limit);
    }
}
