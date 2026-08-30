package com.blms.service.settlement;

import com.blms.service.contract.ContractService;
import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 结算服务（与 flow.js 结算流转 1:1）：
 * settlementCandidates / generateSettlements / buildReconciliation / startReconcile /
 * recalcSettlement / confirmSettle / recordPayment / revertPayment / dunning /
 * collectPrepayment / applyPrepayment / customerConfirm / customerObjection /
 * issueInvoice / redFlushInvoiceRow / markInvoiceStale。
 */
@Service
public class SettlementService {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");
    private final FlowCtx ctx;
    private final ContractService contractService;

    public SettlementService(FlowCtx ctx, ContractService contractService) {
        this.ctx = ctx;
        this.contractService = contractService;
    }

    /** 结算候选：已完成且未入账单的调度单，按 合同+月份(卸货时间) 聚合（等价 settlementCandidates） */
    public List<Map<String, Object>> settlementCandidates() {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> d : ctx.store().list("dispatches")) {
            if (!"completed".equals(d.get("status")) || FlowCtx.bool(d.get("settled"))) continue;
            String period;
            Object unloadTime = d.get("unloadTime");
            if (unloadTime != null && String.valueOf(unloadTime).length() >= 7) {
                period = String.valueOf(unloadTime).substring(0, 7);
            } else {
                period = LocalDate.now().format(YM);
            }
            String key = d.get("contractId") + "|" + period;
            Map<String, Object> g = groups.get(key);
            if (g == null) {
                Map<String, Object> c = ctx.contractOf(str(d, "contractId"));
                g = new LinkedHashMap<>();
                g.put("key", key);
                g.put("contractId", d.get("contractId"));
                g.put("customerId", c != null ? c.get("shipperId") : "");
                g.put("period", period);
                g.put("dispatches", new ArrayList<Map<String, Object>>());
                groups.put(key, g);
            }
            ((List<Map<String, Object>>) g.get("dispatches")).add(d);
        }
        List<Map<String, Object>> res = new ArrayList<>();
        for (Map<String, Object> g : groups.values()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ds = (List<Map<String, Object>>) g.get("dispatches");
            double quantity = ds.stream().mapToDouble(d -> FlowCtx.num(d.get("quantity"))).sum();
            Map<String, Object> c = ctx.contractOf(str(g, "contractId"));
            double freight = FlowCtx.num(ctx.calcSettlementFees(c, ds).get("freight"));
            g.put("dispatchCount", ds.size());
            g.put("quantity", quantity);
            g.put("freight", freight);
            res.add(g);
        }
        return res;
    }

    /** 生成结算单（等价 generateSettlements） */
    public Map<String, Object> generateSettlements(List<String> keys) {
        ctx.requireAction("settlement");
        return Map.of("created", doGenerateSettlements(keys));
    }

    /** 内部核心（无 RBAC，供 terminateContract 等内部联动调用）：返回新建结算单列表 */
    public List<Map<String, Object>> doGenerateSettlements(List<String> keys) {
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> g : settlementCandidates().stream().filter(x -> keys.contains(x.get("key"))).collect(Collectors.toList())) {
            Map<String, Object> c = ctx.contractOf(str(g, "contractId"));
            Map<String, Object> fees = ctx.calcSettlementFees(c, (List<Map<String, Object>>) g.get("dispatches"));
            int tollFee = ctx.randInt(2000, 20000);
            int surcharge = ctx.randInt(0, 8000);
            List<Map<String, Object>> settlements = ctx.store().list("settlements");
            String id = ctx.genId("JS-", 4, settlements);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", id);
            s.put("billNo", "BL-" + str(g, "period").replace("-", "") + "-" + id.substring(id.length() - 3));
            s.put("contractId", g.get("contractId"));
            s.put("customerId", c != null ? c.get("shipperId") : "");
            s.put("period", g.get("period"));
            s.put("dispatchCount", g.get("dispatchCount"));
            s.putAll(fees);
            s.put("tollFee", tollFee);
            s.put("surcharge", surcharge);
            double totalAmount = FlowCtx.num(fees.get("freight")) + FlowCtx.num(fees.get("loadingFee")) + FlowCtx.num(fees.get("unloadingFee"))
                    + tollFee + surcharge - FlowCtx.num(fees.get("lossDeduction")) - FlowCtx.num(fees.get("qualityDeduction")) - FlowCtx.num(fees.get("exceptionLoss"));
            s.put("totalAmount", totalAmount);
            s.put("paidAmount", 0);
            s.put("status", "pending");
            s.put("settleDate", null);
            s.put("invoiceStatus", "not-issued");
            s.put("reconciliation", null);
            s.put("remark", "");
            settlements.add(0, s);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ds = (List<Map<String, Object>>) g.get("dispatches");
            for (Map<String, Object> d : ds) {
                d.put("settled", true);
                d.put("settlementId", s.get("id"));
                for (Map<String, Object> e : ctx.store().list("exceptions").stream()
                        .filter(x -> d.get("id").equals(x.get("dispatchId")) && "closed".equals(x.get("status"))).collect(Collectors.toList())) {
                    e.put("settleApplied", s.get("id"));
                }
            }
            created.add(s);
        }
        if (!created.isEmpty()) {
            ctx.logAction("结算管理", "生成结算单", "生成 " + created.size() + " 张结算单（" + created.stream().map(x -> String.valueOf(x.get("billNo"))).collect(Collectors.joining("、")) + "）", "success");
            ctx.notify("生成 " + created.size() + " 张结算单", "settlement", "/settlement", created.stream().map(x -> String.valueOf(x.get("billNo"))).collect(Collectors.joining("、")), ctx.toRoles("settlement"));
        }
        commit();
        return created;
    }

    /** 对账三方比对（等价 buildReconciliation） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildReconciliation(Map<String, Object> s, String date) {
        Map<String, Object> c = ctx.contractOf(str(s, "contractId"));
        double fallbackPrice = c != null ? FlowCtx.num(c.get("unitPrice")) : 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> d : ctx.store().list("dispatches").stream().filter(x -> s.get("id").equals(x.get("settlementId"))).collect(Collectors.toList())) {
            List<Map<String, Object>> ws = ctx.store().list("weighings").stream().filter(w -> d.get("id").equals(w.get("dispatchId"))).collect(Collectors.toList());
            Double inNet = ws.stream().filter(w -> "进磅".equals(w.get("type"))).findFirst().map(w -> FlowCtx.num(w.get("net"))).orElse(null);
            Double outNet = ws.stream().filter(w -> "出磅".equals(w.get("type"))).findFirst().map(w -> FlowCtx.num(w.get("net"))).orElse(null);
            double settleQty = ctx.settleQtyOf(d);
            double diff = inNet != null ? FlowCtx.round2(FlowCtx.num(d.get("quantity")) - inNet) : 0;
            Map<String, Object> v = ctx.vehicleOf(str(d, "vehicleId"));
            double price = d.get("unitPrice") != null ? FlowCtx.num(d.get("unitPrice")) : fallbackPrice;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dispatchId", d.get("id"));
            item.put("plate", v != null ? v.get("plate") : (d.get("unitNo") != null ? d.get("unitNo") : "-"));
            item.put("dispatchQty", d.get("quantity"));
            item.put("inNet", inNet);
            item.put("outNet", outNet);
            item.put("settleQty", settleQty);
            item.put("loss", FlowCtx.round2(FlowCtx.num(d.get("quantity")) - settleQty));
            item.put("qualityQty", ctx.qualityDeductionQty(d));
            item.put("diff", diff);
            item.put("price", price);
            item.put("hasReceipt", ctx.isRoadMode(str(d, "mode")) ? d.get("receipt") != null : null);
            item.put("status", Math.abs(diff) > FlowCtx.RECONCILE_TOLERANCE ? "diff" : "match");
            items.add(item);
        }
        List<Map<String, Object>> diffItems = items.stream().filter(i -> "diff".equals(i.get("status"))).collect(Collectors.toList());
        double diffQty = FlowCtx.round2(diffItems.stream().mapToDouble(i -> FlowCtx.num(i.get("diff"))).sum());
        double lossQty = FlowCtx.round2(items.stream().mapToDouble(i -> FlowCtx.num(i.get("loss"))).sum());
        double qualityQty = FlowCtx.round2(items.stream().mapToDouble(i -> FlowCtx.num(i.get("qualityQty"))).sum());
        List<Map<String, Object>> missingReceipt = items.stream().filter(i -> Boolean.FALSE.equals(i.get("hasReceipt"))).collect(Collectors.toList());
        Map<String, Object> recon = new LinkedHashMap<>();
        recon.put("date", date == null ? ctx.now() : date);
        recon.put("items", items);
        recon.put("diffCount", diffItems.size());
        recon.put("diffQty", diffQty);
        recon.put("diffAmount", Math.round(diffItems.stream().mapToDouble(i -> Math.abs(FlowCtx.num(i.get("diff"))) * FlowCtx.num(i.get("price"))).sum()));
        recon.put("lossQty", lossQty);
        recon.put("lossAmount", Math.round(items.stream().mapToDouble(i -> FlowCtx.num(i.get("loss")) * FlowCtx.num(i.get("price"))).sum()));
        recon.put("qualityQty", qualityQty);
        recon.put("qualityAmount", Math.round(items.stream().mapToDouble(i -> FlowCtx.num(i.get("qualityQty")) * FlowCtx.num(i.get("price"))).sum()));
        recon.put("missingReceiptCount", missingReceipt.size());
        recon.put("missingReceiptIds", missingReceipt.stream().map(i -> i.get("dispatchId")).collect(Collectors.toList()));
        s.put("reconciliation", recon);
        return recon;
    }

    /** 发起对账（等价 startReconcile） */
    public Map<String, Object> startReconcile(String settlementId) {
        ctx.requireAction("settlement");
        Map<String, Object> s = ctx.byId("settlements", settlementId);
        if (s == null) return Map.of("error", "账单不存在");
        buildReconciliation(s, null);
        s.put("status", "reconciling");
        @SuppressWarnings("unchecked")
        Map<String, Object> recon = (Map<String, Object>) s.get("reconciliation");
        ctx.logAction("结算管理", "发起对账", "账单 " + s.get("billNo") + " 三方比对完成，" + recon.get("diffCount") + " 车次不一致，损耗 " + recon.get("lossQty") + " 吨", "success");
        commit();
        return Map.of("ok", true, "reconciliation", recon);
    }

    /** 重算结算单（等价 recalcSettlement） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> recalcSettlement(String settlementId) {
        ctx.requireAction("settlement");
        Map<String, Object> s = ctx.byId("settlements", settlementId);
        if (s == null) return Map.of("error", "账单不存在");
        if (!"pending".equals(s.get("status"))) return Map.of("error", "账单 " + s.get("billNo") + " 当前非\"待对账\"状态，无法重算");
        Map<String, Object> c = ctx.contractOf(str(s, "contractId"));
        List<Map<String, Object>> ds = ctx.store().list("dispatches").stream().filter(d -> s.get("id").equals(d.get("settlementId"))).collect(Collectors.toList());
        if (ds.isEmpty()) return Map.of("error", "账单 " + s.get("billNo") + " 下无车次，无法重算");
        Map<String, Object> fees = ctx.calcSettlementFees(c, ds);
        double oldTotal = FlowCtx.num(s.get("totalAmount"));
        s.putAll(fees);
        double totalAmount = FlowCtx.num(fees.get("freight")) + FlowCtx.num(fees.get("loadingFee")) + FlowCtx.num(fees.get("unloadingFee"))
                + FlowCtx.num(s.getOrDefault("tollFee", 0)) + FlowCtx.num(s.getOrDefault("surcharge", 0))
                - FlowCtx.num(fees.get("lossDeduction")) - FlowCtx.num(fees.get("qualityDeduction")) - FlowCtx.num(fees.get("exceptionLoss"));
        s.put("totalAmount", totalAmount);
        double delta = totalAmount - oldTotal;
        if (delta != 0) {
            List<Map<String, Object>> adjustments = (List<Map<String, Object>>) s.get("adjustments");
            if (adjustments == null) { adjustments = new ArrayList<>(); s.put("adjustments", adjustments); }
            Map<String, Object> adj = new LinkedHashMap<>();
            adj.put("time", ctx.now());
            adj.put("reason", "重算结算（磅单/异常口径刷新）");
            adj.put("amount", delta);
            adjustments.add(adj);
            ctx.logAction("结算管理", "重算结算", "账单 " + s.get("billNo") + " 重算：结算金额 " + FlowCtx.formatMoney(oldTotal) + " → " + FlowCtx.formatMoney(totalAmount) + "（" + (delta > 0 ? "+" : "") + FlowCtx.formatMoney(delta) + "）", "success");
            if ("issued".equals(s.get("invoiceStatus"))) markInvoiceStale(s, "重算结算，账单金额变化");
        }
        commit();
        return Map.of("ok", true, "delta", delta);
    }

    /** 逾期规则（等价 recalcSettlementStatus） */
    public void recalcSettlementStatus(Map<String, Object> s) {
        if (!"settled".equals(s.get("status")) && !"overdue".equals(s.get("status"))) return;
        Map<String, Object> c = ctx.contractOf(str(s, "contractId"));
        LocalDate due = null;
        if (s.get("settleDate") != null) {
            int days = c != null ? FlowCtx.intNum(c.getOrDefault("paymentDays", 30)) : 30;
            due = LocalDate.parse(String.valueOf(s.get("settleDate"))).plusDays(days);
        }
        boolean unpaid = FlowCtx.num(s.get("totalAmount")) - FlowCtx.num(s.get("paidAmount")) > 0;
        s.put("status", due != null && unpaid && LocalDate.now().isAfter(due) ? "overdue" : "settled");
    }

    /** 确认结算（等价 confirmSettle，环节1 签收硬拦截 + 客户确认闸门） */
    public Map<String, Object> confirmSettle(String settlementId) {
        ctx.requireAction("settlement");
        Map<String, Object> s = ctx.byId("settlements", settlementId);
        if (s == null) return Map.of("error", "账单不存在");
        if (!"reconciling".equals(s.get("status"))) return Map.of("error", "账单 " + s.get("billNo") + " 当前非\"对账中\"状态，无法确认结算");
        if (s.get("customerConfirmed") == null) {
            return Map.of("error", "客户尚未确认账单 " + s.get("billNo") + " 的对账结果，请先由客户在客户门户确认对账后再确认结算");
        }
        List<Map<String, Object>> missing = ctx.store().list("dispatches").stream()
                .filter(d -> s.get("id").equals(d.get("settlementId")) && ctx.isRoadMode(str(d, "mode")) && d.get("receipt") == null)
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            return Map.of("error", missing.size() + " 车次公路车次尚无电子签收单（收货凭证）：" + missing.stream().map(d -> String.valueOf(d.get("id"))).collect(Collectors.joining("、")) + "。签收是结算的收货依据，请先在调度单详情\"补签\"补齐签收后再确认结算");
        }
        s.put("status", "settled");
        s.put("settleDate", ctx.today());
        ctx.logAction("结算管理", "确认结算", "账单 " + s.get("billNo") + " 结算金额 " + FlowCtx.formatMoney(FlowCtx.num(s.get("totalAmount"))) + "，累计已付 " + FlowCtx.formatMoney(FlowCtx.num(s.get("paidAmount"))), "success");
        ctx.notify("账单 " + s.get("billNo") + " 已确认结算", "settlement", "/settlement", "结算金额 " + FlowCtx.formatMoney(FlowCtx.num(s.get("totalAmount"))) + "，进入收款", ctx.toRoles("settlement"));
        commit();
        return Map.of("ok", true);
    }

    /** 登记收款（等价 recordPayment，M5 发票陈旧拦截） */
    public Map<String, Object> recordPayment(String settlementId, double amount, String method) {
        ctx.requireAction("settlement");
        Map<String, Object> s = ctx.byId("settlements", settlementId);
        if (s == null) return Map.of("error", "账单不存在");
        Map<String, Object> staleInv = ctx.store().list("invoices").stream()
                .filter(i -> s.get("id").equals(i.get("settlementId")) && "issued".equals(i.get("status")) && FlowCtx.bool(i.get("stale")))
                .findFirst().orElse(null);
        if (staleInv != null) {
            return Map.of("error", "发票 " + staleInv.get("invoiceNo") + " 金额与账单金额不一致（" + staleInv.get("staleReason") + "），请先红冲重开发票后再登记收款");
        }
        double real = Math.min(amount, FlowCtx.num(s.get("totalAmount")) - FlowCtx.num(s.get("paidAmount")));
        List<Map<String, Object>> payments = ctx.store().list("payments");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", ctx.genId("SK-", 4, payments));
        p.put("settlementId", s.get("id"));
        p.put("amount", real);
        p.put("payTime", ctx.now());
        p.put("method", method);
        p.put("remark", real >= FlowCtx.num(s.get("totalAmount")) - FlowCtx.num(s.get("paidAmount")) ? "付清" : "部分收款");
        payments.add(0, p);
        s.put("paidAmount", FlowCtx.num(s.get("paidAmount")) + real);
        recalcSettlementStatus(s);
        ctx.logAction("结算管理", "登记收款", "账单 " + s.get("billNo") + " 收款 " + FlowCtx.formatMoney(real) + "（" + method + "）", "success");
        ctx.notify("账单 " + s.get("billNo") + " 收款到账", "settlement", "/settlement", method + " " + FlowCtx.formatMoney(real), ctx.toRoles("settlement"));
        commit();
        return Map.of("ok", true, "amount", real);
    }

    /** 收款冲正（等价 revertPayment） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> revertPayment(String settlementId, String paymentId, String reason) {
        ctx.requireAction("settlement");
        Map<String, Object> s = ctx.byId("settlements", settlementId);
        if (s == null) return Map.of("error", "账单不存在");
        if (!"settled".equals(s.get("status")) && !"overdue".equals(s.get("status"))) {
            return Map.of("error", "账单 " + s.get("billNo") + " 当前非\"已结算/逾期\"状态，不可冲正收款");
        }
        Map<String, Object> p = ctx.store().list("payments").stream()
                .filter(x -> paymentId.equals(x.get("id")) && s.get("id").equals(x.get("settlementId"))).findFirst().orElse(null);
        if (p == null) return Map.of("error", "收款流水不存在或不属于该账单");
        if (FlowCtx.bool(p.get("reversed"))) return Map.of("error", "流水 " + p.get("id") + " 已冲正，不可重复操作");
        if (FlowCtx.num(p.get("amount")) > FlowCtx.num(s.get("paidAmount"))) return Map.of("error", "冲正金额超过当前已付金额，数据异常");
        p.put("reversed", true);
        p.put("revertTime", ctx.now());
        p.put("revertReason", reason == null || reason.trim().isEmpty() ? "误登记冲正" : reason.trim());
        s.put("paidAmount", FlowCtx.num(s.get("paidAmount")) - FlowCtx.num(p.get("amount")));
        if (p.get("prepayUsed") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) p.get("prepayUsed")).entrySet()) {
                Map<String, Object> pp = ctx.byId("prepayments", e.getKey());
                if (pp != null) pp.put("used", Math.max(0, FlowCtx.num(pp.get("used")) - FlowCtx.num(e.getValue())));
            }
        }
        recalcSettlementStatus(s);
        ctx.logAction("结算管理", "收款冲正", "账单 " + s.get("billNo") + " 冲正流水 " + p.get("id") + " " + FlowCtx.formatMoney(FlowCtx.num(p.get("amount"))) + "（" + p.get("method") + "），原因：" + p.get("revertReason"), "success");
        ctx.notify("账单 " + s.get("billNo") + " 收款冲正 " + FlowCtx.formatMoney(FlowCtx.num(p.get("amount"))), "settlement", "/settlement", "原因：" + p.get("revertReason"), ctx.toRoles("settlement"));
        commit();
        return Map.of("ok", true, "amount", p.get("amount"));
    }

    /** 催收（等价 dunning） */
    public Map<String, Object> dunning(String settlementId, String level) {
        ctx.requireAction("settlement");
        Map<String, Object> s = ctx.byId("settlements", settlementId);
        if (s == null) return Map.of("error", "账单不存在");
        if (!"settled".equals(s.get("status")) && !"overdue".equals(s.get("status"))) return Map.of("error", "账单 " + s.get("billNo") + " 当前非\"已结算/逾期\"状态，无法催收");
        double unpaid = FlowCtx.num(s.get("totalAmount")) - FlowCtx.num(s.get("paidAmount"));
        if (unpaid <= 0) return Map.of("error", "账单 " + s.get("billNo") + " 已付清，无需催收");
        Map<String, String> levelMap = Map.of("reminder", "付款提醒", "formal", "正式催收", "legal", "法务函");
        if (!levelMap.containsKey(level)) return Map.of("error", "无效催收级别：" + level);
        if (("formal".equals(level) || "legal".equals(level)) && !"overdue".equals(s.get("status"))) {
            return Map.of("error", "正式催收/法务函仅适用于逾期账单（当前为\"已结算\"，可先发起付款提醒）");
        }
        int round = (int) ctx.store().list("dunnings").stream().filter(x -> s.get("id").equals(x.get("settlementId"))).count() + 1;
        List<Map<String, Object>> dunnings = ctx.store().list("dunnings");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", ctx.genId("CJ-", 4, dunnings));
        d.put("settlementId", s.get("id"));
        d.put("billNo", s.get("billNo"));
        d.put("round", round);
        d.put("level", level);
        d.put("levelName", levelMap.get(level));
        d.put("time", ctx.now());
        d.put("content", levelMap.get(level) + "：账单 " + s.get("billNo") + " 未付余额 " + FlowCtx.formatMoney(unpaid) + "，请尽快安排付款");
        d.put("by", ctx.op().getName());
        dunnings.add(0, d);
        ctx.logAction("结算管理", "催收", "账单 " + s.get("billNo") + " 第 " + round + " 轮" + levelMap.get(level) + "（未付 " + FlowCtx.formatMoney(unpaid) + "）", "success");
        ctx.notify("账单 " + s.get("billNo") + " " + levelMap.get(level), "settlement", "/portal", "未付余额 " + FlowCtx.formatMoney(unpaid) + "，请尽快安排付款（第 " + round + " 轮）", ctx.toRoles("customer-confirm"));
        commit();
        return Map.of("ok", true, "round", round);
    }

    /** 收取预付款（等价 collectPrepayment） */
    public Map<String, Object> collectPrepayment(String customerId, double amount, String method, String remark) {
        ctx.requireAction("settlement");
        Map<String, Object> c = ctx.byId("customers", customerId);
        if (c == null) return Map.of("error", "客户不存在");
        if ("frozen".equals(c.get("status"))) return Map.of("error", "客户 " + c.get("name") + " 已冻结，不可收取预付款");
        if (amount <= 0) return Map.of("error", "预付款金额须大于 0");
        List<Map<String, Object>> prepayments = ctx.store().list("prepayments");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", ctx.genId("YF-", 4, prepayments));
        p.put("customerId", customerId);
        p.put("amount", Math.round(amount));
        p.put("used", 0);
        p.put("time", ctx.now());
        p.put("method", method == null || method.isBlank() ? "银行转账" : method);
        p.put("remark", remark == null ? "" : remark);
        prepayments.add(0, p);
        ctx.logAction("结算管理", "收取预付款", "收取 " + c.get("name") + " 预付款 " + FlowCtx.formatMoney(FlowCtx.num(p.get("amount"))) + "（" + p.get("method") + "），当前可用 " + FlowCtx.formatMoney(contractService.prepaymentAvailable(customerId)), "success");
        ctx.notify("收取预付款 " + FlowCtx.formatMoney(FlowCtx.num(p.get("amount"))), "settlement", "/customer/" + customerId, c.get("name") + " 预付款到账（" + p.get("method") + "）", ctx.toRoles("settlement"));
        commit();
        return Map.of("ok", true, "id", p.get("id"));
    }

    /** 预付款抵扣（等价 applyPrepayment，FIFO） */
    public Map<String, Object> applyPrepayment(String settlementId, double amount) {
        ctx.requireAction("settlement");
        Map<String, Object> s = ctx.byId("settlements", settlementId);
        if (s == null) return Map.of("error", "账单不存在");
        if (!"settled".equals(s.get("status")) && !"overdue".equals(s.get("status"))) {
            return Map.of("error", "账单 " + s.get("billNo") + " 当前非\"已结算/逾期\"状态，不可抵扣预付款");
        }
        Map<String, Object> staleInv = ctx.store().list("invoices").stream()
                .filter(i -> s.get("id").equals(i.get("settlementId")) && "issued".equals(i.get("status")) && FlowCtx.bool(i.get("stale")))
                .findFirst().orElse(null);
        if (staleInv != null) {
            return Map.of("error", "发票 " + staleInv.get("invoiceNo") + " 金额与账单金额不一致（" + staleInv.get("staleReason") + "），请先红冲重开后再抵扣");
        }
        double unpaid = FlowCtx.num(s.get("totalAmount")) - FlowCtx.num(s.get("paidAmount"));
        if (unpaid <= 0) return Map.of("error", "账单 " + s.get("billNo") + " 无未付余额，无需抵扣");
        double available = contractService.prepaymentAvailable(str(s, "customerId"));
        if (available <= 0) return Map.of("error", "该客户无可用预付款，请先收取预付款");
        int real = Math.min(Math.min((int) Math.round(amount), (int) unpaid), (int) available);
        if (real <= 0) return Map.of("error", "抵扣金额须大于 0");
        List<Map<String, Object>> prepayOf = ctx.store().list("prepayments").stream()
                .filter(p -> s.get("customerId").equals(p.get("customerId")))
                .sorted(Comparator.comparing(p -> String.valueOf(p.get("time"))))
                .collect(Collectors.toList());
        int rest = real;
        List<String> usedIds = new ArrayList<>();
        Map<String, Object> usedMap = new LinkedHashMap<>();
        for (Map<String, Object> p : prepayOf) {
            if (rest <= 0) break;
            int avail = (int) (FlowCtx.num(p.get("amount")) - FlowCtx.num(p.get("used")));
            if (avail <= 0) continue;
            int x = Math.min(avail, rest);
            p.put("used", FlowCtx.num(p.get("used")) + x);
            rest -= x;
            usedIds.add(str(p, "id"));
            usedMap.put(str(p, "id"), x);
        }
        List<Map<String, Object>> payments = ctx.store().list("payments");
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("id", ctx.genId("SK-", 4, payments));
        pay.put("settlementId", s.get("id"));
        pay.put("amount", real);
        pay.put("payTime", ctx.now());
        pay.put("method", "预付款抵扣");
        pay.put("remark", "预付款抵扣（" + String.join("、", usedIds) + "）");
        pay.put("prepayUsed", usedMap);
        payments.add(0, pay);
        s.put("paidAmount", FlowCtx.num(s.get("paidAmount")) + real);
        recalcSettlementStatus(s);
        ctx.logAction("结算管理", "预付款抵扣", "账单 " + s.get("billNo") + " 预付款抵扣 " + FlowCtx.formatMoney(real) + "（" + String.join("、", usedIds) + "），剩余未付 " + FlowCtx.formatMoney(FlowCtx.num(s.get("totalAmount")) - FlowCtx.num(s.get("paidAmount"))), "success");
        ctx.notify("账单 " + s.get("billNo") + " 预付款抵扣 " + FlowCtx.formatMoney(real), "settlement", "/settlement", "剩余未付 " + FlowCtx.formatMoney(FlowCtx.num(s.get("totalAmount")) - FlowCtx.num(s.get("paidAmount"))), ctx.toRoles("settlement"));
        commit();
        return Map.of("ok", true, "amount", real);
    }

    /** 客户确认对账（等价 customerConfirm，环节2 关闭异议单） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> customerConfirm(String settlementId) {
        ctx.requireAction("customer-confirm");
        Map<String, Object> s = ctx.byId("settlements", settlementId);
        if (s == null) return Map.of("error", "账单不存在");
        if (s.get("reconciliation") == null) return Map.of("error", "账单 " + s.get("billNo") + " 尚无对账结果，无法确认");
        if (!"reconciling".equals(s.get("status"))) return Map.of("error", "账单 " + s.get("billNo") + " 当前非\"对账中\"状态，无法确认对账（异议后须先重新对账）");
        if (s.get("customerConfirmed") != null) return Map.of("error", "账单 " + s.get("billNo") + " 客户已确认过，无需重复确认");
        Map<String, Object> confirmed = new LinkedHashMap<>();
        confirmed.put("time", ctx.now());
        confirmed.put("comment", "对账结果确认，无异议");
        s.put("customerConfirmed", confirmed);
        List<Map<String, Object>> objections = (List<Map<String, Object>>) s.get("objections");
        if (objections != null) {
            for (Map<String, Object> o : objections) {
                if ("open".equals(o.get("status"))) {
                    o.put("status", "resolved");
                    o.put("resolveTime", ctx.now());
                }
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> recon = (Map<String, Object>) s.get("reconciliation");
        ctx.logAction("客户门户", "确认对账", "客户确认账单 " + s.get("billNo") + " 对账结果（差异 " + recon.get("diffCount") + " 车次，损耗 " + recon.get("lossQty") + " 吨）", "success");
        ctx.notify("客户已确认对账结果", "settlement", "/settlement", "账单 " + s.get("billNo") + " 客户已确认，可确认结算", ctx.toRoles("settlement"));
        commit();
        return Map.of("ok", true);
    }

    /** 客户异议（等价 customerObjection，环节2） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> customerObjection(String settlementId, String reason) {
        ctx.requireAction("customer-confirm");
        Map<String, Object> s = ctx.byId("settlements", settlementId);
        if (s == null) return Map.of("error", "账单不存在");
        if (s.get("reconciliation") == null) return Map.of("error", "账单 " + s.get("billNo") + " 尚无对账结果，无法提出异议");
        if (!"reconciling".equals(s.get("status"))) return Map.of("error", "账单 " + s.get("billNo") + " 当前非\"对账中\"状态，无法提出异议");
        if (s.get("customerConfirmed") != null) return Map.of("error", "账单 " + s.get("billNo") + " 客户已确认，不可再提出异议");
        String text = reason == null || reason.trim().isEmpty() ? "未填写具体原因" : reason.trim();
        List<Map<String, Object>> objections = (List<Map<String, Object>>) s.get("objections");
        if (objections == null) { objections = new ArrayList<>(); s.put("objections", objections); }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("time", ctx.now());
        o.put("reason", text);
        o.put("status", "open");
        objections.add(o);
        s.put("status", "pending");
        s.put("customerConfirmed", null);
        ctx.logAction("客户门户", "对账异议", "客户对账单 " + s.get("billNo") + " 对账结果提出异议：" + text, "success");
        ctx.notify("客户对账单 " + s.get("billNo") + " 提出异议", "settlement", "/settlement", text, ctx.toRoles("settlement"));
        commit();
        return Map.of("ok", true);
    }

    /** 开具发票（等价 issueInvoice） */
    public Map<String, Object> issueInvoice(String settlementId) {
        ctx.requireAction("invoice");
        Map<String, Object> s = ctx.byId("settlements", settlementId);
        if (s == null) return Map.of("error", "账单不存在");
        if (!"not-issued".equals(s.get("invoiceStatus")) && !"pending".equals(s.get("invoiceStatus"))) {
            return Map.of("error", "账单 " + s.get("billNo") + " 当前开票状态非\"未开票/待开具\"，无法重复开具发票");
        }
        List<Map<String, Object>> invoices = ctx.store().list("invoices");
        Map<String, Object> inv = invoices.stream().filter(i -> s.get("id").equals(i.get("settlementId")) && "pending".equals(i.get("status"))).findFirst().orElse(null);
        if (inv != null) {
            if (inv.get("invoiceNo") == null || String.valueOf(inv.get("invoiceNo")).isBlank()) inv.put("invoiceNo", genInvoiceNo(s.get("id") + "-" + inv.get("id")));
            inv.put("issueDate", ctx.today());
            inv.put("amount", s.get("totalAmount"));
            inv.put("status", "issued");
        } else {
            String id = ctx.genId("FP-", 4, invoices);
            inv = new LinkedHashMap<>();
            inv.put("id", id);
            inv.put("settlementId", s.get("id"));
            inv.put("invoiceNo", genInvoiceNo(s.get("id") + "-" + id));
            inv.put("type", "增值税专用发票");
            inv.put("amount", s.get("totalAmount"));
            inv.put("issueDate", ctx.today());
            inv.put("status", "issued");
            inv.put("remark", "");
            invoices.add(inv);
        }
        s.put("invoiceStatus", "issued");
        ctx.logAction("发票管理", "开具发票", "账单 " + s.get("billNo") + " 开具发票 " + inv.get("invoiceNo") + "，金额 " + FlowCtx.formatMoney(FlowCtx.num(s.get("totalAmount"))), "success");
        ctx.notify("账单 " + s.get("billNo") + " 已开票", "settlement", "/settlement", "发票号码 " + inv.get("invoiceNo"), ctx.toRoles("settlement", "invoice"));
        commit();
        return Map.of("ok", true, "invoiceNo", inv.get("invoiceNo"));
    }

    /** 发票红冲（等价 redFlushInvoiceRow） */
    public Map<String, Object> redFlushInvoiceRow(String invoiceId, String reason) {
        ctx.requireAction("invoice");
        Map<String, Object> inv = ctx.byId("invoices", invoiceId);
        if (inv == null) return Map.of("error", "发票不存在");
        if (!"issued".equals(inv.get("status"))) return Map.of("error", "发票 " + inv.get("id") + " 当前非\"已开具\"状态，无法红冲");
        inv.put("status", "red-flushed");
        inv.put("remark", reason == null || reason.isBlank() ? String.valueOf(inv.getOrDefault("remark", "")) : reason);
        Map<String, Object> s = ctx.byId("settlements", str(inv, "settlementId"));
        if (s != null) s.put("invoiceStatus", "not-issued");
        ctx.logAction("发票管理", "发票红冲", "发票 " + inv.get("invoiceNo") + " 红冲：" + (reason == null || reason.isBlank() ? "未填写原因" : reason), "success");
        commit();
        return Map.of("ok", true);
    }

    /** 发票金额陈旧标记（等价 markInvoiceStale，M5） */
    public void markInvoiceStale(Map<String, Object> s, String reason) {
        Map<String, Object> inv = ctx.store().list("invoices").stream()
                .filter(i -> s.get("id").equals(i.get("settlementId")) && "issued".equals(i.get("status"))).findFirst().orElse(null);
        if (inv == null || FlowCtx.bool(inv.get("stale"))) return;
        inv.put("stale", true);
        inv.put("staleReason", reason);
        ctx.logAction("发票管理", "发票金额陈旧标记", "发票 " + inv.get("invoiceNo") + " 金额 " + FlowCtx.formatMoney(FlowCtx.num(inv.get("amount"))) + " 与账单金额 " + FlowCtx.formatMoney(FlowCtx.num(s.get("totalAmount"))) + " 不一致（" + reason + "），需红冲重开", "success");
        ctx.notify("发票 " + inv.get("invoiceNo") + " 需红冲重开", "settlement", "/settlement/invoice", "账单 " + s.get("billNo") + "：" + reason, ctx.toRoles("settlement", "invoice"));
    }

    /** 发票号（确定性派生 16 位，等价 genInvoiceNo） */
    public String genInvoiceNo(String seedStr) {
        long n = 0;
        for (char ch : String.valueOf(seedStr).toCharArray()) n = (n * 31 + (int) ch) % 2147483647;
        return "2410" + (100000000000L + (n % 900000000000L));
    }

    private void commit() {
        ctx.store().commitAll();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
