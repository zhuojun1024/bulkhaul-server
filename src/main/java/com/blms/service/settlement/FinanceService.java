package com.blms.service.settlement;

import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 财务服务（与 flow.js 财务域 1:1）。
 * 趟次应付：generatePayables / payPayable / payableStats。
 * 银行核销：addBankStatement / matchBankRecord / autoMatchBank。
 */
@Service
public class FinanceService {

    private static final Pattern YH = Pattern.compile("^YH-(\\d+)$");
    private final FlowCtx ctx;
    private final SettlementService settlementService;

    public FinanceService(FlowCtx ctx, SettlementService settlementService) {
        this.ctx = ctx;
        this.settlementService = settlementService;
    }

    private void commit() { ctx.store().commitAll(); }

    /** 批量生成趟次应付（等价 generatePayables） */
    public Map<String, Object> generatePayables() {
        ctx.requireAction("settlement");
        List<Map<String, Object>> payables = ctx.store().list("payables");
        List<Map<String, Object>> targets = ctx.store().list("dispatches").stream()
                .filter(d -> "completed".equals(d.get("status")) && FlowCtx.ROAD_MODES.contains(String.valueOf(d.get("mode")))
                        && payables.stream().noneMatch(p -> d.get("id").equals(p.get("dispatchId"))))
                .collect(Collectors.toList());
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> d : targets) {
            Map<String, Object> p = ctx.doCreateTripPayable(d);
            if (p != null) created.add(p);
        }
        if (!created.isEmpty()) {
            double total = created.stream().mapToDouble(p -> FlowCtx.num(p.get("amount"))).sum();
            ctx.logAction("结算管理", "生成趟次应付", "批量生成 " + created.size() + " 笔趟次应付，合计 " + FlowCtx.formatMoney(total), "success");
            ctx.notify("生成 " + created.size() + " 笔趟次应付", "settlement", "/settlement",
                    "司机趟次费 + 外协车运费，合计 " + FlowCtx.formatMoney(total), ctx.toRoles("settlement"));
        }
        commit();
        return Map.of("ok", true, "created", created.size());
    }

    /** 趟次应付付款（等价 payPayable）：待付 → 已付 */
    public Map<String, Object> payPayable(String payableId, String method) {
        ctx.requireAction("settlement");
        Map<String, Object> p = ctx.byId("payables", payableId);
        if (p == null || !"pending".equals(p.get("status"))) {
            return Map.of("error", "应付单 " + (p != null ? p.get("id") : "") + " 非\"待付\"状态，不可付款");
        }
        p.put("status", "paid");
        p.put("payTime", ctx.now());
        p.put("payMethod", method == null || method.isBlank() ? "银行转账" : method);
        ctx.logAction("结算管理", "趟次应付付款",
                "应付单 " + p.get("id") + "（调度单 " + p.get("dispatchId") + "）付款 " + FlowCtx.formatMoney(FlowCtx.num(p.get("amount"))) + "（" + p.get("payMethod") + "）：司机趟次费 " + FlowCtx.formatMoney(FlowCtx.num(p.get("driverFee"))) + " + 外协运费 " + FlowCtx.formatMoney(FlowCtx.num(p.get("outsourceFee"))), "success");
        ctx.notify("趟次应付已付 " + FlowCtx.formatMoney(FlowCtx.num(p.get("amount"))), "settlement", "/settlement",
                "调度单 " + p.get("dispatchId") + "：" + p.get("payMethod"), ctx.toRoles("settlement"));
        commit();
        return Map.of("ok", true, "amount", FlowCtx.num(p.get("amount")));
    }

    /** 趟次应付统计（等价 payableStats） */
    public Map<String, Object> payableStats() {
        List<Map<String, Object>> payables = ctx.store().list("payables");
        List<Map<String, Object>> pending = payables.stream().filter(p -> "pending".equals(p.get("status"))).collect(Collectors.toList());
        List<Map<String, Object>> paid = payables.stream().filter(p -> "paid".equals(p.get("status"))).collect(Collectors.toList());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pendingCount", pending.size());
        r.put("pendingAmount", pending.stream().mapToDouble(p -> FlowCtx.num(p.get("amount"))).sum());
        r.put("paidCount", paid.size());
        r.put("paidAmount", paid.stream().mapToDouble(p -> FlowCtx.num(p.get("amount"))).sum());
        return r;
    }

    /** 登记银行流水（等价 addBankStatement） */
    public Map<String, Object> addBankStatement(Map<String, Object> payload) {
        ctx.requireAction("settlement");
        String counterparty = payload.get("counterparty") == null ? "" : String.valueOf(payload.get("counterparty")).trim();
        double amount = payload.get("amount") == null ? 0 : FlowCtx.num(payload.get("amount"));
        String time = payload.get("time") == null ? "" : String.valueOf(payload.get("time")).trim();
        if (counterparty.isEmpty()) return Map.of("error", "请填写对手方（付款单位）");
        if (amount <= 0) return Map.of("error", "到账金额须大于 0");
        if (time.isEmpty()) return Map.of("error", "请选择到账时间");
        int max = 0;
        for (Map<String, Object> b : ctx.store().list("bankRecords")) {
            Matcher m = YH.matcher(String.valueOf(b.get("id") == null ? "" : b.get("id")));
            if (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", "YH-" + String.format("%04d", max + 1));
        b.put("accountNo", payload.get("accountNo") == null || String.valueOf(payload.get("accountNo")).isBlank() ? "6222 **** **** 8899" : payload.get("accountNo"));
        b.put("counterparty", counterparty);
        b.put("amount", amount);
        b.put("time", time);
        b.put("summary", payload.get("summary") == null || String.valueOf(payload.get("summary")).trim().isEmpty() ? "银行到账" : String.valueOf(payload.get("summary")).trim());
        b.put("status", "unmatched");
        b.put("settlementId", null);
        b.put("matchTime", null);
        b.put("matchBy", "");
        ctx.store().list("bankRecords").add(b);
        ctx.logAction("结算管理", "流水录入", "登记银行流水 " + b.get("id") + "（" + counterparty + " " + FlowCtx.formatMoney(amount) + "），待核销", "success");
        ctx.notify("新增待核销银行流水", "settlement", "/settlement",
                "银行流水 " + b.get("id") + "：" + counterparty + " 到账 " + FlowCtx.formatMoney(amount) + "，请核销", ctx.toRoles("settlement"));
        commit();
        return Map.of("ok", true, "id", b.get("id"));
    }

    /** 手动核销（等价 matchBankRecord）：待核销流水核销至指定账单（超未付余额拦截） */
    public Map<String, Object> matchBankRecord(String bankId, String settlementId) {
        ctx.requireAction("settlement");
        Map<String, Object> b = ctx.byId("bankRecords", bankId);
        if (b == null) return Map.of("error", "请选择银行流水");
        if (!"unmatched".equals(b.get("status"))) return Map.of("error", "银行流水 " + b.get("id") + " 已核销，不能重复核销");
        Map<String, Object> s = ctx.byId("settlements", settlementId);
        if (s == null) return Map.of("error", "请选择核销账单");
        double unpaid = FlowCtx.num(s.get("totalAmount")) - FlowCtx.num(s.get("paidAmount"));
        if (FlowCtx.num(b.get("amount")) > unpaid) {
            return Map.of("error", "银行流水金额 " + FlowCtx.formatMoney(FlowCtx.num(b.get("amount"))) + " 超过账单 " + s.get("billNo") + " 未付余额 " + FlowCtx.formatMoney(unpaid));
        }
        Map<String, Object> real = settlementService.recordPayment(s.get("id").toString(), FlowCtx.num(b.get("amount")), "银行转账");
        if (real.get("error") != null) return real;
        b.put("status", "matched");
        b.put("settlementId", s.get("id"));
        b.put("matchTime", ctx.now());
        b.put("matchBy", ctx.op().getName());
        ctx.logAction("结算管理", "银行核销", "银行流水 " + b.get("id") + "（" + b.get("counterparty") + " " + FlowCtx.formatMoney(FlowCtx.num(b.get("amount"))) + "）核销至账单 " + s.get("billNo"), "success");
        commit();
        return Map.of("ok", true, "real", real);
    }

    /** 自动核销（等价 autoMatchBank）：对手方+金额与账单未付余额精确一致者自动核销 */
    public List<Map<String, Object>> autoMatchBank() {
        ctx.requireAction("settlement");
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> b : ctx.store().list("bankRecords").stream()
                .filter(x -> "unmatched".equals(x.get("status"))).collect(Collectors.toList())) {
            Map<String, Object> c = ctx.store().list("customers").stream()
                    .filter(x -> b.get("counterparty").equals(x.get("name"))).findFirst().orElse(null);
            Map<String, Object> s = ctx.store().list("settlements").stream()
                    .filter(x -> ("settled".equals(x.get("status")) || "overdue".equals(x.get("status")))
                            && (c != null && c.get("id").equals(x.get("customerId")))
                            && Math.abs(FlowCtx.num(x.get("totalAmount")) - FlowCtx.num(x.get("paidAmount")) - FlowCtx.num(b.get("amount"))) < 0.01)
                    .findFirst().orElse(null);
            if (s == null) continue;
            Map<String, Object> r = matchBankRecord(b.get("id").toString(), s.get("id").toString());
            if (r.get("error") == null) matched.add(b);
        }
        if (!matched.isEmpty()) ctx.logAction("结算管理", "自动核销", "自动核销完成，" + matched.size() + " 笔银行流水已核销", "success");
        commit();
        return matched;
    }
}
