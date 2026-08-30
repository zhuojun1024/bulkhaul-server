package com.blms.service.contract;

import com.blms.service.settlement.SettlementService;
import com.blms.store.FlowCtx;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合同/计划服务（与 flow.js 1:1）。
 * createContract（draft/pending）/ createPlan / cancelPlan / contractRemaining /
 * 审批流（submit/approve/reject）/ 变更（change/approveChange/rejectChange）/
 * 生命周期（extend/terminate/complete/archive）。
 * 信用校验 creditCheck 在此（读 settlements/prepayments）。
 */
@Service
public class ContractService {

    private final FlowCtx ctx;
    private final SettlementService settlementService;

    public ContractService(FlowCtx ctx, @Lazy SettlementService settlementService) {
        this.ctx = ctx;
        this.settlementService = settlementService;
    }

    private void commit() { ctx.store().commitAll(); }

    /** 信用校验（等价 creditCheck）：（未付余额 - 可用预付款）+ 新订单金额 vs 授信额度 */
    public Map<String, Object> creditCheck(String customerId, double orderAmount) {
        Map<String, Object> c = ctx.byId("customers", customerId);
        if (c == null) return Map.of("ok", true, "message", "");
        double outstanding = outstandingOf(customerId);
        double prepay = prepaymentAvailable(customerId);
        double occupied = Math.max(0, outstanding - prepay);
        double total = occupied + orderAmount;
        if (total > FlowCtx.num(c.get("creditLimit"))) {
            return Map.of("ok", false, "message",
                    c.get("name") + " 信用占用 " + FlowCtx.formatMoney(occupied) + "（未付 " + FlowCtx.formatMoney(outstanding)
                            + " - 预付 " + FlowCtx.formatMoney(prepay) + "）+ 本单 " + FlowCtx.formatMoney(orderAmount)
                            + " = " + FlowCtx.formatMoney(total) + "，超出授信额度 " + FlowCtx.formatMoney(FlowCtx.num(c.get("creditLimit"))));
        }
        return Map.of("ok", true, "message", "");
    }

    public double outstandingOf(String customerId) {
        return ctx.store().list("settlements").stream()
                .filter(s -> customerId.equals(s.get("customerId")))
                .mapToDouble(s -> Math.max(0, FlowCtx.num(s.get("totalAmount")) - FlowCtx.num(s.get("paidAmount"))))
                .sum();
    }

    public double prepaymentAvailable(String customerId) {
        return ctx.store().list("prepayments").stream()
                .filter(p -> customerId.equals(p.get("customerId")))
                .mapToDouble(p -> FlowCtx.num(p.get("amount")) - FlowCtx.num(p.get("used")))
                .sum();
    }

    /** 合同剩余可计划量（等价 contractRemaining） */
    public double contractRemaining(String contractId) {
        Map<String, Object> c = ctx.contractOf(contractId);
        if (c == null) return 0;
        double planned = ctx.store().list("plans").stream()
                .filter(p -> contractId.equals(p.get("contractId")) && !"cancelled".equals(p.get("status")))
                .mapToDouble(p -> FlowCtx.num(p.get("quantity")))
                .sum();
        return Math.max(0, FlowCtx.num(c.get("quantity")) - planned);
    }

