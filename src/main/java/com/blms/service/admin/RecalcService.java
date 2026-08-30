package com.blms.service.admin;

import com.blms.service.settlement.SettlementService;
import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 全局口径校准（等价前端 flow.js recalcAll）：
 * 多式联运调度单补运输方式/运输单元；车辆/司机占用状态对齐；计划/合同进度回卷；结算状态重算。
 * 前端在 db 载入后调用一次保证一致性；后端提供 /api/admin/recalc 供前端联调或数据修复时触发。
 */
@Service
public class RecalcService {

    private final FlowCtx ctx;
    private final SettlementService settlement;

    public RecalcService(FlowCtx ctx, SettlementService settlement) {
        this.ctx = ctx;
        this.settlement = settlement;
    }

    public Map<String, Object> recalcAll() {
        // RBAC 单点校验：全局口径校准会改写调度/车辆/司机/计划/合同/结算并 commitAll，
        // 属全局数据变更，仅平台管理员（actions=null 全放行）可触发；其余角色默认拒绝。
        // 前端启动时 recalcAll() 为内存本地校准（afterWrite 后台 POST 被拒仅 console.warn，不阻塞 UI）。
        ctx.requireAction("admin");
        List<Map<String, Object>> dispatches = ctx.store().list("dispatches");
        // 多式联运口径：补运输方式；非公路按运输单元执行，不绑车辆/司机
        for (Map<String, Object> d : dispatches) {
            if (d.get("mode") == null || String.valueOf(d.get("mode")).isBlank()) {
                Map<String, Object> c = ctx.contractOf(str(d, "contractId"));
                d.put("mode", c != null && c.get("mode") != null ? c.get("mode") : "公路");
            }
            if (!ctx.isRoadMode(str(d, "mode"))) {
                d.put("vehicleId", null);
                d.put("driverId", null);
                if (d.get("unitNo") == null || String.valueOf(d.get("unitNo")).isBlank()) d.put("unitNo", ctx.unitNoOf(str(d, "mode"), str(d, "id")));
            }
        }
        // 车辆占用对齐
        for (Map<String, Object> v : ctx.store().list("vehicles")) {
            if ("scrapped".equals(v.get("status")) || "maintenance".equals(v.get("status"))) continue;
            boolean active = dispatches.stream().anyMatch(x -> v.get("id").equals(x.get("vehicleId")) && FlowCtx.ACTIVE.contains(x.get("status")));
            if (active && !"inuse".equals(v.get("status"))) v.put("status", "inuse");
            else if (!active && "inuse".equals(v.get("status"))) v.put("status", "idle");
        }
        // 司机占用对齐
        for (Map<String, Object> dr : ctx.store().list("drivers")) {
            if ("disabled".equals(dr.get("status"))) continue;
            boolean active = dispatches.stream().anyMatch(x -> dr.get("id").equals(x.get("driverId")) && FlowCtx.ACTIVE.contains(x.get("status")));
            if (active && !"onduty".equals(dr.get("status"))) dr.put("status", "onduty");
            else if (!active && "onduty".equals(dr.get("status"))) dr.put("status", "available");
        }
        // 计划回卷
        for (Map<String, Object> p : ctx.store().list("plans")) {
            if ("cancelled".equals(p.get("status"))) continue;
            List<Map<String, Object>> ds = dispatches.stream().filter(x -> p.get("id").equals(x.get("planId"))).toList();
            if (ds.isEmpty()) { p.put("progress", 0); continue; }
            double doneQty = ds.stream().filter(x -> "completed".equals(x.get("status"))).mapToDouble(x -> FlowCtx.num(x.get("quantity"))).sum();
            p.put("progress", (int) Math.min(100, Math.round(doneQty / FlowCtx.num(p.get("quantity")) * 100)));
            boolean allDone = ds.stream().allMatch(x -> "completed".equals(x.get("status")));
            boolean active = ds.stream().anyMatch(x -> FlowCtx.ACTIVE.contains(x.get("status")) || "exception".equals(x.get("status")));
            p.put("status", allDone ? "completed" : (active || doneQty > 0) ? "intransit" : "dispatched");
        }
        // 合同回卷
        for (Map<String, Object> c : ctx.store().list("contracts")) {
            if (!"executing".equals(c.get("status"))) continue;
            double doneQty = dispatches.stream().filter(x -> c.get("id").equals(x.get("contractId")) && "completed".equals(x.get("status")))
                    .mapToDouble(x -> FlowCtx.num(x.get("quantity"))).sum();
            c.put("progress", (int) Math.min(100, Math.round(doneQty / FlowCtx.num(c.get("quantity")) * 100)));
            if (FlowCtx.num(c.get("progress")) >= 100) c.put("status", "completed");
        }
        // 结算状态重算
        for (Map<String, Object> s : ctx.store().list("settlements")) settlement.recalcSettlementStatus(s);
        ctx.store().commitAll();
        return Map.of("ok", true, "dispatches", dispatches.size());
    }

    private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : String.valueOf(v); }
}
