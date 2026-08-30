package com.blms.service.weighing;

import com.blms.service.settlement.SettlementService;
import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 磅单服务（与 flow.js 磅单域 1:1）。
 * correctWeighing：复磅更正 + 结算联动（重算账单金额，已对账/结算则客户确认失效，已开票标记金额陈旧）。
 */
@Service
public class WeighingService {

    private final FlowCtx ctx;
    private final SettlementService settlementService;

    public WeighingService(FlowCtx ctx, SettlementService settlementService) {
        this.ctx = ctx;
        this.settlementService = settlementService;
    }

    private void commit() { ctx.store().commitAll(); }

    /** 磅单补录（等价 manualWeighing）：公路车次手工登记进/出磅，重复拦截 */
    public Map<String, Object> manualWeighing(String dispatchId, String type, double net) {
        ctx.requireAction("weighing");
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        if (!ctx.isRoadMode(str(d, "mode"))) {
            return Map.of("error", (str(d, "mode") == null ? "" : str(d, "mode")) + " 车次按运输单元执行，无公路磅单，无需补录");
        }
        boolean exists = ctx.store().list("weighings").stream()
                .anyMatch(w -> dispatchId.equals(w.get("dispatchId")) && type.equals(w.get("type")));
        if (exists) return Map.of("error", "该调度单已存在" + type + "磅单，不能重复补录");
        ctx.pushWeighing(d, type, net, ctx.now());
        ctx.logAction("磅单记录", "磅单补录", "调度单 " + d.get("id") + " 补录" + type + "磅单，净重 " + net + " 吨", "success");
        commit();
        return Map.of("ok", true);
    }

    /** 磅单更正/复磅（等价 correctWeighing） */
    public Map<String, Object> correctWeighing(String weighingId, double newNet, String reason) {
        ctx.requireAction("weighing");
        Map<String, Object> w = ctx.byId("weighings", weighingId);
        if (w == null) return Map.of("error", "磅单不存在");
        double net = newNet;
        if (Double.isNaN(net) || Double.isInfinite(net) || net <= 0) return Map.of("error", "复磅净重须为大于 0 的数值");
        double fixed = FlowCtx.round2(net);
        double oldNet = FlowCtx.num(w.get("net"));
        if (fixed == oldNet) return Map.of("error", "复磅净重与原值相同，无需更正");
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) return Map.of("error", "请填写复磅原因");

        if (w.get("originalNet") == null) w.put("originalNet", oldNet);
        w.put("net", fixed);
        w.put("gross", FlowCtx.round2(FlowCtx.num(w.get("tare")) + fixed));
        w.put("corrected", true);
        w.put("correctTime", ctx.now());
        w.put("correctReason", r);
        w.put("correctOperator", ctx.op().getName());
        ctx.logAction("磅单记录", "磅单更正/复磅", "磅单 " + w.get("id") + "（调度单 " + w.get("dispatchId") + "，" + w.get("type") + "）净重 " + oldNet + " → " + fixed + " 吨，原因：" + r, "success");

        // 结算联动：车次已入账单 → 按当前磅单重算金额，已对账/结算则客户确认失效
        Map<String, Object> d = ctx.dispatchOf(str(w, "dispatchId"));
        if (d != null && d.get("settlementId") != null) {
            Map<String, Object> s = ctx.byId("settlements", str(d, "settlementId"));
            if (s != null) applyWeighingCorrectionToSettlement(s, w, oldNet, fixed);
        }
        ctx.notify("磅单 " + w.get("id") + " 已复磅更正", "weighing", "/terminal/weighing",
                w.get("type") + "净重 " + oldNet + " → " + fixed + " 吨，原因：" + r, ctx.toRoles("weighing", "settlement"));
        commit();
        return Map.of("ok", true, "oldNet", oldNet, "net", fixed);
    }

    /** 磅单更正的结算联动（等价 applyWeighingCorrectionToSettlement） */
    private void applyWeighingCorrectionToSettlement(Map<String, Object> s, Map<String, Object> w, double oldNet, double net) {
        Map<String, Object> c = ctx.contractOf(str(s, "contractId"));
        List<Map<String, Object>> ds = ctx.store().list("dispatches").stream()
                .filter(x -> s.get("id").equals(x.get("settlementId"))).collect(Collectors.toList());
        boolean wasReconciled = !"pending".equals(s.get("status"));
        Map<String, Object> fees = ctx.calcSettlementFees(c, ds);
        double oldTotal = FlowCtx.num(s.get("totalAmount"));
        s.putAll(fees);
        double total = FlowCtx.num(fees.get("freight")) + FlowCtx.num(fees.get("loadingFee")) + FlowCtx.num(fees.get("unloadingFee"))
                + FlowCtx.num(s.get("tollFee")) + FlowCtx.num(s.get("surcharge"))
                - FlowCtx.num(fees.get("lossDeduction")) - FlowCtx.num(fees.get("qualityDeduction")) - FlowCtx.num(fees.get("exceptionLoss"));
        s.put("totalAmount", total);
        double delta = total - oldTotal;
        if (delta != 0) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> adjustments = (List<Map<String, Object>>) s.get("adjustments");
            if (adjustments == null) { adjustments = new java.util.ArrayList<>(); s.put("adjustments", adjustments); }
            Map<String, Object> adj = new LinkedHashMap<>();
            adj.put("time", ctx.now());
            adj.put("reason", "磅单 " + w.get("id") + " 复磅更正（" + w.get("type") + "净重 " + oldNet + " → " + net + "）" + (wasReconciled ? "，已对账/结算，客户确认失效，须重新对账确认" : ""));
            adj.put("amount", delta);
            adjustments.add(adj);
            if ("issued".equals(s.get("invoiceStatus"))) settlementService.markInvoiceStale(s, "磅单 " + w.get("id") + " 复磅更正，账单金额变化");
        }
        if (wasReconciled) {
            s.put("status", "pending");
            s.put("customerConfirmed", null);
            s.put("reconciliation", null);
            s.put("settleDate", null);
            ctx.notify("账单 " + s.get("billNo") + " 磅单更正后须重新对账确认", "settlement", "/settlement",
                    "磅单更正后结算金额调整为 " + FlowCtx.formatMoney(total) + "，客户原确认已失效，请重新对账并由客户确认", ctx.toRoles("settlement"));
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
