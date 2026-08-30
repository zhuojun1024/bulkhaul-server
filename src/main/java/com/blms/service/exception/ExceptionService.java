package com.blms.service.exception;

import com.blms.service.settlement.SettlementService;
import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常处置服务（与 flow.js 异常域 1:1）。
 * acceptException / finishException / closeException（close 联动结算补扣 + 事故结案 + 车辆状态）。
 */
@Service
public class ExceptionService {

    private final FlowCtx ctx;
    private final SettlementService settlementService;

    public ExceptionService(FlowCtx ctx, SettlementService settlementService) {
        this.ctx = ctx;
        this.settlementService = settlementService;
    }

    private void commit() { ctx.store().commitAll(); }

    /**
     * 系统级异常单创建（等价 flow.js createException 内部核心）。
     * 围栏事件等系统定时任务调用：不做登录用户权限校验（与后端系统任务口径一致），
     * 但保留调度单状态守卫；事故类同步生成事故记录。返回异常单（守卫拦截时返回带 error 的 Map）。
     */
    public Map<String, Object> createException(Map<String, Object> d, String description, String type, String level, String source) {
        if (!List.of("pending", "loading", "intransit", "unloading").contains(d.get("status"))) {
            return Map.of("error", "调度单 " + d.get("id") + " 当前非执行中状态，无法上报异常");
        }
        d.put("exceptionFrom", d.get("status"));
        d.put("status", "exception");
        d.put("speed", 0);
        List<Map<String, Object>> exceptions = ctx.store().list("exceptions");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", ctx.genId("YC-", 4, exceptions));
        e.put("dispatchId", d.get("id"));
        e.put("type", type);
        e.put("level", level);
        e.put("status", "pending");
        e.put("occurTime", ctx.now());
        e.put("handler", "");
        e.put("description", description);
        e.put("result", "");
        e.put("cost", 0);
        e.put("source", source == null ? "" : source);
        exceptions.add(0, e);
        if ("accident".equals(type)) {
            Map<String, Object> v = ctx.vehicleOf(str(d, "vehicleId"));
            List<Map<String, Object>> accidents = ctx.store().list("accidents");
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", ctx.genId("SG-", 3, accidents));
            a.put("time", ctx.today());
            a.put("type", "碰撞");
            a.put("level", "high".equals(level) ? "重大" : "medium".equals(level) ? "较大" : "一般");
            a.put("vehicleId", d.get("vehicleId"));
            a.put("plate", v != null ? v.get("plate") : "-");
            a.put("location", "调度单 " + d.get("id") + " 在途");
            a.put("description", description);
            a.put("handling", "处置中");
            a.put("loss", 0);
            a.put("status", "handling");
            a.put("exceptionId", e.get("id"));
            accidents.add(0, a);
            e.put("accidentId", a.get("id"));
        }
        ctx.rollupPlan(str(d, "planId"));
        ctx.logAction("异常处理", "上报异常", "调度单 " + d.get("id") + " 上报异常（" + type + "），生成异常单 " + e.get("id") + ("accident".equals(type) ? " 及事故记录 " + e.get("accidentId") : ""), "success");
        ctx.notify("调度单 " + d.get("id") + " 上报异常", "exception", "/exception", description, ctx.toRoles("exception"));
        return e;
    }

    /** 受理异常（等价 acceptException）：pending → handling，指派处理人 */
    public Map<String, Object> acceptException(String exceptionId, String handler) {
        ctx.requireAction("exception");
        Map<String, Object> e = ctx.byId("exceptions", exceptionId);
        if (e == null) return Map.of("error", "异常单不存在");
        e.put("handler", handler);
        e.put("status", "handling");
        ctx.logAction("异常处理", "受理异常", "异常单 " + e.get("id") + " 受理，处理人 " + handler, "success");
        commit();
        return Map.of("ok", true);
    }

    /** 处置完成（等价 finishException）：填写处置结果与损失金额 */
    public Map<String, Object> finishException(String exceptionId, String result, double cost) {
        ctx.requireAction("exception");
        Map<String, Object> e = ctx.byId("exceptions", exceptionId);
        if (e == null) return Map.of("error", "异常单不存在");
        e.put("result", result);
        e.put("cost", cost);
        if (e.get("accidentId") != null) {
            Map<String, Object> a = ctx.byId("accidents", str(e, "accidentId"));
            if (a != null) {
                a.put("handling", result);
                a.put("loss", cost);
            }
        }
        ctx.logAction("异常处理", "处置完成", "异常单 " + e.get("id") + " 处置完成，损失 " + cost + " 元", "success");
        commit();
        return Map.of("ok", true);
    }

