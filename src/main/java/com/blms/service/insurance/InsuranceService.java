package com.blms.service.insurance;

import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保险服务（与 flow.js 保险域 1:1）。
 * fileInsuranceClaim / assessInsuranceClaim / settleInsuranceClaim（结案冲减账单异常损失）/ rejectInsuranceClaim。
 */
@Service
public class InsuranceService {

    private final FlowCtx ctx;

    public InsuranceService(FlowCtx ctx) {
        this.ctx = ctx;
    }

    private void commit() { ctx.store().commitAll(); }

    /** 报险/投保（等价 fileInsuranceClaim）：为事故登记理赔单，同一事故仅一张 */
    public Map<String, Object> fileInsuranceClaim(String accidentId, Map<String, Object> payload) {
        ctx.requireAction("insurance");
        Map<String, Object> a = ctx.byId("accidents", accidentId);
        if (a == null) return Map.of("error", "事故 " + accidentId + " 不存在");
        Map<String, Object> dup = ctx.store().list("insurance").stream()
                .filter(x -> accidentId.equals(x.get("accidentId"))).findFirst().orElse(null);
        if (dup != null) return Map.of("error", "事故 " + accidentId + " 已有理赔单 " + dup.get("id") + "，请勿重复报险");
        Map<String, Object> e = ctx.store().list("exceptions").stream()
                .filter(x -> a.get("id").equals(x.get("accidentId"))).findFirst().orElse(null);
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("id", ctx.genId("BX-", 3, ctx.store().list("insurance")));
        claim.put("accidentId", a.get("id"));
        claim.put("dispatchId", e != null ? e.get("dispatchId") : "");
        claim.put("policyNo", payload.get("policyNo") == null || String.valueOf(payload.get("policyNo")).isBlank()
                ? "PICC-" + String.valueOf(a.get("id")).replace("SG-", "") + "-001" : payload.get("policyNo"));
        claim.put("insurer", payload.get("insurer") == null || String.valueOf(payload.get("insurer")).isBlank() ? "中国人民财产保险" : payload.get("insurer"));
        claim.put("insured", payload.get("insured") == null || String.valueOf(payload.get("insured")).isBlank() ? "车辆及货物" : payload.get("insured"));
        claim.put("claimDate", payload.get("claimDate") == null || String.valueOf(payload.get("claimDate")).isBlank() ? ctx.today() : payload.get("claimDate"));
        claim.put("reportedAmount", payload.get("reportedAmount") != null ? FlowCtx.num(payload.get("reportedAmount")) : FlowCtx.num(a.get("loss")));
        claim.put("responsibility", "");
        claim.put("responsibilityParty", "");
        claim.put("assessedAmount", 0);
        claim.put("settledAmount", 0);
        claim.put("status", "reported");
        claim.put("handler", payload.get("handler") == null ? "" : payload.get("handler"));
        claim.put("remark", payload.get("remark") == null ? "" : payload.get("remark"));
        ctx.store().list("insurance").add(0, claim);
        a.put("insuranceId", claim.get("id"));
        ctx.logAction("安全管理", "保险报险", "事故 " + a.get("id") + " 报险，理赔单 " + claim.get("id") + "（" + claim.get("insurer") + "，报案金额 " + FlowCtx.formatMoney(FlowCtx.num(claim.get("reportedAmount"))) + "）", "success");
        ctx.notify("事故 " + a.get("id") + " 已报险", "exception", "/safety",
                "理赔单 " + claim.get("id") + "（" + claim.get("insurer") + "），报案金额 " + FlowCtx.formatMoney(FlowCtx.num(claim.get("reportedAmount"))), ctx.toRoles("insurance", "safety"));
        commit();
        return Map.of("ok", true, "id", claim.get("id"), "claim", claim);
    }

    /** 责任认定 + 核定金额（等价 assessInsuranceClaim）：reported → assessed */
    public Map<String, Object> assessInsuranceClaim(String claimId, Map<String, Object> payload) {
        ctx.requireAction("insurance");
        Map<String, Object> claim = ctx.byId("insurance", claimId);
        if (claim == null) return Map.of("error", "理赔单不存在");
        if (!"reported".equals(claim.get("status"))) return Map.of("error", "理赔单 " + claim.get("id") + " 当前非\"已报险\"状态，无法定责核定");
        if (payload.get("responsibility") == null || String.valueOf(payload.get("responsibility")).isBlank()) return Map.of("error", "请选择责任认定");
        if (payload.get("assessedAmount") == null || FlowCtx.num(payload.get("assessedAmount")) < 0) return Map.of("error", "请填写核定金额");
        claim.put("responsibility", payload.get("responsibility"));
        claim.put("responsibilityParty", payload.get("responsibilityParty") == null ? "" : payload.get("responsibilityParty"));
        claim.put("assessedAmount", FlowCtx.num(payload.get("assessedAmount")));
        if (payload.get("handler") != null && !String.valueOf(payload.get("handler")).isBlank()) claim.put("handler", payload.get("handler"));
        claim.put("status", "assessed");
        ctx.logAction("安全管理", "保险责任认定", "理赔单 " + claim.get("id") + " 定责：" + claim.get("responsibility") + "（" + (String.valueOf(claim.get("responsibilityParty")).isBlank() ? "—" : claim.get("responsibilityParty")) + "），核定 " + FlowCtx.formatMoney(FlowCtx.num(claim.get("assessedAmount"))), "success");
        commit();
        return Map.of("ok", true);
    }