    /** 新建合同（等价 createContract，status=draft；pending 审批流见阶段 5） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createContract(Map<String, Object> payload, String status) {
        ctx.requireAction("contract");
        String name = payload.get("name") == null ? "" : String.valueOf(payload.get("name")).trim();
        if (name.isEmpty()) return Map.of("error", "请输入合同名称");
        Map<String, Object> shipper = ctx.byId("customers", str(payload, "shipperId"));
        Map<String, Object> consignee = ctx.byId("customers", str(payload, "consigneeId"));
        if (shipper == null || !List.of("shipper", "both").contains(shipper.get("type"))) return Map.of("error", "请选择发货方客户");
        if (consignee == null || !List.of("consignee", "both").contains(consignee.get("type"))) return Map.of("error", "请选择收货方客户");
        if ("frozen".equals(shipper.get("status"))) return Map.of("error", "发货方 " + shipper.get("name") + " 已冻结，不可新建合同");
        if (str(payload, "commodityId") == null) return Map.of("error", "请选择商品");
        if (str(payload, "loadTerminalId") == null || str(payload, "unloadTerminalId") == null) return Map.of("error", "请选择装/卸货场站");
        double quantity = FlowCtx.num(payload.get("quantity"));
        if (quantity <= 0) return Map.of("error", "计划数量须大于 0");
        double unitPrice = FlowCtx.num(payload.get("unitPrice"));
        if (unitPrice <= 0) return Map.of("error", "合同单价须大于 0");
        double amount = Math.round(quantity * unitPrice);
        if ("pending".equals(status)) {
            Map<String, Object> check = creditCheck(str(payload, "shipperId"), amount);
            if (!FlowCtx.bool(check.get("ok"))) return Map.of("error", String.valueOf(check.get("message")));
        }
        List<Map<String, Object>> contracts = ctx.store().list("contracts");
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("id", ctx.genId("HT-", 4, contracts));
        contract.put("name", name);
        contract.put("shipperId", shipper.get("id"));
        contract.put("consigneeId", consignee.get("id"));
        contract.put("commodityId", payload.get("commodityId"));
        contract.put("mode", payload.getOrDefault("mode", "公路"));
        contract.put("loadTerminalId", payload.get("loadTerminalId"));
        contract.put("unloadTerminalId", payload.get("unloadTerminalId"));
        contract.put("quantity", quantity);
        contract.put("unitPrice", unitPrice);
        contract.put("amount", amount);
        contract.put("paymentDays", payload.getOrDefault("paymentDays", 30));
        contract.put("startDate", payload.getOrDefault("startDate", ctx.today()));
        contract.put("endDate", payload.getOrDefault("endDate", ctx.nowPlusDays(180)));
        contract.put("signDate", ctx.today());
        contract.put("status", "pending".equals(status) ? "pending" : "draft");
        contract.put("progress", 0);
        contract.put("approvalChain", null);
        contract.put("contact", payload.getOrDefault("contact", shipper.getOrDefault("contact", "—")));
        contract.put("phone", payload.getOrDefault("phone", shipper.getOrDefault("phone", "—")));
        contract.put("remark", payload.getOrDefault("remark", ""));
        contracts.add(0, contract);
        ctx.logAction("合同管理", "新建合同", "合同 " + contract.get("id") + " 创建（" + name + "，" + quantity + " 吨，" + ("pending".equals(status) ? "提交审批" : "草稿") + "）", "success");
        if ("pending".equals(status)) {
            // 提交审批走多级审批流（部门→公司），生成审批链（同属 contract 权限，直接调用）
            Map<String, Object> r = submitContractApproval(contract.get("id").toString());
            if (r.get("error") != null) { commit(); Map<String, Object> e = new LinkedHashMap<>(); e.put("error", r.get("error")); e.put("id", contract.get("id")); return e; }
            commit();
            return Map.of("ok", true, "id", contract.get("id"), "contract", contract);
        }
        commit();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("id", contract.get("id"));
        r.put("contract", contract);
        return r;
    }

    /** 新建运输计划（等价 createPlan） */
    public Map<String, Object> createPlan(Map<String, Object> payload) {
        ctx.requireAction("plan");
        Map<String, Object> c = ctx.contractOf(str(payload, "contractId"));
        if (c == null) return Map.of("error", "请选择合同");
        if (!"executing".equals(c.get("status"))) return Map.of("error", "合同 " + c.get("id") + " 当前非\"执行中\"状态，不可新建计划");
        double quantity = FlowCtx.num(payload.get("quantity"));
        if (quantity <= 0) return Map.of("error", "批次数量须大于 0");
        double remain = contractRemaining(str(c, "id"));
        if (quantity > remain) return Map.of("error", "批次数量超出合同剩余可计划量（剩余 " + remain + " 吨）");
        List<Map<String, Object>> plans = ctx.store().list("plans");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", ctx.genId("YH-", 4, plans));
        p.put("contractId", c.get("id"));
        p.put("commodityId", c.get("commodityId"));
        p.put("quantity", quantity);
        p.put("loadTerminalId", c.get("loadTerminalId"));
        p.put("unloadTerminalId", c.get("unloadTerminalId"));
        p.put("mode", c.get("mode"));
        p.put("planDate", payload.getOrDefault("planDate", ctx.nowPlusDays(1)));
        p.put("unitPrice", c.get("unitPrice"));
        p.put("status", "pending");
        p.put("progress", 0);
        p.put("remark", payload.getOrDefault("remark", ""));
        plans.add(0, p);
        ctx.logAction("运输计划", "新建计划", "计划 " + p.get("id") + " 创建（合同 " + c.get("id") + "，" + quantity + " 吨）", "success");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("id", p.get("id"));
        r.put("plan", p);
        return r;
    }