    /** 关闭归档（等价 closeException）：closed；事故类同步结案并更新车辆状态；结算联动补扣损失 */
    public Map<String, Object> closeException(String exceptionId) {
        ctx.requireAction("exception");
        Map<String, Object> e = ctx.byId("exceptions", exceptionId);
        if (e == null) return Map.of("error", "异常单不存在");
        e.put("status", "closed");
        if (e.get("handler") == null || String.valueOf(e.get("handler")).isBlank()) e.put("handler", "系统");
        if (e.get("result") == null || String.valueOf(e.get("result")).isBlank()) e.put("result", "已处理完毕");
        if (e.get("accidentId") != null) {
            Map<String, Object> a = ctx.byId("accidents", str(e, "accidentId"));
            if (a != null) {
                a.put("status", "closed");
                if (a.get("handling") == null || String.valueOf(a.get("handling")).isBlank()) a.put("handling", "已结案");
                Map<String, Object> v = ctx.vehicleOf(str(a, "vehicleId"));
                if (v != null && "idle".equals(v.get("status"))) v.put("status", "maintenance");
            }
        }
        double cost = FlowCtx.num(e.get("cost"));
        if (cost > 0 && e.get("dispatchId") != null && !FlowCtx.bool(e.get("settleApplied"))) {
            Map<String, Object> d = ctx.dispatchOf(str(e, "dispatchId"));
            Map<String, Object> s = (d != null && d.get("settlementId") != null) ? ctx.byId("settlements", str(d, "settlementId")) : null;
            if (s != null) {
                boolean wasSettled = "settled".equals(s.get("status")) || "overdue".equals(s.get("status"));
                s.put("exceptionLoss", FlowCtx.num(s.get("exceptionLoss")) + cost);
                s.put("totalAmount", FlowCtx.num(s.get("totalAmount")) - cost);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> adjustments = (List<Map<String, Object>>) s.get("adjustments");
                if (adjustments == null) { adjustments = new java.util.ArrayList<>(); s.put("adjustments", adjustments); }
                Map<String, Object> adj = new LinkedHashMap<>();
                adj.put("time", ctx.now());
                adj.put("reason", "异常单 " + e.get("id") + " 关闭，损失补扣" + ("issued".equals(s.get("invoiceStatus")) ? "（已开票，需红冲重开）" : "") + (wasSettled ? "（已结算，客户确认失效，须重新对账确认）" : ""));
                adj.put("amount", -cost);
                adjustments.add(adj);
                if ("issued".equals(s.get("invoiceStatus"))) settlementService.markInvoiceStale(s, "异常单 " + e.get("id") + " 关闭补扣损失 " + FlowCtx.formatMoney(cost));
                if (wasSettled) {
                    s.put("status", "pending");
                    s.put("customerConfirmed", null);
                    s.put("reconciliation", null);
                    s.put("settleDate", null);
                    ctx.notify("账单 " + s.get("billNo") + " 补扣后须重新对账确认", "settlement", "/settlement",
                            "异常单 " + e.get("id") + " 关闭补扣 " + FlowCtx.formatMoney(cost) + "，结算金额调整为 " + FlowCtx.formatMoney(FlowCtx.num(s.get("totalAmount"))) + "，客户原确认已失效，请重新对账并由客户确认", ctx.toRoles("settlement"));
                    ctx.notify("账单 " + s.get("billNo") + " 金额调整，请重新确认对账", "settlement", "/portal",
                            "异常补扣后结算金额调整为 " + FlowCtx.formatMoney(FlowCtx.num(s.get("totalAmount"))) + "，请重新确认对账结果", ctx.toRoles("customer-confirm"));
                }
                e.put("settleApplied", s.get("id"));
                ctx.logAction("结算管理", "结算调整", "账单 " + s.get("billNo") + " 因异常单 " + e.get("id") + " 关闭补扣损失 " + FlowCtx.formatMoney(cost) + "，结算金额调整为 " + FlowCtx.formatMoney(FlowCtx.num(s.get("totalAmount"))) + (wasSettled ? "，客户确认失效，账单回待对账" : ""), "success");
            }
        }
        ctx.logAction("异常处理", "关闭异常单", "异常单 " + e.get("id") + " 关闭归档" + (e.get("accidentId") != null ? "，事故 " + e.get("accidentId") + " 结案" : ""), "success");
        ctx.notify("异常单 " + e.get("id") + " 已关闭", "exception", "/exception", str(e, "result"), ctx.toRoles("exception"));
        commit();
        return Map.of("ok", true);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