    /** 理赔结案（等价 settleInsuranceClaim）：assessed → settled，冲减事故损失 + 账单异常损失 */
    public Map<String, Object> settleInsuranceClaim(String claimId, Map<String, Object> payload) {
        ctx.requireAction("insurance");
        Map<String, Object> claim = ctx.byId("insurance", claimId);
        if (claim == null) return Map.of("error", "理赔单不存在");
        if (!"assessed".equals(claim.get("status"))) return Map.of("error", "理赔单 " + claim.get("id") + " 当前非\"已定责核定\"状态，无法理赔结案");
        double settled = payload.get("settledAmount") != null ? FlowCtx.num(payload.get("settledAmount")) : FlowCtx.num(claim.get("assessedAmount"));
        if (settled < 0) return Map.of("error", "理赔金额不能为负");
        claim.put("settledAmount", settled);
        claim.put("status", "settled");
        claim.put("settleDate", ctx.today());
        Map<String, Object> a = ctx.byId("accidents", str(claim, "accidentId"));
        if (a != null) a.put("insuranceRecovered", FlowCtx.num(a.get("insuranceRecovered")) + settled);
        String offsetBill = null;
        Map<String, Object> e = ctx.store().list("exceptions").stream()
                .filter(x -> claim.get("accidentId").equals(x.get("accidentId"))).findFirst().orElse(null);
        if (e != null && e.get("settleApplied") != null) {
            Map<String, Object> s = ctx.byId("settlements", str(e, "settleApplied"));
            if (s != null) {
                double recover = Math.min(settled, FlowCtx.num(e.get("cost")));
                if (recover > 0) {
                    boolean wasSettled = "settled".equals(s.get("status")) || "overdue".equals(s.get("status"));
                    s.put("exceptionLoss", FlowCtx.num(s.get("exceptionLoss")) - recover);
                    s.put("totalAmount", FlowCtx.num(s.get("totalAmount")) + recover);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> adjustments = (List<Map<String, Object>>) s.get("adjustments");
                    if (adjustments == null) { adjustments = new java.util.ArrayList<>(); s.put("adjustments", adjustments); }
                    Map<String, Object> adj = new LinkedHashMap<>();
                    adj.put("time", ctx.now());
                    adj.put("reason", "理赔单 " + claim.get("id") + " 结案，保险回收 " + FlowCtx.formatMoney(recover) + " 冲减异常损失");
                    adj.put("amount", recover);
                    adjustments.add(adj);
                    if (wasSettled) {
                        s.put("status", "pending");
                        s.put("customerConfirmed", null);
                        s.put("reconciliation", null);
                        s.put("settleDate", null);
                    }
                    offsetBill = String.valueOf(s.get("billNo"));
                    ctx.logAction("结算管理", "保险回收冲减", "账单 " + s.get("billNo") + " 因理赔单 " + claim.get("id") + " 结案回收 " + FlowCtx.formatMoney(recover) + "，异常损失冲减，结算金额调整为 " + FlowCtx.formatMoney(FlowCtx.num(s.get("totalAmount"))) + (wasSettled ? "，客户确认失效，账单回待对账" : ""), "success");
                }
            }
        }
        ctx.logAction("安全管理", "保险理赔结案", "理赔单 " + claim.get("id") + " 结案，理赔 " + FlowCtx.formatMoney(settled) + (offsetBill != null ? "，冲减账单 " + offsetBill + " 异常损失" : ""), "success");
        ctx.notify("理赔单 " + claim.get("id") + " 已结案", "exception", "/safety",
                "理赔 " + FlowCtx.formatMoney(settled) + "（责任：" + claim.get("responsibility") + "）", ctx.toRoles("insurance", "safety"));
        commit();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("settledAmount", settled);
        r.put("offsetSettlement", offsetBill);
        return r;
    }

    /** 拒赔（等价 rejectInsuranceClaim）：reported/assessed → rejected */
    public Map<String, Object> rejectInsuranceClaim(String claimId, String reason) {
        ctx.requireAction("insurance");
        Map<String, Object> claim = ctx.byId("insurance", claimId);
        if (claim == null) return Map.of("error", "理赔单不存在");
        if (!("reported".equals(claim.get("status")) || "assessed".equals(claim.get("status")))) {
            return Map.of("error", "理赔单 " + claim.get("id") + " 当前状态无法拒赔");
        }
        claim.put("status", "rejected");
        claim.put("remark", reason == null || reason.isBlank() ? claim.get("remark") : reason);
        ctx.logAction("安全管理", "保险拒赔", "理赔单 " + claim.get("id") + " 拒赔：" + (reason == null || reason.isBlank() ? "—" : reason), "fail");
        commit();
        return Map.of("ok", true);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