    /** 取消计划（等价 cancelPlan） */
    public Map<String, Object> cancelPlan(String planId) {
        ctx.requireAction("plan");
        Map<String, Object> p = ctx.planOf(planId);
        if (p == null) return Map.of("error", "计划不存在");
        if (!"pending".equals(p.get("status"))) return Map.of("error", "计划 " + planId + " 当前非\"待执行\"状态，无法取消");
        p.put("status", "cancelled");
        ctx.logAction("运输计划", "取消计划", "计划 " + planId + " 取消（" + (ctx.contractOf(str(p, "contractId")) != null ? ctx.contractOf(str(p, "contractId")).get("name") : str(p, "contractId")) + "）", "success");
        commit();
        return Map.of("ok", true);
    }

    /* ===== 合同审批与变更（多级审批：部门审批 → 公司审批） ===== */

    private List<Map<String, Object>> buildApprovalChain() {
        List<Map<String, Object>> chain = new ArrayList<>();
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("level", 1); s1.put("name", "部门审批"); s1.put("status", "pending"); s1.put("approver", ""); s1.put("comment", ""); s1.put("time", null);
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("level", 2); s2.put("name", "公司审批"); s2.put("status", "waiting"); s2.put("approver", ""); s2.put("comment", ""); s2.put("time", null);
        chain.add(s1); chain.add(s2);
        return chain;
    }

    /** 提交审批（等价 submitContractApproval）：draft → pending，生成审批链 */
    public Map<String, Object> submitContractApproval(String contractId) {
        ctx.requireAction("contract");
        Map<String, Object> c = ctx.contractOf(contractId);
        if (c == null) return Map.of("error", "合同不存在");
        if (!"draft".equals(c.get("status"))) return Map.of("error", "合同 " + c.get("id") + " 当前非\"草稿\"状态，无法提交审批");
        c.put("status", "pending");
        c.put("approvalChain", buildApprovalChain());
        c.put("submitTime", ctx.now());
        ctx.logAction("合同管理", "提交合同审批", "合同 " + c.get("id") + " 提交审批（部门审批 → 公司审批）", "success");
        ctx.notify("合同 " + c.get("id") + " 提交审批", "approval", "/contract", "请及时处理（部门审批 → 公司审批）", ctx.toRoles("contract-approve", "contract"));
        commit();
        return Map.of("ok", true);
    }

