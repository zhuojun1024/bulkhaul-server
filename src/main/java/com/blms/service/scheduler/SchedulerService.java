package com.blms.service.scheduler;

import com.blms.service.exception.ExceptionService;
import com.blms.service.settlement.SettlementService;
import com.blms.store.FlowCtx;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后端定时任务（与前端 scheduler.js 的 runSchedulerTick 1:1，由 cron 驱动，不依赖页面打开）。
 * 单轮：advanceTelemetry → checkFenceEvents → recalcOverdueAll → escalatePendingExceptions → escalateContractApprovals。
 * 系统任务口径：不做登录用户权限校验（与前端"系统事件走内部核心"一致）。
 */
@Service
public class SchedulerService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final FlowCtx ctx;
    private final ExceptionService exceptionService;
    private final SettlementService settlementService;

    /** 自动轮询开关（验证时置 false，避免后台任务干扰端到端断言；手动 /api/scheduler/tick 不受限） */
    @Value("${blms.scheduler.auto-enabled:true}")
    private boolean autoEnabled;

    public SchedulerService(FlowCtx ctx, ExceptionService exceptionService, SettlementService settlementService) {
        this.ctx = ctx;
        this.exceptionService = exceptionService;
        this.settlementService = settlementService;
    }

    /** GPS/遥测推进：在途车次进度与车速（等价 advanceTelemetry） */
    public void advanceTelemetry() {
        for (Map<String, Object> d : ctx.store().list("dispatches")) {
            if ("intransit".equals(d.get("status"))) {
                d.put("progress", Math.min(95, FlowCtx.num(d.get("progress")) + Math.random() * 0.9));
                d.put("speed", Math.max(35, Math.min(75, FlowCtx.num(d.get("speed")) + (Math.random() - 0.5) * 8)));
            }
        }
    }

    /** 围栏事件检查：在途车次 轨迹偏离超阈值→偏离异常 / 超 ETA 超阈值→延误异常，自动写异常单（等价 checkFenceEvents） */
    public List<Map<String, Object>> checkFenceEvents() {
        Map<String, Object> cfg = ctx.store().obj("fenceConfig");
        if (cfg == null || !FlowCtx.bool(cfg.get("enabled"))) return new ArrayList<>();
        double deviateLimit = FlowCtx.num(cfg.get("deviateLimit"));
        int delayMinutes = FlowCtx.intNum(cfg.get("delayMinutes"));
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> d : ctx.store().list("dispatches")) {
            if (!"intransit".equals(d.get("status"))) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> alerted = (Map<String, Object>) d.get("fenceAlerted");
            if (alerted == null) { alerted = new LinkedHashMap<>(); d.put("fenceAlerted", alerted); }
            int dev = ctx.maxDeviationOf(d);
            if (!FlowCtx.bool(alerted.get("deviate")) && dev > deviateLimit) {
                alerted.put("deviate", true);
                Map<String, Object> e = exceptionService.createException(d,
                        "围栏预警：轨迹偏离线路 " + dev + " 个地图单位（阈值 " + deviateLimit + "）", "other", "medium", "fence");
                if (e.get("id") != null) created.add(e);
            } else if (!FlowCtx.bool(alerted.get("delay")) && d.get("eta") != null && isBeforeByMinutes(str(d, "eta"), delayMinutes)) {
                alerted.put("delay", true);
                int overMin = minutesBetween(str(d, "eta"), ctx.now());
                Map<String, Object> e = exceptionService.createException(d,
                        "围栏预警：超预计到达时间 " + overMin + " 分钟（阈值 " + delayMinutes + " 分钟）", "delay", "medium", "fence");
                if (e.get("id") != null) created.add(e);
            }
        }
        return created;
    }

    /** 逾期全量校准（等价 recalcOverdueAll）：返回本次状态变化条数 */
    public int recalcOverdueAll() {
        int n = 0;
        for (Map<String, Object> s : ctx.store().list("settlements")) {
            String before = String.valueOf(s.get("status"));
            settlementService.recalcSettlementStatus(s);
            if (!before.equals(String.valueOf(s.get("status")))) n += 1;
        }
        return n;
    }

    /** 异常升级：待受理异常单超时逐级升级（等价 escalatePendingExceptions），返回本轮升级数组 */
    public List<Map<String, Object>> escalatePendingExceptions() {
        Map<String, Object> cfg = ctx.store().obj("escalateConfig");
        double hours = (cfg != null && cfg.get("exceptionHours") != null) ? FlowCtx.num(cfg.get("exceptionHours")) : 2;
        String now = ctx.now();
        List<Map<String, Object>> escalated = new ArrayList<>();
        for (Map<String, Object> e : ctx.store().list("exceptions")) {
            if (!"pending".equals(e.get("status"))) continue;
            int ageH = (int) Math.floor(hoursBetween(str(e, "occurTime"), now));
            int target = ageH >= hours * 4 ? 2 : ageH >= hours ? 1 : 0;
            if (target > FlowCtx.intNum(e.get("escalated"))) {
                e.put("escalated", target);
                e.put("escalateTime", now);
                e.put("escalatedTo", target >= 2 ? "平台管理员（升级督办）" : "安全管理员/平台管理员");
                ctx.logAction("异常处理", target >= 2 ? "异常升级督办" : "异常升级",
                        "异常单 " + e.get("id") + " 待受理超 " + ageH + "h，" + (target >= 2 ? "升级督办平台管理员" : "升级提醒安全管理员/平台管理员"), "success");
                ctx.notify(target >= 2 ? "异常单 " + e.get("id") + " 超时未受理，升级督办" : "异常单 " + e.get("id") + " 超时未受理",
                        "exception", "/exception",
                        "待受理超 " + ageH + " 小时，" + (target >= 2 ? "请平台管理员督办处理" : "请安全管理员/平台管理员关注"), ctx.toRoles("exception"));
                escalated.add(e);
            }
        }
        return escalated;
    }

    /** 合同审批超时催办：待审批合同/改价超 contractHours 未批 → 催办审批人（每 24h 至多一次，等价 escalateContractApprovals） */
    public List<Map<String, Object>> escalateContractApprovals() {
        Map<String, Object> cfg = ctx.store().obj("escalateConfig");
        double hours = (cfg != null && cfg.get("contractHours") != null) ? FlowCtx.num(cfg.get("contractHours")) : 24;
        String now = ctx.now();
        List<Map<String, Object>> reminded = new ArrayList<>();
        for (Map<String, Object> c : ctx.store().list("contracts")) {
            if ("pending".equals(c.get("status")) && c.get("submitTime") != null) {
                int ageH = (int) Math.floor(hoursBetween(str(c, "submitTime"), now));
                if (ageH >= hours && reminderDue(str(c, "lastApprovalReminder"), now)) {
                    c.put("lastApprovalReminder", now);
                    ctx.logAction("合同管理", "审批超时催办", "合同 " + c.get("id") + " 审批待批超 " + ageH + "h，催办审批人", "success");
                    ctx.notify("合同 " + c.get("id") + " 审批超时", "approval", "/contract",
                            "审批已待批 " + ageH + " 小时，请审批人及时处理", ctx.toRoles("contract-approve", "contract"));
                    reminded.add(c);
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> pc = (Map<String, Object>) c.get("pendingChange");
            if (pc != null && pc.get("createTime") != null) {
                int ageH = (int) Math.floor(hoursBetween(str(pc, "createTime"), now));
                if (ageH >= hours && reminderDue(str(c, "lastChangeReminder"), now)) {
                    c.put("lastChangeReminder", now);
                    ctx.logAction("合同管理", "改价审批超时催办", "合同 " + c.get("id") + " 改价待批超 " + ageH + "h，催办审批人", "success");
                    ctx.notify("合同 " + c.get("id") + " 改价审批超时", "approval", "/contract",
                            "改价审批已待批 " + ageH + " 小时，请审批人及时处理", ctx.toRoles("contract-approve", "contract"));
                    reminded.add(c);
                }
            }
        }
        return reminded;
    }

    /** 单轮定时任务（等价 runSchedulerTick）：遥测 → 围栏 → 逾期 → 异常升级 → 审批催办，末尾统一回写 */
    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public Map<String, Object> runSchedulerTick() {
        if (!autoEnabled) {
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put("skipped", true);
            return skip;
        }
        return doTick();
    }

    /** 核心单轮（无开关守卫，供手动端点 /api/scheduler/tick 确定性触发） */
    public Map<String, Object> doTick() {
        advanceTelemetry();
        List<Map<String, Object>> fence = checkFenceEvents();
        int overdue = recalcOverdueAll();
        List<Map<String, Object>> escalated = escalatePendingExceptions();
        List<Map<String, Object>> reminded = escalateContractApprovals();
        if (!fence.isEmpty() || overdue > 0 || !escalated.isEmpty() || !reminded.isEmpty()) {
            ctx.store().commitAll();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fenceCreated", fence.size());
        r.put("overdueChanged", overdue);
        r.put("escalated", escalated.size());
        r.put("reminded", reminded.size());
        return r;
    }

    /* ===== 时间工具 ===== */
    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static LocalDateTime parse(String s) {
        try { return LocalDateTime.parse(s, DT); } catch (Exception e) { return null; }
    }

    /** 两个时间的小时差（to - from），解析失败返回 0 */
    private static double hoursBetween(String from, String to) {
        LocalDateTime f = parse(from), t = parse(to);
        if (f == null || t == null) return 0;
        return Duration.between(f, t).toMinutes() / 60.0;
    }

    private static int minutesBetween(String from, String to) {
        LocalDateTime f = parse(from), t = parse(to);
        if (f == null || t == null) return 0;
        return (int) Math.abs(Duration.between(f, t).toMinutes());
    }

    /** eta 是否早于（now - delayMinutes） */
    private static boolean isBeforeByMinutes(String eta, int delayMinutes) {
        LocalDateTime e = parse(eta);
        if (e == null) return false;
        return e.isBefore(LocalDateTime.now().minusMinutes(delayMinutes));
    }

    /** 催办间隔：无上次记录或距上次 >= 24h */
    private static boolean reminderDue(String last, String now) {
        if (last == null || last.isBlank()) return true;
        return hoursBetween(last, now) >= 24;
    }
}