    /** 审批通过（等价 approveContract）：推进当前待审批层级；末级通过 → executing */
    @SuppressWarnings("unchecked")
    public Map<String, Object> approveContract(String contractId, String comment) {
        ctx.requireAction("contract-approve");
        Map<String, Object> c = ctx.contractOf(contractId);
        if (c == null) return Map.of("error", "合同不存在");
        List<Map<String, Object>> chain = (List<Map<String, Object>>) c.get("approvalChain");
        Map<String, Object> step = chain == null ? null : chain.stream().filter(s -> "pending".equals(s.get("status"))).findFirst().orElse(null);
        if (step == null) return Map.of("error", "合同 " + c.get("id") + " 无待审批层级");
        step.put("status", "approved");
        step.put("approver", ctx.op().getName());
        step.put("comment", comment == null || comment.isBlank() ? "同意" : comment);
        step.put("time", ctx.now());
        Map<String, Object> next = chain.stream().filter(s -> "waiting".equals(s.get("status"))).findFirst().orElse(null);
        if (next != null) {
            next.put("status", "pending");
            ctx.logAction("合同管理", "合同审批", "合同 " + c.get("id") + " " + step.get("name") + "通过（" + step.get("approver") + "），进入" + next.get("name"), "success");
            ctx.notify("合同 " + c.get("id") + " " + step.get("name") + "通过", "approval", "/contract", "进入" + next.get("name") + "（审批人 " + step.get("approver") + "）", ctx.toRoles("contract-approve", "contract"));
            commit();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true); r.put("final", false); r.put("step", step.get("name"));
            return r;
        }
        c.put("status", "executing");
        c.put("startDate", ctx.today());
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("approver", ctx.op().getName()); approval.put("time", step.get("time")); approval.put("comment", step.get("comment"));
        c.put("approval", approval);
        ctx.logAction("合同管理", "合同审批", "合同 " + c.get("id") + " 全级审批通过（末级：" + step.get("name") + " " + step.get("approver") + "），进入执行", "success");
        ctx.notify("合同 " + c.get("id") + " 全级审批通过", "approval", "/contract", "合同进入执行，可拆批计划", ctx.toRoles("contract-approve", "contract"));
        commit();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true); r.put("final", true); r.put("step", step.get("name"));
        return r;
    }

    /** 审批驳回（等价 rejectContract）：当前层级驳回 → 回草稿，后续层级取消 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> rejectContract(String contractId, String reason) {
        ctx.requireAction("contract-approve");
        Map<String, Object> c = ctx.contractOf(contractId);
        if (c == null) return Map.of("error", "合同不存在");
        List<Map<String, Object>> chain = (List<Map<String, Object>>) c.get("approvalChain");
        Map<String, Object> step = chain == null ? null : chain.stream().filter(s -> "pending".equals(s.get("status"))).findFirst().orElse(null);
        if (step == null) return Map.of("error", "合同 " + c.get("id") + " 无待审批层级");
        step.put("status", "rejected");
        step.put("approver", ctx.op().getName());
        step.put("comment", "驳回：" + reason);
        step.put("time", ctx.now());
        for (Map<String, Object> s : chain) if ("waiting".equals(s.get("status"))) s.put("status", "cancelled");
        c.put("status", "draft");
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("approver", ctx.op().getName()); approval.put("time", step.get("time")); approval.put("comment", "驳回：" + reason);
        c.put("approval", approval);
        ctx.logAction("合同管理", "合同审批", "合同 " + c.get("id") + " " + step.get("name") + "驳回：" + reason, "fail");
        ctx.notify("合同 " + c.get("id") + " 审批被驳回", "approval", "/contract", step.get("name") + "驳回：" + reason, ctx.toRoles("contract-approve", "contract"));
        commit();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true); r.put("step", step.get("name"));
        return r;
    }

    private void pushChange(Map<String, Object> c, String reason, String content) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) c.get("changes");
        if (changes == null) { changes = new ArrayList<>(); c.put("changes", changes); }
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("time", ctx.now()); ch.put("operator", ctx.op().getName()); ch.put("reason", reason); ch.put("content", content);
        changes.add(ch);
    }

    /** 合同变更字段应用（即时变更与改价审批通过后共用），返回变更描述列表 */
    private List<String> applyContractFields(Map<String, Object> c, Map<String, Object> fields) {
        List<String> changes = new ArrayList<>();
        if (fields.get("quantity") != null && FlowCtx.num(fields.get("quantity")) != FlowCtx.num(c.get("quantity"))) {
            changes.add("数量 " + c.get("quantity") + "→" + fields.get("quantity") + " 吨");
            c.put("quantity", FlowCtx.num(fields.get("quantity")));
        }
        if (fields.get("unitPrice") != null && FlowCtx.num(fields.get("unitPrice")) != FlowCtx.num(c.get("unitPrice"))) {
            changes.add("单价 " + c.get("unitPrice") + "→" + fields.get("unitPrice") + " 元/吨");
            c.put("unitPrice", FlowCtx.num(fields.get("unitPrice")));
        }
        if (fields.get("endDate") != null && !String.valueOf(fields.get("endDate")).equals(String.valueOf(c.get("endDate")))) {
            changes.add("截止日期 " + c.get("endDate") + "→" + fields.get("endDate"));
            c.put("endDate", fields.get("endDate"));
        }
        c.put("amount", Math.round(FlowCtx.num(c.get("quantity")) * FlowCtx.num(c.get("unitPrice"))));
        return changes;
    }

    /** 合同变更（等价 changeContract）：单价变更转改价审批；数量/截止日期即时生效 */
    public Map<String, Object> changeContract(String contractId, Map<String, Object> fields, String reason) {
        ctx.requireAction("contract");
        Map<String, Object> c = ctx.contractOf(contractId);
        if (c == null) return Map.of("error", "合同不存在");
        if (c.get("pendingChange") != null) return Map.of("error", "合同 " + c.get("id") + " 已有变更待审批，审批完成前不可提交新变更");
        if (fields.get("unitPrice") != null && FlowCtx.num(fields.get("unitPrice")) != FlowCtx.num(c.get("unitPrice"))) {
            Map<String, Object> pc = new LinkedHashMap<>();
            Map<String, Object> pf = new LinkedHashMap<>();
            pf.put("quantity", fields.get("quantity")); pf.put("unitPrice", fields.get("unitPrice")); pf.put("endDate", fields.get("endDate"));
            pc.put("fields", pf);
            pc.put("reason", reason == null ? "" : reason.trim());
            pc.put("createTime", ctx.now());
            pc.put("chain", buildApprovalChain());
            c.put("pendingChange", pc);
            ctx.logAction("合同管理", "提交改价审批", "合同 " + c.get("id") + " 改价提交审批：单价 " + c.get("unitPrice") + "→" + fields.get("unitPrice") + " 元/吨（" + (reason == null ? "" : reason) + "）", "success");
            ctx.notify("合同 " + c.get("id") + " 改价提交审批", "approval", "/contract", "单价 " + c.get("unitPrice") + "→" + fields.get("unitPrice") + " 元/吨，请及时处理（部门审批 → 公司审批）", ctx.toRoles("contract-approve", "contract"));
            commit();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("changed", false); r.put("pending", true); r.put("changes", List.of("单价 " + c.get("unitPrice") + "→" + fields.get("unitPrice") + " 元/吨（待审批）"));
            return r;
        }
        List<String> changes = applyContractFields(c, fields);
        if (changes.isEmpty()) { commit(); return Map.of("changed", false); }
        pushChange(c, reason, String.join("；", changes));
        ctx.logAction("合同管理", "合同变更", "合同 " + c.get("id") + " 变更：" + String.join("；", changes) + "（" + reason + "）", "success");
        commit();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("changed", true); r.put("changes", changes);
        return r;
    }

    /** 改价审批通过（等价 approveContractChange）：末级通过 → 应用变更（单价生效） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> approveContractChange(String contractId, String comment) {
        ctx.requireAction("contract-approve");
        Map<String, Object> c = ctx.contractOf(contractId);
        if (c == null) return Map.of("error", "合同不存在");
        Map<String, Object> pc = (Map<String, Object>) c.get("pendingChange");
        if (pc == null) return Map.of("error", "合同 " + c.get("id") + " 无待审批的变更");
        List<Map<String, Object>> chain = (List<Map<String, Object>>) pc.get("chain");
        Map<String, Object> step = chain == null ? null : chain.stream().filter(s -> "pending".equals(s.get("status"))).findFirst().orElse(null);
        if (step == null) return Map.of("error", "合同 " + c.get("id") + " 变更无待审批层级");
        step.put("status", "approved");
        step.put("approver", ctx.op().getName());
        step.put("comment", comment == null || comment.isBlank() ? "同意" : comment);
        step.put("time", ctx.now());
        Map<String, Object> next = chain.stream().filter(s -> "waiting".equals(s.get("status"))).findFirst().orElse(null);
        if (next != null) {
            next.put("status", "pending");
            ctx.logAction("合同管理", "改价审批", "合同 " + c.get("id") + " 改价" + step.get("name") + "通过（" + step.get("approver") + "），进入" + next.get("name"), "success");
            ctx.notify("合同 " + c.get("id") + " 改价" + step.get("name") + "通过", "approval", "/contract", "进入" + next.get("name") + "（审批人 " + step.get("approver") + "）", ctx.toRoles("contract-approve", "contract"));
            commit();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true); r.put("final", false); r.put("step", step.get("name"));
            return r;
        }
        Map<String, Object> pf = (Map<String, Object>) pc.get("fields");
        List<String> changes = applyContractFields(c, pf);
        c.put("pendingChange", null);
        pushChange(c, String.valueOf(pc.getOrDefault("reason", "改价")), String.join("；", changes) + "（改价审批通过）");
        ctx.logAction("合同管理", "改价审批", "合同 " + c.get("id") + " 改价全级审批通过（末级：" + step.get("name") + " " + step.get("approver") + "），变更生效：" + String.join("；", changes), "success");
        ctx.notify("合同 " + c.get("id") + " 改价审批通过", "approval", "/contract", "变更生效：" + String.join("；", changes) + "（仅影响未派车批次）", ctx.toRoles("contract-approve", "contract"));
        commit();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true); r.put("final", true); r.put("step", step.get("name")); r.put("changes", changes);
        return r;
    }

    /** 改价驳回（等价 rejectContractChange）：当前层级驳回即作废变更申请 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> rejectContractChange(String contractId, String reason) {
        ctx.requireAction("contract-approve");
        Map<String, Object> c = ctx.contractOf(contractId);
        if (c == null) return Map.of("error", "合同不存在");
        Map<String, Object> pc = (Map<String, Object>) c.get("pendingChange");
        if (pc == null) return Map.of("error", "合同 " + c.get("id") + " 无待审批的变更");
        List<Map<String, Object>> chain = (List<Map<String, Object>>) pc.get("chain");
        Map<String, Object> step = chain == null ? null : chain.stream().filter(s -> "pending".equals(s.get("status"))).findFirst().orElse(null);
        if (step == null) return Map.of("error", "合同 " + c.get("id") + " 变更无待审批层级");
        step.put("status", "rejected");
        step.put("approver", ctx.op().getName());
        step.put("comment", "驳回：" + reason);
        step.put("time", ctx.now());
        for (Map<String, Object> s : chain) if ("waiting".equals(s.get("status"))) s.put("status", "cancelled");
        Map<String, Object> pf = (Map<String, Object>) pc.get("fields");
        String summary = pf.get("unitPrice") != null ? "单价 " + c.get("unitPrice") + "→" + pf.get("unitPrice") + " 元/吨" : "合同变更";
        c.put("pendingChange", null);
        ctx.logAction("合同管理", "改价审批", "合同 " + c.get("id") + " 改价" + step.get("name") + "驳回：" + reason + "（" + summary + "，单价维持不变）", "fail");
        ctx.notify("合同 " + c.get("id") + " 改价被驳回", "approval", "/contract", step.get("name") + "驳回：" + reason, ctx.toRoles("contract-approve", "contract"));
        commit();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true); r.put("step", step.get("name"));
        return r;
    }

    /** 合同延期（等价 extendContract）：延长截止日期，记录变更历史 */
    public Map<String, Object> extendContract(String contractId, String newDate, String reason) {
        ctx.requireAction("contract");
        Map<String, Object> c = ctx.contractOf(contractId);
        if (c == null) return Map.of("error", "合同不存在");
        if (c.get("pendingChange") != null) return Map.of("error", "合同 " + c.get("id") + " 已有变更待审批，审批完成前不可延期");
        String old = String.valueOf(c.get("endDate"));
        c.put("endDate", newDate);
        pushChange(c, reason, "延期 " + old + " → " + newDate);
        ctx.logAction("合同管理", "合同延期", "合同 " + c.get("id") + " 延期至 " + newDate + "（" + reason + "）", "success");
        commit();
        return Map.of("ok", true);
    }

    /** 提前终止（等价 terminateContract）：executing → terminated，取消待执行计划，联动提前结算 */
    public Map<String, Object> terminateContract(String contractId, String reason, boolean settleNow) {
        ctx.requireAction("contract");
        Map<String, Object> c = ctx.contractOf(contractId);
        if (c == null) return Map.of("error", "合同不存在");
        c.put("status", "terminated");
        pushChange(c, reason, "提前终止（" + reason + "）");
        for (Map<String, Object> p : ctx.store().list("plans")) {
            if (c.get("id").equals(p.get("contractId")) && "pending".equals(p.get("status"))) p.put("status", "cancelled");
        }
        String billNo = null;
        if (settleNow) {
            List<String> keys = settlementService.settlementCandidates().stream()
                    .filter(g -> c.get("id").equals(g.get("contractId"))).map(g -> String.valueOf(g.get("key"))).collect(java.util.stream.Collectors.toList());
            if (!keys.isEmpty()) {
                List<Map<String, Object>> created = settlementService.doGenerateSettlements(keys);
                billNo = !created.isEmpty() ? String.valueOf(created.get(0).get("billNo")) : null;
            }
        }
        ctx.logAction("合同管理", "终止合同", "合同 " + c.get("id") + " 提前终止（" + reason + "）" + (billNo != null ? "，已完成车次生成提前结算单 " + billNo : ""), "success");
        ctx.notify("合同 " + c.get("id") + " 提前终止", "approval", "/contract", reason, ctx.toRoles("contract-approve", "contract"));
        commit();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true); r.put("billNo", billNo);
        return r;
    }

    /** 合同完结（等价 completeContract）：executing → completed（手动关单） */
    public Map<String, Object> completeContract(String contractId) {
        ctx.requireAction("contract");
        Map<String, Object> c = ctx.contractOf(contractId);
        if (c == null) return Map.of("error", "合同不存在");
        if (!"executing".equals(c.get("status"))) return Map.of("error", "合同 " + c.get("id") + " 当前非\"执行中\"状态，无法完结");
        long activePlans = ctx.store().list("plans").stream()
                .filter(p -> c.get("id").equals(p.get("contractId")) && !"cancelled".equals(p.get("status")) && !"completed".equals(p.get("status"))).count();
        if (activePlans > 0) return Map.of("error", "合同 " + c.get("id") + " 尚有 " + activePlans + " 个未完结计划（待执行/执行中），无法完结");
        c.put("status", "completed");
        c.put("progress", 100);
        pushChange(c, "合同完结", "计划全部完成，手动完结合同");
        ctx.logAction("合同管理", "合同完结", "合同 " + c.get("id") + " 手动完结（计划全部完成，进度置 100%）", "success");
        ctx.notify("合同 " + c.get("id") + " 已完结", "approval", "/contract", "计划全部完成，合同手动关单", ctx.toRoles("contract-approve", "contract"));
        commit();
        return Map.of("ok", true);
    }

    /** 合同归档（等价 archiveContract）：completed → archived */
    public Map<String, Object> archiveContract(String contractId) {
        ctx.requireAction("contract");
        Map<String, Object> c = ctx.contractOf(contractId);
        if (c == null) return Map.of("error", "合同不存在");
        c.put("status", "archived");
        pushChange(c, "合同执行完毕", "归档");
        ctx.logAction("合同管理", "合同归档", "合同 " + c.get("id") + " 归档", "success");
        commit();
        return Map.of("ok", true);
    }

    /* ================= 客户运输需求（客户门户） ================= */
    /** 客户发起运输需求（等价 submitTransportRequest） */
    public Map<String, Object> submitTransportRequest(String customerId, Map<String, Object> p) {
        ctx.requireAction("customer-request");
        Map<String, Object> c = ctx.byId("customers", customerId);
        if (c == null) return Map.of("error", "当前账号未绑定客户，无法发起运输需求");
        if ("frozen".equals(c.get("status"))) return Map.of("error", "客户 " + c.get("name") + " 已冻结，无法发起运输需求");
        if (p.get("commodityId") == null || p.get("loadTerminalId") == null || p.get("unloadTerminalId") == null || p.get("consigneeId") == null)
            return Map.of("error", "请完整填写商品、装/卸货场站与收货方");
        double quantity = FlowCtx.num(p.get("quantity"));
        if (quantity <= 0) return Map.of("error", "计划数量须大于 0");
        List<Map<String, Object>> reqs = ctx.store().list("transportRequests");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", ctx.genId("YS-", 4, reqs));
        r.put("customerId", c.get("id"));
        r.put("consigneeId", p.get("consigneeId"));
        r.put("commodityId", p.get("commodityId"));
        r.put("quantity", quantity);
        r.put("loadTerminalId", p.get("loadTerminalId"));
        r.put("unloadTerminalId", p.get("unloadTerminalId"));
        r.put("mode", p.get("mode") != null ? p.get("mode") : "公路");
        r.put("expectDate", p.get("expectDate") != null ? p.get("expectDate") : ctx.nowPlusDays(14));
        r.put("unitPrice", FlowCtx.num(p.get("unitPrice")));
        r.put("remark", p.get("remark") != null ? String.valueOf(p.get("remark")) : "");
        r.put("status", "pending");
        r.put("createTime", ctx.now());
        r.put("contractId", null);
        r.put("rejectReason", "");
        reqs.add(0, r);
        Map<String, Object> cm = ctx.byId("commodities", String.valueOf(p.get("commodityId")));
        ctx.logAction("客户门户", "发起运输需求", "客户 " + c.get("name") + " 发起运输需求 " + r.get("id") + "（" + (cm != null ? cm.get("name") : "") + " " + quantity + " 吨）", "success");
        ctx.notify("客户发起运输需求", "request", "/contract", "客户 " + c.get("name") + " 需求 " + r.get("id") + "，请及时处理", ctx.toRoles("contract"));
        commit();
        return r;
    }

    /** 运输需求转合同草稿（等价 convertRequestToContract） */
    public Map<String, Object> convertRequestToContract(String requestId, Map<String, Object> fields) {
        ctx.requireAction("contract");
        Map<String, Object> r = ctx.byId("transportRequests", requestId);
        if (r == null) return Map.of("error", "运输需求不存在");
        if (!"pending".equals(r.get("status"))) return Map.of("error", "运输需求 " + r.get("id") + " 当前非\"待处理\"状态，无法转换");
        Map<String, Object> c = ctx.byId("customers", String.valueOf(r.get("customerId")));
        Map<String, Object> consignee = ctx.byId("customers", String.valueOf(r.get("consigneeId")));
        Map<String, Object> commodity = ctx.byId("commodities", String.valueOf(r.get("commodityId")));
        double quantity = fields.get("quantity") != null ? FlowCtx.num(fields.get("quantity")) : FlowCtx.num(r.get("quantity"));
        double unitPrice = fields.get("unitPrice") != null ? FlowCtx.num(fields.get("unitPrice")) : FlowCtx.num(r.get("unitPrice"));
        List<Map<String, Object>> contracts = ctx.store().list("contracts");
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("id", ctx.genId("HT-", 4, contracts));
        contract.put("name", (c != null ? c.get("name") : "") + "→" + (consignee != null ? consignee.get("name") : "") + " " + (commodity != null ? commodity.get("name") : "") + "运输合同");
        contract.put("shipperId", r.get("customerId"));
        contract.put("consigneeId", r.get("consigneeId"));
        contract.put("commodityId", r.get("commodityId"));
        contract.put("mode", r.get("mode"));
        contract.put("loadTerminalId", r.get("loadTerminalId"));
        contract.put("unloadTerminalId", r.get("unloadTerminalId"));
        contract.put("quantity", quantity);
        contract.put("unitPrice", unitPrice);
        contract.put("amount", Math.round(quantity * unitPrice));
        contract.put("paymentDays", fields.get("paymentDays") != null ? FlowCtx.num(fields.get("paymentDays")) : 30);
        contract.put("startDate", ctx.today());
        contract.put("endDate", fields.get("endDate") != null ? fields.get("endDate") : ctx.nowPlusDays(180));
        contract.put("signDate", ctx.today());
        contract.put("status", "draft");
        contract.put("progress", 0);
        contract.put("approvalChain", null);
        contract.put("contact", c != null ? c.get("contact") : "—");
        contract.put("phone", c != null ? c.get("phone") : "—");
        contract.put("remark", "由客户运输需求 " + r.get("id") + " 生成" + (r.get("remark") != null && !String.valueOf(r.get("remark")).isBlank() ? "；" + r.get("remark") : ""));
        contract.put("source", "request");
        contract.put("requestId", r.get("id"));
        contracts.add(0, contract);
        r.put("status", "converted");
        r.put("contractId", contract.get("id"));
        ctx.logAction("合同管理", "需求转合同", "运输需求 " + r.get("id") + " 转为合同草稿 " + contract.get("id") + "（" + contract.get("name") + "）", "success");
        ctx.notify("运输需求转合同", "request", "/contract", "需求 " + r.get("id") + " 转为合同草稿 " + contract.get("id"), ctx.toRoles("contract"));
        commit();
        return contract;
    }

    /** 驳回运输需求（等价 rejectTransportRequest） */
    public Map<String, Object> rejectTransportRequest(String requestId, String reason) {
        ctx.requireAction("contract");
        Map<String, Object> r = ctx.byId("transportRequests", requestId);
        if (r == null) return Map.of("error", "运输需求不存在");
        if (!"pending".equals(r.get("status"))) return Map.of("error", "运输需求 " + r.get("id") + " 当前非\"待处理\"状态，无法驳回");
        r.put("status", "rejected");
        r.put("rejectReason", reason == null || reason.isBlank() ? "未通过" : reason);
        ctx.logAction("合同管理", "驳回需求", "运输需求 " + r.get("id") + " 驳回：" + r.get("rejectReason"), "fail");
        ctx.notify("运输需求被驳回", "request", "/contract", "需求 " + r.get("id") + "：" + r.get("rejectReason"), ctx.toRoles("contract"));
        commit();
        return Map.of("ok", true);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
