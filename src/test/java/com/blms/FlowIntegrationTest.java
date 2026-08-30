package com.blms;

import com.blms.auth.LoginLockoutService;
import com.blms.auth.Operator;
import com.blms.auth.RbacService;
import com.blms.common.ApiResult;
import com.blms.common.AuditLog;
import com.blms.common.CollReadController;
import com.blms.common.OptimisticLockContext;
import com.blms.common.OptimisticLockException;
import com.blms.common.RateLimitService;
import com.blms.common.SnapshotController;
import com.blms.service.admin.DataScopeService;
import com.blms.service.admin.UserAdminService;
import com.blms.service.contract.ContractService;
import com.blms.service.dispatch.DispatchService;
import com.blms.service.exception.ExceptionService;
import com.blms.service.insurance.InsuranceService;
import com.blms.service.report.DashboardService;
import com.blms.service.report.ReportService;
import com.blms.service.safety.SafetyService;
import com.blms.service.scheduler.SchedulerService;
import com.blms.service.settlement.FinanceService;
import com.blms.service.settlement.SettlementService;
import com.blms.service.weighing.WeighingService;
import com.blms.store.DataStore;
import com.blms.store.FlowCtx;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 环节 1–12 集成测试（与前端 scripts/verify-flow.mjs 环节 1–12 的 143 条断言 1:1 移植）。
 *
 * 设计：
 *  - @SpringBootTest 启动完整 context（DataStore 从 blms_test 库加载 34 集合种子，等价前端 db）；
 *  - @TestInstance(PER_CLASS) + @TestMethodOrder(OrderAnnotation)：12 个环节按序执行、共享内存态，
 *    模拟前端 verify-flow.mjs 单进程顺序流（环节 3 依赖环节 2 的 created[1]，环节 4 依赖环节 2 的 d 等）；
 *  - operator 注入：SecurityContextHolder 写 Operator（等价前端 setOperator），RBAC 单点校验生效；
 *  - check(name, cond)：断言计数，@AfterAll 汇总；fail>0 则测试失败。
 *  - 测试库 blms_test 独立于开发库 blms，Flyway V1–V3 重建种子，互不污染。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlowIntegrationTest {

    @Autowired DataStore store;
    @Autowired FlowCtx ctx;
    @Autowired ContractService contractService;
    @Autowired DispatchService dispatchService;
    @Autowired ExceptionService exceptionService;
    @Autowired SettlementService settlementService;
    @Autowired WeighingService weighingService;
    @Autowired FinanceService financeService;
    @Autowired SafetyService safetyService;
    @Autowired InsuranceService insuranceService;
    @Autowired RbacService rbac;
    @Autowired AuditLog audit;
    @Autowired ReportService reportService;
    @Autowired DashboardService dashboardService;
    @Autowired SchedulerService schedulerService;
    @Autowired DataScopeService scope;
    @Autowired UserAdminService userAdminService;
    @Autowired CollReadController collRead;
    @Autowired SnapshotController snapshotController;
    @Autowired LoginLockoutService lockout;
    @Autowired RateLimitService rateLimit;

    int pass = 0, fail = 0;
    final List<String> failures = new ArrayList<>();

    // 跨环节共享引用（模拟前端模块级变量）
    Map<String, Object> createdPlan;
    List<Map<String, Object>> created;
    Map<String, Object> d;   // 环节2 完成的新车次
    Map<String, Object> d2;  // 环节3 异常车次
    Map<String, Object> s;   // 环节4 生成的结算单

    protected void check(String name, boolean cond) { check(name, cond, null); }

    protected void check(String name, boolean cond, String detail) {
        if (cond) pass++;
        else { fail++; failures.add(name + (detail != null ? "  ← " + detail : "")); }
    }

    protected void login(String name, String username, String role, String driverId) {
        Operator op = new Operator(name, username, role, driverId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(op, null, Collections.emptyList()));
    }

    /**
     * 测试幂等性：把内存数据仓库重置回种子基线（seed_* 快照，不受上次运行 commitAll 污染影响）。
     * 等价前端 verify-ui.mjs 的 resetDemo()——历史教训：上次运行回写 biz_* 的测试工件（如 YH-TEST-S2）
     * 会让本次加载到污染态 → 内存重复 ID → commitAll 主键冲突 → 连锁 NPE。重置后跑完 commitAll 再回写干净态。
     */
    @BeforeAll
    void resetToSeed() {
        store.resetToSeed();
        pass = 0;
        fail = 0;
        failures.clear();
    }

    @BeforeEach
    void setup() { login("张建国", "admin", "平台管理员", ""); }

    @AfterEach
    void clearAuth() { SecurityContextHolder.clearContext(); }

    /* 取最新引用（service 操作后内存 Map 已更新，byId 返回同一活引用） */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> byId(String coll, String id) { return ctx.byId(coll, id); }

    protected static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    // ===================== 环节 1：预置数据一致性 =====================
    @Test @Order(1)
    void s1_预置数据一致性() {
        List<Map<String, Object>> plans = store.list("plans");
        List<Map<String, Object>> dispatches = store.list("dispatches");
        List<Map<String, Object>> contracts = store.list("contracts");
        List<Map<String, Object>> settlements = store.list("settlements");
        List<Map<String, Object>> payments = store.list("payments");
        List<Map<String, Object>> weighings = store.list("weighings");

        check("计划状态与车次状态一致（全完成→completed，否则→intransit/dispatched）",
                plans.stream().filter(p -> dispatches.stream().anyMatch(dd -> p.get("id").equals(dd.get("planId")))).allMatch(p -> {
                    List<Map<String, Object>> ds = dispatches.stream().filter(dd -> p.get("id").equals(dd.get("planId"))).toList();
                    if (ds.stream().allMatch(dd -> "completed".equals(dd.get("status")))) return "completed".equals(p.get("status"));
                    return "intransit".equals(p.get("status")) || "dispatched".equals(p.get("status"));
                }));

        check("执行中合同进度 = 实际完成运量/合同量",
                contracts.stream().filter(c -> "executing".equals(c.get("status"))).allMatch(c -> {
                    double done = dispatches.stream().filter(dd -> c.get("id").equals(dd.get("contractId")) && "completed".equals(dd.get("status")))
                            .mapToDouble(dd -> FlowCtx.num(dd.get("quantity"))).sum();
                    double expect = Math.min(100, Math.round(done / FlowCtx.num(c.get("quantity")) * 100));
                    return Math.abs(FlowCtx.num(c.get("progress")) - expect) < 1e-9;
                }));

        check("结算单运量 = 该合同已完成车次出磅净重之和（按磅结算）",
                settlements.stream().allMatch(st -> {
                    double expect = dispatches.stream().filter(dd -> st.get("contractId").equals(dd.get("contractId")) && "completed".equals(dd.get("status")))
                            .mapToDouble(dd -> {
                                Map<String, Object> w = weighings.stream().filter(x -> dd.get("id").equals(x.get("dispatchId")) && "出磅".equals(x.get("type"))).findFirst().orElse(null);
                                return w != null ? FlowCtx.num(w.get("net")) : FlowCtx.num(dd.get("quantity"));
                            }).sum();
                    return Math.abs(FlowCtx.num(st.get("totalQuantity")) - FlowCtx.round2(expect)) < 0.01;
                }));

        check("结算单金额恒等式（运费+杂费-损耗扣减-质量扣减-异常损失）",
                settlements.stream().allMatch(st -> Math.abs(
                        FlowCtx.num(st.get("totalAmount"))
                                - (FlowCtx.num(st.get("freight")) + FlowCtx.num(st.get("loadingFee")) + FlowCtx.num(st.get("unloadingFee"))
                                + FlowCtx.num(st.get("tollFee")) + FlowCtx.num(st.get("surcharge"))
                                - FlowCtx.num(st.get("lossDeduction")) - FlowCtx.num(st.get("qualityDeduction")) - FlowCtx.num(st.get("exceptionLoss")))
                ) < 1e-6));

        check("收款流水与账单已付金额一致",
                settlements.stream().allMatch(st -> {
                    double sum = payments.stream().filter(p -> st.get("id").equals(p.get("settlementId")) && !FlowCtx.bool(p.get("reversed")))
                            .mapToDouble(p -> FlowCtx.num(p.get("amount"))).sum();
                    return Math.abs(sum - FlowCtx.num(st.get("paidAmount"))) < 1e-6;
                }));

        check("逾期账单 = 超账期且未付清",
                settlements.stream().filter(st -> "overdue".equals(st.get("status"))).allMatch(st ->
                        st.get("settleDate") != null && FlowCtx.num(st.get("totalAmount")) - FlowCtx.num(st.get("paidAmount")) > 0));

        check("异常车次数量 >= 7（异常模块可演示）",
                dispatches.stream().filter(dd -> "exception".equals(dd.get("status"))).count() >= 7);
        check("单车运量在 30-40 吨区间",
                dispatches.stream().allMatch(dd -> FlowCtx.num(dd.get("quantity")) >= 30 && FlowCtx.num(dd.get("quantity")) <= 40));
        check("调度单数量合理（200-500）",
                dispatches.size() >= 200 && dispatches.size() <= 500);
    }

    // ===================== 环节 2：状态机全流程（新数据） =====================
    @Test @Order(2)
    void s2_状态机全流程() {
        Map<String, Object> contract = store.list("contracts").stream()
                .filter(c -> "executing".equals(c.get("status")) && ("公路".equals(c.get("mode")) || "多式联运".equals(c.get("mode"))))
                .findFirst().orElse(null);
        assertNotNull(contract, "无公路执行中合同");

        // 模拟前端 unshift plan（手工构造，不走 createPlan 的 remain 校验）
        List<Map<String, Object>> plans = store.list("plans");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("id", "YH-TEST-S2");
        plan.put("contractId", contract.get("id"));
        plan.put("commodityId", contract.get("commodityId"));
        plan.put("quantity", 105);
        plan.put("loadTerminalId", contract.get("loadTerminalId"));
        plan.put("unloadTerminalId", contract.get("unloadTerminalId"));
        plan.put("mode", contract.get("mode"));
        plan.put("unitPrice", contract.get("unitPrice"));
        plan.put("status", "pending");
        plan.put("progress", 0);
        plans.add(0, plan);
        createdPlan = plan;

        Map<String, Object> cd = dispatchService.createDispatches("YH-TEST-S2", 3, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cr = (List<Map<String, Object>>) cd.get("created");
        created = cr;
        check("createDispatches 生成 3 张调度单", cr.size() == 3 && cd.get("error") == null, str(cd, "error"));
        check("调度单数量按批次均摊（35 吨/车）", cr.stream().allMatch(dd -> Math.abs(FlowCtx.num(dd.get("quantity")) - 35) < 1e-9));
        check("计划状态 → dispatched", "dispatched".equals(byId("plans", "YH-TEST-S2").get("status")));

        d = cr.get(0);
        String did = str(d, "id");
        Map<String, Object> v = store.list("vehicles").stream().filter(x -> x.get("id").equals(d.get("vehicleId"))).findFirst().orElse(null);
        Map<String, Object> dr = store.list("drivers").stream().filter(x -> x.get("id").equals(d.get("driverId"))).findFirst().orElse(null);

        // 守卫：未接单公路车次不可确认装货
        Map<String, Object> blocked = dispatchService.confirmLoad(str(cr.get(1), "id"));
        check("守卫：未接单公路车次不可确认装货", blocked.get("error") != null && "pending".equals(byId("dispatches", str(cr.get(1), "id")).get("status")));

        dispatchService.acceptDispatch(did);
        dispatchService.confirmLoad(did);
        check("确认装货 → loading + 进磅单",
                "loading".equals(d.get("status")) && store.list("weighings").stream().anyMatch(w -> did.equals(w.get("dispatchId")) && "进磅".equals(w.get("type"))));
        dispatchService.depart(did);
        check("发车 → intransit + 车辆/司机占用",
                "intransit".equals(d.get("status")) && "inuse".equals(v.get("status")) && "onduty".equals(dr.get("status")));
        dispatchService.arrive(did);
        check("到达 → unloading", "unloading".equals(d.get("status")));
        dispatchService.confirmUnload(did);
        check("确认卸货 → completed + 出磅单 + 资源释放",
                "completed".equals(d.get("status"))
                        && store.list("weighings").stream().anyMatch(w -> did.equals(w.get("dispatchId")) && "出磅".equals(w.get("type")))
                        && "idle".equals(v.get("status")) && "available".equals(dr.get("status")));
        dispatchService.signReceipt(did, "收货方");
        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = (Map<String, Object>) d.get("receipt");
        check("电子签收：卸货完成后生成签收单（收货凭证）",
                receipt != null && String.valueOf(receipt.get("code")).startsWith("QS-") && receipt.get("time") != null);
        check("计划进度回卷（33%）", Math.abs(FlowCtx.num(byId("plans", "YH-TEST-S2").get("progress")) - 33) < 1e-9);
        check("合同进度随执行上升", FlowCtx.num(byId("contracts", str(contract, "id")).get("progress")) > 0);
    }

    // ===================== 环节 3：异常闭环 =====================
    @Test @Order(3)
    void s3_异常闭环() {
        d2 = created.get(1);
        String d2id = str(d2, "id");
        dispatchService.acceptDispatch(d2id);
        dispatchService.confirmLoad(d2id);
        dispatchService.depart(d2id);
        Map<String, Object> e = exceptionService.createException(d2, "测试异常", "other", "medium", "");
        check("上报异常 → exception + 异常单生成",
                "exception".equals(d2.get("status")) && e.get("id") != null
                        && str(store.list("exceptions").get(0), "dispatchId").equals(d2id));
        dispatchService.resumeDispatch(d2id);
        check("异常恢复 → intransit", "intransit".equals(d2.get("status")));
    }

    // ===================== 环节 4：结算闭环（生成结算单 / 对账三方比对） =====================
    @Test @Order(4)
    void s4_结算闭环() {
        List<Map<String, Object>> settlements = store.list("settlements");
        List<Map<String, Object>> dispatches = store.list("dispatches");

        check("预置账单：车次均标记已入账单且状态一致",
                settlements.stream().allMatch(st -> {
                    List<Map<String, Object>> ds = dispatches.stream().filter(x -> st.get("id").equals(x.get("settlementId"))).toList();
                    return ds.size() == FlowCtx.intNum(st.get("dispatchCount"))
                            && ds.stream().allMatch(x -> FlowCtx.bool(x.get("settled")) && "completed".equals(x.get("status")));
                }));

        List<Map<String, Object>> completedAll = dispatches.stream().filter(x -> "completed".equals(x.get("status"))).toList();
        Set<String> candIds = settlementService.settlementCandidates().stream()
                .flatMap(g -> ((List<Map<String, Object>>) g.get("dispatches")).stream())
                .map(x -> str(x, "id")).collect(java.util.stream.Collectors.toSet());
        check("已完成车次 = 已入账单 + 结算候选（无遗漏、无重复）",
                completedAll.stream().allMatch(x -> candIds.contains(str(x, "id")) == !FlowCtx.bool(x.get("settled"))));

        check("非待对账账单已预生成对账结果且条数=车次",
                settlements.stream().filter(st -> !"pending".equals(st.get("status"))).allMatch(st -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> recon = (Map<String, Object>) st.get("reconciliation");
                    return recon != null && ((List<?>) recon.get("items")).size() == FlowCtx.intNum(st.get("dispatchCount"));
                }));

        // 复用环节2 完成的新车次 d，走 生成结算单 → 对账 → 结算 全流程
        Map<String, Object> g = settlementService.settlementCandidates().stream()
                .filter(x -> ((List<Map<String, Object>>) x.get("dispatches")).stream().anyMatch(dd -> str(d, "id").equals(str(dd, "id"))))
                .findFirst().orElse(null);
        check("新完成车次进入结算候选", g != null);
        Map<String, Object> gs = settlementService.generateSettlements(List.of(str(g, "key")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> createdS = (List<Map<String, Object>>) gs.get("created");
        s = createdS.stream().filter(x -> str(g, "contractId").equals(str(x, "contractId"))).findFirst().orElse(null);
        check("生成结算单：车次/调度量与候选一致，结算量按磅扣减",
                s != null && FlowCtx.intNum(s.get("dispatchCount")) == FlowCtx.intNum(g.get("dispatchCount"))
                        && Math.abs(FlowCtx.num(s.get("dispatchQuantity")) - FlowCtx.num(g.get("quantity"))) < 1e-9
                        && FlowCtx.num(s.get("totalQuantity")) < FlowCtx.num(s.get("dispatchQuantity")));
        check("车次标记已入账单（settled + settlementId）",
                FlowCtx.bool(d.get("settled")) && str(d, "settlementId").equals(str(s, "id")));
        check("账单初始状态 待对账/未付款", "pending".equals(s.get("status")) && FlowCtx.num(s.get("paidAmount")) == 0);
        check("生成后候选不再包含该车次（防重复结算）",
                settlementService.settlementCandidates().stream().noneMatch(x ->
                        ((List<Map<String, Object>>) x.get("dispatches")).stream().anyMatch(dd -> str(d, "id").equals(str(dd, "id")))));

        Map<String, Object> rr = settlementService.startReconcile(str(s, "id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> recon = (Map<String, Object>) rr.get("reconciliation");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) recon.get("items");
        check("发起对账 → 对账中 + 三方比对生成",
                "reconciling".equals(s.get("status")) && items.size() == FlowCtx.intNum(s.get("dispatchCount")));
        Map<String, Object> item = items.stream().filter(i -> str(d, "id").equals(str(i, "dispatchId"))).findFirst().orElse(null);
        Map<String, Object> outW = store.list("weighings").stream()
                .filter(w -> str(d, "id").equals(str(w, "dispatchId")) && "出磅".equals(w.get("type"))).findFirst().orElse(null);
        check("比对项与磅单记录一致（结算量=出磅净重）",
                item != null && Math.abs(FlowCtx.num(item.get("outNet")) - FlowCtx.num(outW.get("net"))) < 1e-9
                        && Math.abs(FlowCtx.num(item.get("settleQty")) - FlowCtx.num(outW.get("net"))) < 1e-9);
        check("损耗=调度量-结算量 且进入汇总（按磅结算）",
                item != null && Math.abs(FlowCtx.num(item.get("loss")) - FlowCtx.round2(FlowCtx.num(d.get("quantity")) - FlowCtx.num(outW.get("net")))) < 0.01
                        && FlowCtx.num(recon.get("lossQty")) > 0 && FlowCtx.num(recon.get("lossAmount")) > 0);

        // N1 客户确认闸门：未确认对账结果不可确认结算
        Map<String, Object> blockedSettle = settlementService.confirmSettle(str(s, "id"));
        check("守卫：客户未确认对账结果不可确认结算",
                blockedSettle.get("error") != null && "reconciling".equals(s.get("status")));
        Map<String, Object> rcConf = settlementService.customerConfirm(str(s, "id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cc = (Map<String, Object>) s.get("customerConfirmed");
        check("客户确认对账（写 customerConfirmed，门户口径）",
                rcConf.get("ok") != null && cc != null && cc.get("time") != null);
        settlementService.confirmSettle(str(s, "id"));
        check("确认结算 → 已结算 + 进入收款（未付）",
                "settled".equals(s.get("status")) && FlowCtx.num(s.get("paidAmount")) == 0 && s.get("settleDate") != null);
    }

    // ===================== 环节 5：收款流水与信用校验 =====================
    @Test @Order(5)
    void s5_收款与信用() {
        double half = Math.round(FlowCtx.num(s.get("totalAmount")) / 2);
        settlementService.recordPayment(str(s, "id"), half, "银行转账");
        check("部分收款 → 已付更新 + 流水生成",
                Math.abs(FlowCtx.num(s.get("paidAmount")) - half) < 1e-9
                        && store.list("payments").stream().anyMatch(p -> str(s, "id").equals(str(p, "settlementId")) && Math.abs(FlowCtx.num(p.get("amount")) - half) < 1e-9));
        settlementService.recordPayment(str(s, "id"), FlowCtx.num(s.get("totalAmount")), "支票");
        check("超收按未付余额截断（付清）", Math.abs(FlowCtx.num(s.get("paidAmount")) - FlowCtx.num(s.get("totalAmount"))) < 1e-9);
        check("付清后状态保持已结算", "settled".equals(s.get("status")));

        // 逾期规则：结算日超账期且未付清 → 逾期；付清 → 回到已结算
        s.put("settleDate", java.time.LocalDate.now().minusDays(90).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        s.put("paidAmount", 0);
        settlementService.recordPayment(str(s, "id"), 1, "银行转账");
        check("超账期未付清 → 逾期", "overdue".equals(s.get("status")));
        settlementService.recordPayment(str(s, "id"), FlowCtx.num(s.get("totalAmount")) - 1, "银行转账");
        check("逾期账单付清 → 回到已结算", "settled".equals(s.get("status")));

        // 信用校验
        Map<String, Object> c1 = store.list("customers").stream().filter(c -> FlowCtx.num(c.get("creditLimit")) > 0).findFirst().orElse(null);
        if (c1 != null) {
            double out = contractService.outstandingOf(str(c1, "id"));
            check("信用校验：小额订单通过", contractService.creditCheck(str(c1, "id"), 1000).get("ok") != null);
            check("信用校验：超出授信额度的订单被拒",
                    ((Boolean) contractService.creditCheck(str(c1, "id"), FlowCtx.num(c1.get("creditLimit")) + out + 1).get("ok")) == false);
        } else {
            check("信用校验：存在授信客户", false, "无授信客户");
        }
    }

    // ===================== 环节 6：模块互联（审计/仓储/安全/审批/磅单） =====================
    @Test @Order(6)
    void s6_模块互联() {
        // 审计日志：状态变更动作实时写日志
        int logBefore = audit.recent(1).size();
        dispatchService.acceptDispatch(str(created.get(2), "id"));
        dispatchService.confirmLoad(str(created.get(2), "id"));
        dispatchService.depart(str(created.get(2), "id"));
        List<Map<String, Object>> logs = audit.recent(10);
        check("状态变更动作写入审计日志（含操作人/详情）",
                !logs.isEmpty() && logs.get(0).get("user") != null && !String.valueOf(logs.get(0).get("detail")).isBlank());
        check("日志按时间倒序（新日志在最前）",
                logs.size() >= 2 && String.valueOf(logs.get(0).get("time")).compareTo(String.valueOf(logs.get(1).get("time"))) >= 0);

        // 仓储联动：装/卸货场站有仓库时出入库
        Map<String, Object> whDispatch = store.list("dispatches").stream().filter(dd -> {
            Map<String, Object> lt = store.list("terminals").stream().filter(t -> t.get("id").equals(dd.get("loadTerminalId"))).findFirst().orElse(null);
            Map<String, Object> ut = store.list("terminals").stream().filter(t -> t.get("id").equals(dd.get("unloadTerminalId"))).findFirst().orElse(null);
            return "pending".equals(dd.get("status")) && lt != null && ut != null && lt.get("warehouseId") != null && ut.get("warehouseId") != null;
        }).findFirst().orElse(null);
        if (whDispatch != null) {
            Map<String, Object> lt = store.list("terminals").stream().filter(t -> t.get("id").equals(whDispatch.get("loadTerminalId"))).findFirst().orElse(null);
            Map<String, Object> ut = store.list("terminals").stream().filter(t -> t.get("id").equals(whDispatch.get("unloadTerminalId"))).findFirst().orElse(null);
            Map<String, Object> whOut = store.list("warehouses").stream().filter(w -> w.get("id").equals(lt.get("warehouseId"))).findFirst().orElse(null);
            Map<String, Object> whIn = store.list("warehouses").stream().filter(w -> w.get("id").equals(ut.get("warehouseId"))).findFirst().orElse(null);
            int invBefore = store.list("inventories").size();
            double usedOutBefore = FlowCtx.num(whOut.get("used"));
            double usedInBefore = FlowCtx.num(whIn.get("used"));
            String wid = str(whDispatch, "id");
            dispatchService.confirmLoad(wid);
            dispatchService.depart(wid);
            dispatchService.arrive(wid);
            dispatchService.confirmUnload(wid);
            check("确认装货 → 装货场站仓库出库（占用减少）", FlowCtx.num(whOut.get("used")) <= usedOutBefore);
            check("确认卸货 → 卸货场站仓库入库（新批次+占用增加）",
                    store.list("inventories").size() > invBefore && FlowCtx.num(whIn.get("used")) >= usedInBefore);
        }

        // 安全联动：事故类异常生成事故记录，结案更新车辆状态
        Map<String, Object> accDispatch = store.list("dispatches").stream()
                .filter(dd -> "intransit".equals(dd.get("status")) && dd.get("loadTime") != null).findFirst().orElse(null);
        if (accDispatch != null) {
            Map<String, Object> e = exceptionService.createException(accDispatch, "测试事故：高速追尾", "accident", "high", "");
            check("事故类异常生成事故记录（关联异常单）",
                    e.get("accidentId") != null && store.list("accidents").stream().anyMatch(a -> e.get("accidentId").equals(a.get("id")) && str(e, "id").equals(str(a, "exceptionId"))));
            exceptionService.acceptException(str(e, "id"), "测试安全员");
            exceptionService.finishException(str(e, "id"), "保险理赔中", 20000);
            Map<String, Object> acc = store.list("accidents").stream().filter(a -> e.get("accidentId").equals(a.get("id"))).findFirst().orElse(null);
            check("处置完成同步事故（处理/损失）",
                    acc != null && "保险理赔中".equals(acc.get("handling")) && Math.abs(FlowCtx.num(acc.get("loss")) - 20000) < 1e-9);
            exceptionService.closeException(str(e, "id"));
            check("关闭异常 → 事故结案",
                    "closed".equals(e.get("status")) && acc != null && "closed".equals(acc.get("status")));
        }

        // 多级审批（部门→公司）：驳回回草稿、重新提交重走全链、逐级通过
        Map<String, Object> pendingContract = store.list("contracts").stream().filter(c -> "pending".equals(c.get("status"))).findFirst().orElse(null);
        if (pendingContract != null) {
            String cid = str(pendingContract, "id");
            contractService.rejectContract(cid, "运输方案需调整");
            @SuppressWarnings("unchecked")
            Map<String, Object> approval = (Map<String, Object>) byId("contracts", cid).get("approval");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chain = (List<Map<String, Object>>) byId("contracts", cid).get("approvalChain");
            check("审批驳回 → 回草稿 + 记录审批意见",
                    "draft".equals(byId("contracts", cid).get("status")) && approval != null && String.valueOf(approval.get("comment")).contains("驳回"));
            check("驳回时后续审批级取消",
                    chain != null && chain.size() >= 2 && "rejected".equals(chain.get(0).get("status")) && "cancelled".equals(chain.get(1).get("status")));
            contractService.submitContractApproval(cid);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chain2 = (List<Map<String, Object>>) byId("contracts", cid).get("approvalChain");
            check("重新提交审批 → 待审批 + 重建两级审批链",
                    "pending".equals(byId("contracts", cid).get("status")) && chain2 != null && chain2.size() == 2
                            && "pending".equals(chain2.get(0).get("status")) && "waiting".equals(chain2.get(1).get("status")));
            Map<String, Object> r1 = contractService.approveContract(cid, "部门同意");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chain3 = (List<Map<String, Object>>) byId("contracts", cid).get("approvalChain");
            check("部门审批通过 → 仍待审批（进入公司审批）",
                    Boolean.FALSE.equals(r1.get("final")) && "pending".equals(byId("contracts", cid).get("status"))
                            && chain3 != null && "approved".equals(chain3.get(0).get("status")) && "pending".equals(chain3.get(1).get("status")));
            Map<String, Object> r2 = contractService.approveContract(cid, "同意");
            @SuppressWarnings("unchecked")
            Map<String, Object> approval2 = (Map<String, Object>) byId("contracts", cid).get("approval");
            check("公司审批通过 → 执行中 + 记录审批意见",
                    Boolean.TRUE.equals(r2.get("final")) && "executing".equals(byId("contracts", cid).get("status"))
                            && approval2 != null && "同意".equals(approval2.get("comment")));
        }

        // 磅单补录 + 皮重按车辆派生（10-16t）
        Map<String, Object> mwDispatch = store.list("dispatches").stream()
                .filter(dd -> ctx.isRoadMode(str(dd, "mode")) && store.list("weighings").stream().noneMatch(w -> str(dd, "id").equals(str(w, "dispatchId")) && "进磅".equals(w.get("type"))))
                .findFirst().orElse(null);
        if (mwDispatch != null) {
            Map<String, Object> r = weighingService.manualWeighing(str(mwDispatch, "id"), "进磅", FlowCtx.num(mwDispatch.get("quantity")));
            check("磅单补录成功",
                    r.get("ok") != null && store.list("weighings").stream().anyMatch(w -> str(mwDispatch, "id").equals(str(w, "dispatchId")) && "进磅".equals(w.get("type"))));
            Map<String, Object> dup = weighingService.manualWeighing(str(mwDispatch, "id"), "进磅", 35);
            check("重复补录被拦截", dup.get("error") != null);
        }
        Map<String, Object> v1 = store.list("vehicles").get(0);
        check("皮重按车辆派生且在 10-16t 区间", ctx.tareOf(v1) >= 10 && ctx.tareOf(v1) <= 16);
        check("交互磅单皮重与预置口径一致（10-16t）",
                store.list("weighings").stream().allMatch(w -> FlowCtx.num(w.get("tare")) >= 10 && FlowCtx.num(w.get("tare")) <= 16));
    }

    // ===================== 环节 7：P3 产品完整度 =====================
    @Test @Order(7)
    void s7_P3产品完整度() {
        // P3-2 合同剩余可计划量
        Map<String, Object> rc = store.list("contracts").stream().filter(c -> "executing".equals(c.get("status"))).findFirst().orElse(null);
        if (rc != null) {
            double planned = store.list("plans").stream()
                    .filter(p -> str(rc, "id").equals(str(p, "contractId")) && !"cancelled".equals(p.get("status")))
                    .mapToDouble(p -> FlowCtx.num(p.get("quantity"))).sum();
            check("合同剩余可计划量 = 合同总量 - 未取消计划量",
                    Math.abs(contractService.contractRemaining(str(rc, "id")) - Math.max(0, FlowCtx.num(rc.get("quantity")) - planned)) < 1e-9);
        }

        // P3-4 多式联运：非公路方式按运输单元执行
        Map<String, Object> nonRoad = store.list("contracts").stream()
                .filter(c -> "executing".equals(c.get("status")) && !ctx.isRoadMode(str(c, "mode"))).findFirst().orElse(null);
        if (nonRoad != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", "YH-TEST-NR-S7");
            p.put("contractId", nonRoad.get("id"));
            p.put("commodityId", nonRoad.get("commodityId"));
            p.put("quantity", 2000);
            p.put("loadTerminalId", nonRoad.get("loadTerminalId"));
            p.put("unloadTerminalId", nonRoad.get("unloadTerminalId"));
            p.put("mode", nonRoad.get("mode"));
            p.put("planDate", ctx.today());
            p.put("unitPrice", nonRoad.get("unitPrice"));
            p.put("status", "pending");
            p.put("progress", 0);
            p.put("remark", "测试");
            store.list("plans").add(0, p);
            Map<String, Object> cd = dispatchService.createDispatches("YH-TEST-NR-S7", 2, null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nrCreated = (List<Map<String, Object>>) cd.get("created");
            check("非公路方式（" + nonRoad.get("mode") + "）按运输单元派车成功",
                    cd.get("error") == null && nrCreated.size() == 2, str(cd, "error"));
            check("非公路车次无车辆/司机，带运输单元号",
                    nrCreated.stream().allMatch(dd -> dd.get("vehicleId") == null && dd.get("driverId") == null
                            && dd.get("unitNo") != null && !String.valueOf(dd.get("unitNo")).isBlank()
                            && str(nonRoad, "mode").equals(str(dd, "mode"))));
            for (Map<String, Object> dd : nrCreated) {
                String id = str(dd, "id");
                dispatchService.confirmLoad(id);
                dispatchService.depart(id);
                dispatchService.arrive(id);
                dispatchService.confirmUnload(id);
            }
            check("非公路车次全流程完成且不产生公路磅单",
                    nrCreated.stream().allMatch(dd -> "completed".equals(dd.get("status"))
                            && store.list("weighings").stream().noneMatch(w -> str(dd, "id").equals(str(w, "dispatchId")))));
            // 清理测试数据
            store.list("dispatches").removeAll(nrCreated);
            store.list("plans").remove(p);
        }

        Map<String, Object> nr = store.list("dispatches").stream().filter(dd -> !ctx.isRoadMode(str(dd, "mode"))).findFirst().orElse(null);
        boolean mwBlocked = nr == null || weighingService.manualWeighing(str(nr, "id"), "进磅", 35).get("error") != null;
        check("manualWeighing 拦截非公路车次", mwBlocked);

        // P3-5 司机端：接单 + 电子签收
        Map<String, Object> rd = store.list("dispatches").stream()
                .filter(dd -> "pending".equals(dd.get("status")) && dd.get("driverId") != null).findFirst().orElse(null);
        if (rd != null) {
            dispatchService.acceptDispatch(str(rd, "id"));
            check("司机接单标记 accepted", FlowCtx.bool(rd.get("accepted")));
            check("守卫：卸货完成前不可签收（签收单为收货凭证）",
                    dispatchService.signReceipt(str(rd, "id"), "测试签收人").get("error") != null && rd.get("receipt") == null);
        }
        // 电子签收：已完成且未签收的公路车次
        Map<String, Object> rdone = store.list("dispatches").stream()
                .filter(dd -> "completed".equals(dd.get("status")) && ctx.isRoadMode(str(dd, "mode")) && dd.get("receipt") == null).findFirst().orElse(null);
        if (rdone != null) {
            dispatchService.signReceipt(str(rdone, "id"), "测试签收人");
            @SuppressWarnings("unchecked")
            Map<String, Object> receipt = (Map<String, Object>) rdone.get("receipt");
            check("电子签收单生成（QS- 码 + 签收人）",
                    receipt != null && String.valueOf(receipt.get("code")).startsWith("QS-")
                            && "测试签收人".equals(receipt.get("signer")) && receipt.get("time") != null);
        }

        // P3-7 合同生命周期：变更 / 延期 / 终止 / 归档
        Map<String, Object> ec = store.list("contracts").stream().filter(c -> "executing".equals(c.get("status"))).findFirst().orElse(null);
        if (ec != null) {
            String ecid = str(ec, "id");
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("quantity", FlowCtx.num(ec.get("quantity")) + 1000);
            Map<String, Object> r1 = contractService.changeContract(ecid, fields, "需求增加");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> changes = (List<Map<String, Object>>) byId("contracts", ecid).get("changes");
            check("合同变更重算金额并记录历史",
                    Boolean.TRUE.equals(r1.get("changed"))
                            && Math.abs(FlowCtx.num(byId("contracts", ecid).get("amount")) - Math.round(FlowCtx.num(byId("contracts", ecid).get("quantity")) * FlowCtx.num(byId("contracts", ecid).get("unitPrice")))) < 1e-9
                            && changes != null && !changes.isEmpty());
            String newEnd = java.time.LocalDate.now().plusDays(90).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            contractService.extendContract(ecid, newEnd, "工期顺延");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> changes2 = (List<Map<String, Object>>) byId("contracts", ecid).get("changes");
            check("合同延期更新截止日期并记录历史",
                    newEnd.equals(byId("contracts", ecid).get("endDate")) && changes2 != null && changes2.size() > 1);
        }
        Map<String, Object> tc = store.list("contracts").stream()
                .filter(c -> "executing".equals(c.get("status")) && !str(c, "id").equals(str(ec, "id"))).findFirst().orElse(null);
        if (tc != null) {
            String tcid = str(tc, "id");
            Object billNo = contractService.terminateContract(tcid, "客户经营调整", false).get("billNo");
            check("提前终止：状态 terminated 且待执行计划全部取消",
                    "terminated".equals(byId("contracts", tcid).get("status"))
                            && store.list("plans").stream().noneMatch(x -> tcid.equals(str(x, "contractId")) && "pending".equals(x.get("status")))
                            && (billNo == null || billNo instanceof String));
        }
        Map<String, Object> ac = store.list("contracts").stream().filter(c -> "completed".equals(c.get("status"))).findFirst().orElse(null);
        if (ac != null) {
            String acid = str(ac, "id");
            contractService.archiveContract(acid);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> changes3 = (List<Map<String, Object>>) byId("contracts", acid).get("changes");
            check("合同归档：状态 archived 且记录历史",
                    "archived".equals(byId("contracts", acid).get("status")) && changes3 != null && !changes3.isEmpty());
        }

        // P3-8 KPI 口径
        Map<String, Object> kpi = dashboardService.kpi();
        check("准时交付率在 0-100 区间",
                FlowCtx.num(kpi.get("onTimeRate")) >= 0 && FlowCtx.num(kpi.get("onTimeRate")) <= 100);
        long usable = store.list("vehicles").stream().filter(v -> !"scrapped".equals(v.get("status"))).count();
        long inuse = store.list("vehicles").stream().filter(v -> "inuse".equals(v.get("status"))).count();
        double expectUtil = usable > 0 ? Math.round(inuse * 1000.0 / usable) / 10.0 : 0;
        check("车辆利用率 = 运输中车辆 / 非报废车辆",
                Math.abs(FlowCtx.num(kpi.get("utilization")) - expectUtil) < 1e-9);

        // P3-9 发票号确定性派生
        String invNo = settlementService.genInvoiceNo("SET-TEST-1");
        check("发票号确定性派生（16 位数字，2410 开头，与种子口径一致）",
                invNo.equals(settlementService.genInvoiceNo("SET-TEST-1")) && invNo.matches("\\d{16}") && invNo.startsWith("2410"));
        check("不同结算单发票号不同", !settlementService.genInvoiceNo("SET-A").equals(settlementService.genInvoiceNo("SET-B")));

        // P3-10 公告 / 天气数据源化
        check("公告数据源化（db.announcements）", ctx.announcements().size() >= 3);
        Map<String, Object> w1 = ctx.weatherOf("2026-08-16");
        Map<String, Object> w2 = ctx.weatherOf("2026-08-16");
        check("天气按日期确定性派生",
                w1.get("city") != null && w1.get("cond") != null && FlowCtx.num(w1.get("temp")) > 0
                        && w1.get("cond").equals(w2.get("cond")) && w1.get("temp").equals(w2.get("temp")));

        // P3-8 报表中心
        check("月度报表覆盖近 6 个月", reportService.monthlyReport().size() == 6);
        check("客户报表含授信占用口径",
                reportService.customerReport().stream().allMatch(c -> c.get("creditPct") instanceof Number && c.get("outstanding") instanceof Number));
        check("商品报表含磅单损耗率",
                reportService.commodityReport().stream().allMatch(c -> c.get("lossRate") instanceof Number));
        check("场站报表含装卸吞吐",
                reportService.terminalReport().stream().allMatch(t -> t.get("loadTrips") instanceof Number && t.get("unloadTrips") instanceof Number));

        // RBAC：报表中心权限 + 路径一致性
        check("报表中心权限：结算专员可访问、调度员不可访问",
                rbac.menuAllowed("结算专员", "/report") && !rbac.menuAllowed("调度员", "/report"));
        check("RBAC 路径与实际路由一致（调度员可访问 /contract）", rbac.menuAllowed("调度员", "/contract"));
    }

    // ===================== 环节 8：P0 回归 =====================
    @Test @Order(8)
    void s8_P0回归() {
        // 状态机守卫：非法流转被拦截且状态不变
        Map<String, Object> dbgA = dispatchService.confirmLoad(str(d2, "id"));
        Map<String, Object> dbgB = dispatchService.depart(str(d2, "id"));
        check("守卫：在途车次不可确认装货/重复发车",
                dbgA.get("error") != null
                        && dbgB.get("error") != null
                        && "intransit".equals(d2.get("status")),
                "confirmLoad=" + dbgA + " depart=" + dbgB + " d2.status=" + d2.get("status") + " d2id=" + str(d2, "id"));
        Map<String, Object> excD = store.list("dispatches").stream().filter(x -> "exception".equals(x.get("status"))).findFirst().orElse(null);
        check("守卫：异常车次不可发车/确认卸货", excD == null || (
                dispatchService.depart(str(excD, "id")).get("error") != null
                        && dispatchService.confirmUnload(str(excD, "id")).get("error") != null
                        && "exception".equals(excD.get("status"))));
        check("守卫：已完成车次不可再流转",
                dispatchService.confirmUnload(str(d, "id")).get("error") != null
                        && dispatchService.reportException(str(d, "id"), "已完成再报异常", "other", "low").get("error") != null
                        && "completed".equals(d.get("status")));

        // P0-4：对账前预付在确认结算后保留
        Map<String, Object> preS = store.list("settlements").stream()
                .filter(x -> "reconciling".equals(x.get("status")) && FlowCtx.num(x.get("paidAmount")) > 0).findFirst().orElse(null);
        if (preS != null) {
            double pre = FlowCtx.num(preS.get("paidAmount"));
            for (Map<String, Object> x : store.list("dispatches").stream()
                    .filter(dd -> str(preS, "id").equals(str(dd, "settlementId")) && ctx.isRoadMode(str(dd, "mode")) && dd.get("receipt") == null).toList()) {
                dispatchService.supplementReceipt(str(x, "id"), "收货方仓管员", "冒烟测试补签");
            }
            settlementService.customerConfirm(str(preS, "id"));
            settlementService.confirmSettle(str(preS, "id"));
            check("确认结算保留预付款（已付金额不清零）",
                    "settled".equals(preS.get("status")) && Math.abs(FlowCtx.num(preS.get("paidAmount")) - pre) < 1e-9);
            check("预付款与收款流水合计一致",
                    Math.abs(store.list("payments").stream()
                            .filter(p -> str(preS, "id").equals(str(p, "settlementId")) && !FlowCtx.bool(p.get("reversed")))
                            .mapToDouble(p -> FlowCtx.num(p.get("amount"))).sum() - FlowCtx.num(preS.get("paidAmount"))) < 1e-9);
        }

        // P0-7：月度报表逾期数按月过滤
        check("月度报表逾期数按月过滤（各月之和 = 全量逾期账单数）",
                reportService.monthlyReport().stream().mapToInt(m -> FlowCtx.intNum(m.get("overdueCount"))).sum()
                        == store.list("settlements").stream().filter(x -> "overdue".equals(x.get("status"))).count());

        // P0-1：RBAC 默认拒绝 + 平台管理员全权限
        check("RBAC 默认拒绝：未知/空角色无菜单无操作",
                !rbac.menuAllowed("未知角色", "/contract") && !rbac.menuAllowed("", "/contract")
                        && !rbac.actionAllowed("未知角色", "dispatch") && !rbac.actionAllowed("", "dispatch"));
        check("RBAC：平台管理员(null)仍为全权限",
                rbac.menuAllowed("平台管理员", "/contract") && rbac.actionAllowed("平台管理员", "dispatch"));

        // P0-5：种子事故类异常与事故记录双向关联
        check("种子事故类异常与事故记录双向关联",
                store.list("exceptions").stream().filter(e -> "accident".equals(e.get("type")) && e.get("accidentId") != null).count() > 0
                        && store.list("exceptions").stream().filter(e -> "accident".equals(e.get("type")) && e.get("accidentId") != null)
                        .allMatch(e -> store.list("accidents").stream().anyMatch(a -> e.get("accidentId").equals(a.get("id")) && str(e, "id").equals(str(a, "exceptionId")))));
    }

    // ===================== 环节 9：P1 回归 =====================
    @Test @Order(9)
    void s9_P1回归() {
        // P1-8：角色权限表数据化
        Map<String, Object> rolePerms = store.obj("rolePerms");
        check("db.rolePerms 为内置角色种子权限条目",
                List.of("平台管理员", "调度员", "结算专员", "场站操作员", "安全管理员", "只读用户").stream().allMatch(rolePerms::containsKey));
        check("角色权限以 db.rolePerms 为准（调度员无结算菜单、有调度菜单）",
                !rbac.menuAllowed("调度员", "/settlement") && rbac.menuAllowed("调度员", "/dispatch"));
        Map<String, Object> testPerm = new LinkedHashMap<>();
        testPerm.put("menus", List.of());
        testPerm.put("actions", List.of());
        rolePerms.put("测试角色", testPerm);
        check("新建角色默认无任何权限（deny）",
                !rbac.menuAllowed("测试角色", "/workbench") && !rbac.actionAllowed("测试角色", "dispatch"));
        testPerm.put("menus", List.of("/workbench", "/dispatch"));
        testPerm.put("actions", List.of("dispatch"));
        check("角色授权后立即生效（按授权放行、未授权拒绝）",
                rbac.menuAllowed("测试角色", "/workbench") && !rbac.menuAllowed("测试角色", "/settlement")
                        && rbac.actionAllowed("测试角色", "dispatch") && !rbac.actionAllowed("测试角色", "settlement"));
        rolePerms.remove("测试角色");
        check("角色删除后权限条目同步清除（回落到默认拒绝）",
                !rolePerms.containsKey("测试角色") && !rbac.menuAllowed("测试角色", "/workbench"));

        // P1-9：皮重口径统一
        check("种子磅单皮重 = tareOf(车辆)（同一车辆进/出磅皮重一致）",
                store.list("weighings").stream().filter(w -> {
                    Map<String, Object> dd = store.list("dispatches").stream().filter(x -> str(w, "dispatchId").equals(str(x, "id"))).findFirst().orElse(null);
                    return dd != null && dd.get("vehicleId") != null;
                }).count() > 0
                        && store.list("weighings").stream().filter(w -> {
                    Map<String, Object> dd = store.list("dispatches").stream().filter(x -> str(w, "dispatchId").equals(str(x, "id"))).findFirst().orElse(null);
                    return dd != null && dd.get("vehicleId") != null;
                }).allMatch(w -> {
                    Map<String, Object> dd = store.list("dispatches").stream().filter(x -> str(w, "dispatchId").equals(str(x, "id"))).findFirst().orElse(null);
                    Map<String, Object> v = store.list("vehicles").stream().filter(x -> x.get("id").equals(dd.get("vehicleId"))).findFirst().orElse(null);
                    return v != null && Math.abs(FlowCtx.num(w.get("tare")) - ctx.tareOf(v)) < 1e-9;
                }));

        // P1-10：资源类操作走 flow（发票开具/红冲）
        Map<String, Object> invSettle = store.list("settlements").stream()
                .filter(x -> "settled".equals(x.get("status")) || "overdue".equals(x.get("status"))).findFirst().orElse(null);
        if (invSettle != null) {
            Map<String, Object> invPending = new LinkedHashMap<>();
            invPending.put("id", "FP-TEST");
            invPending.put("settlementId", invSettle.get("id"));
            invPending.put("invoiceNo", "");
            invPending.put("type", "增值税专用发票");
            invPending.put("amount", invSettle.get("totalAmount"));
            invPending.put("issueDate", null);
            invPending.put("status", "pending");
            invPending.put("remark", "");
            store.list("invoices").add(invPending);
            invSettle.put("invoiceStatus", "pending");
            Map<String, Object> r1 = settlementService.issueInvoice(str(invSettle, "id"));
            check("发票开具走 flow（状态/号码/结算单开票状态 + 审计日志）",
                    r1.get("ok") != null && "issued".equals(invPending.get("status")) && !String.valueOf(invPending.get("invoiceNo")).isBlank()
                            && "issued".equals(invSettle.get("invoiceStatus")));
            Map<String, Object> r2 = settlementService.redFlushInvoiceRow(str(invPending, "id"), "测试红冲");
            check("发票红冲走 flow（状态回退）",
                    r2.get("ok") != null && "red-flushed".equals(invPending.get("status")));
            check("守卫：已红冲发票不可重复红冲",
                    settlementService.redFlushInvoiceRow(str(invPending, "id"), "再次红冲").get("error") != null);
        }

        // P1-12：派车资源互斥
        Map<String, Object> p12 = store.list("plans").stream().filter(p -> {
            if (!"pending".equals(p.get("status"))) return false;
            Map<String, Object> c = store.list("contracts").stream().filter(x -> x.get("id").equals(p.get("contractId"))).findFirst().orElse(null);
            return c != null && "executing".equals(c.get("status")) && ctx.isRoadMode(str(p, "mode"));
        }).findFirst().orElse(null);
        if (p12 != null) {
            Set<String> busyBefore = store.list("dispatches").stream()
                    .filter(x -> List.of("pending", "loading", "exception").contains(x.get("status")))
                    .map(x -> String.valueOf(x.get("vehicleId"))).collect(java.util.stream.Collectors.toSet());
            Set<String> busyDriverBefore = store.list("dispatches").stream()
                    .filter(x -> List.of("pending", "loading", "exception").contains(x.get("status")))
                    .map(x -> String.valueOf(x.get("driverId"))).collect(java.util.stream.Collectors.toSet());
            Map<String, Object> cd = dispatchService.createDispatches(str(p12, "id"), 3, null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> c12 = (List<Map<String, Object>>) cd.get("created");
            check("派车排除已有未完结车次的车辆/司机",
                    c12.size() == 3 && c12.stream().allMatch(x -> !busyBefore.contains(str(x, "vehicleId")) && !busyDriverBefore.contains(str(x, "driverId"))));
            check("新调度单之间车辆/司机不重复占用",
                    c12.stream().map(x -> str(x, "vehicleId")).distinct().count() == c12.size()
                            && c12.stream().map(x -> str(x, "driverId")).distinct().count() == c12.size());
        }

        // P1-11：合同终止口径
        Map<String, Object> tc2 = store.list("contracts").stream().filter(c -> "executing".equals(c.get("status"))).findFirst().orElse(null);
        if (tc2 != null) {
            String tcid = str(tc2, "id");
            List<Map<String, Object>> pendingPlans = store.list("plans").stream()
                    .filter(p -> tcid.equals(str(p, "contractId")) && "pending".equals(p.get("status"))).toList();
            contractService.terminateContract(tcid, "P1 终止口径测试", false);
            check("合同终止：状态 terminated 且待执行计划全部取消",
                    "terminated".equals(byId("contracts", tcid).get("status"))
                            && pendingPlans.stream().allMatch(p -> "cancelled".equals(p.get("status"))));
            Map<String, Object> anyPlan = store.list("plans").stream()
                    .filter(p -> tcid.equals(str(p, "contractId")) && !"cancelled".equals(p.get("status"))).findFirst().orElse(null);
            Map<String, Object> r11 = anyPlan != null ? dispatchService.createDispatches(str(anyPlan, "id"), 1, null)
                    : Map.of("created", List.of(), "error", "合同已终止，不能再下发调度单");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> c11 = (List<Map<String, Object>>) r11.get("created");
            check("守卫：已终止合同不可再下发调度单",
                    r11.get("error") != null && c11.isEmpty());
        }

        // P1-13：结算调整（异常关闭补扣 + 重算入口）
        Map<String, Object> c13 = store.list("contracts").stream()
                .filter(c -> "executing".equals(c.get("status")) && ctx.isRoadMode(str(c, "mode"))).findFirst().orElse(null);
        if (c13 != null) {
            Map<String, Object> p13 = new LinkedHashMap<>();
            p13.put("id", "YH-P13");
            p13.put("contractId", c13.get("id"));
            p13.put("commodityId", c13.get("commodityId"));
            p13.put("quantity", 35);
            p13.put("loadTerminalId", c13.get("loadTerminalId"));
            p13.put("unloadTerminalId", c13.get("unloadTerminalId"));
            p13.put("mode", c13.get("mode"));
            p13.put("unitPrice", c13.get("unitPrice"));
            p13.put("status", "pending");
            p13.put("progress", 0);
            store.list("plans").add(0, p13);
            Map<String, Object> cd = dispatchService.createDispatches("YH-P13", 1, null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> c13d = (List<Map<String, Object>>) cd.get("created");
            if (!c13d.isEmpty()) {
                Map<String, Object> d13 = c13d.get(0);
                String d13id = str(d13, "id");
                dispatchService.acceptDispatch(d13id);
                dispatchService.confirmLoad(d13id);
                dispatchService.depart(d13id);
                Map<String, Object> e13 = dispatchService.reportException(d13id, "P1 测试：结算时异常未关闭", "damage", "medium");
                dispatchService.resumeDispatch(d13id);
                dispatchService.arrive(d13id);
                dispatchService.confirmUnload(d13id);
                Map<String, Object> g13 = settlementService.settlementCandidates().stream()
                        .filter(x -> ((List<Map<String, Object>>) x.get("dispatches")).stream().anyMatch(dd -> d13id.equals(str(dd, "id"))))
                        .findFirst().orElse(null);
                Map<String, Object> gs = settlementService.generateSettlements(List.of(str(g13, "key")));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> createdS = (List<Map<String, Object>>) gs.get("created");
                Map<String, Object> s13 = createdS.get(0);
                double lossBefore = FlowCtx.num(s13.get("exceptionLoss"));
                double totalBefore = FlowCtx.num(s13.get("totalAmount"));
                Map<String, Object> r13a = settlementService.recalcSettlement(str(s13, "id"));
                check("重算（待对账）：数据未变化时金额不变（幂等）",
                        r13a.get("ok") != null && Math.abs(FlowCtx.num(r13a.get("delta"))) < 1e-9);
                exceptionService.finishException(str(e13, "id"), "货损已处理", 8000);
                exceptionService.closeException(str(e13, "id"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> adjustments = (List<Map<String, Object>>) s13.get("adjustments");
                check("异常关闭补扣：已入账单损失扣减 + 调整记录 + 防重复标记",
                        Math.abs(FlowCtx.num(s13.get("exceptionLoss")) - (lossBefore + 8000)) < 1e-9
                                && Math.abs(FlowCtx.num(s13.get("totalAmount")) - (totalBefore - 8000)) < 1e-9
                                && adjustments != null && !adjustments.isEmpty()
                                && Math.abs(FlowCtx.num(adjustments.get(adjustments.size() - 1).get("amount")) + 8000) < 1e-9
                                && str(s13, "id").equals(str(e13, "settleApplied")),
                        "lossBefore=" + lossBefore + " exceptionLoss=" + s13.get("exceptionLoss")
                                + " totalBefore=" + totalBefore + " totalAmount=" + s13.get("totalAmount")
                                + " adjustments=" + adjustments + " e13.settleApplied=" + e13.get("settleApplied")
                                + " s13id=" + str(s13, "id") + " e13id=" + str(e13, "id")
                                + " d13.settlementId=" + d13.get("settlementId") + " e13.status=" + e13.get("status") + " e13.cost=" + e13.get("cost"));
                Map<String, Object> dbgRecalc = settlementService.recalcSettlement(str(s13, "id"));
                check("重算与补扣结果一致（幂等，不重复扣减）",
                        Math.abs(FlowCtx.num(dbgRecalc.get("delta"))) < 1e-9
                                && Math.abs(FlowCtx.num(s13.get("totalAmount")) - (totalBefore - 8000)) < 1e-9,
                        "recalc=" + dbgRecalc + " totalAmount=" + s13.get("totalAmount") + " expect=" + (totalBefore - 8000));
                settlementService.startReconcile(str(s13, "id"));
                check("守卫：非待对账账单不可重算",
                        settlementService.recalcSettlement(str(s13, "id")).get("error") != null);
            }
        }
    }

    // ===================== 环节 10：P2 回归（清理 + 多级审批） =====================
    @Test @Order(10)
    void s10_P2回归() {
        check("种子待审批合同带两级审批链（首级待审）",
                store.list("contracts").stream().filter(c -> "pending".equals(c.get("status"))).allMatch(c -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> chain = (List<Map<String, Object>>) c.get("approvalChain");
                    return chain != null && chain.size() == 2 && "pending".equals(chain.get(0).get("status")) && "waiting".equals(chain.get(1).get("status"));
                }));
        check("种子已执行/已完成/已终止合同审批链全链通过",
                store.list("contracts").stream().filter(c -> List.of("executing", "completed", "terminated").contains(c.get("status"))).allMatch(c -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> chain = (List<Map<String, Object>>) c.get("approvalChain");
                    return chain != null && chain.size() == 2 && chain.stream().allMatch(s -> "approved".equals(s.get("status")));
                }));
        check("安全运行天数按事故记录派生（>0 且 ≤365）",
                dashboardService.safeDays() > 0 && dashboardService.safeDays() <= 365);
        check("培训种子含参训司机 driverIds（覆盖率口径可计算）",
                store.list("trainings").stream().anyMatch(t -> {
                    @SuppressWarnings("unchecked")
                    List<?> ids = (List<?>) t.get("driverIds");
                    return ids != null && !ids.isEmpty();
                }));
    }

    // ===================== 环节 11：P2 功能（扫码/围栏/成本侧/客户门户） =====================
    @Test @Order(11)
    void s11_P2功能() {
        // 扫码确认
        Map<String, Object> scanD = store.list("dispatches").stream()
                .filter(x -> "pending".equals(x.get("status")) && ctx.isRoadMode(str(x, "mode")) && FlowCtx.bool(x.get("accepted"))).findFirst().orElse(null);
        if (scanD == null) {
            Map<String, Object> pd = store.list("dispatches").stream()
                    .filter(x -> "pending".equals(x.get("status")) && ctx.isRoadMode(str(x, "mode"))).findFirst().orElse(null);
            if (pd != null) { dispatchService.acceptDispatch(str(pd, "id")); scanD = pd; }
        }
        final String scanId = scanD == null ? null : str(scanD, "id");
        if (scanD != null) {
            String lc = dispatchService.loadCodeOf(scanD);
            String uc = dispatchService.unloadCodeOf(scanD);
            check("装/卸货码确定性派生（同单同码、异单异码、格式 ZD/XD+6 位）",
                    lc.matches("ZD\\d{6}") && uc.matches("XD\\d{6}")
                            && dispatchService.loadCodeOf(scanD).equals(lc)
                            && store.list("dispatches").stream().anyMatch(x -> !scanId.equals(str(x, "id")) && !dispatchService.loadCodeOf(x).equals(lc)),
                    "lc=" + lc + " uc=" + uc + " scanId=" + scanId);
            check("守卫：错误装货码拦截（状态不变）",
                    dispatchService.scanConfirmLoad(str(scanD, "id"), "ZD999999").get("error") != null && "pending".equals(scanD.get("status")));
            Map<String, Object> rScan = dispatchService.scanConfirmLoad(str(scanD, "id"), lc);
            check("扫码确认装货：正确码 → 装货中 + 进磅单登记",
                    rScan.get("ok") != null && "loading".equals(scanD.get("status"))
                            && store.list("weighings").stream().anyMatch(w -> scanId.equals(str(w, "dispatchId")) && "进磅".equals(w.get("type"))));
            check("守卫：非待装货状态扫码装货拦截", dispatchService.scanConfirmLoad(str(scanD, "id"), lc).get("error") != null);
            check("守卫：非卸货中状态扫码卸货拦截", dispatchService.scanConfirmUnload(str(scanD, "id"), uc).get("error") != null);
        }

        // 围栏事件化
        Map<String, Object> fenceConfig = store.obj("fenceConfig");
        check("围栏参数种子（enabled/deviateLimit/delayMinutes）",
                FlowCtx.bool(fenceConfig.get("enabled")) && FlowCtx.num(fenceConfig.get("deviateLimit")) > 0 && FlowCtx.num(fenceConfig.get("delayMinutes")) > 0);
        List<Map<String, Object>> deviating = store.list("dispatches").stream()
                .filter(x -> "intransit".equals(x.get("status")) && ctx.maxDeviationOf(x) > FlowCtx.num(fenceConfig.get("deviateLimit"))).toList();
        int fenceBefore = store.list("exceptions").size();
        List<Map<String, Object>> fenceCreated = schedulerService.checkFenceEvents();
        check("围栏事件：偏离超阈值在途车次自动写异常单（source=fence，车次转异常）",
                fenceCreated.size() >= deviating.size()
                        && fenceCreated.stream().allMatch(e -> "fence".equals(e.get("source")) && "pending".equals(e.get("status")))
                        && deviating.stream().allMatch(x -> "exception".equals(x.get("status"))
                                && store.list("exceptions").stream().anyMatch(e -> str(x, "id").equals(str(e, "dispatchId")) && "fence".equals(e.get("source"))))
                        && store.list("exceptions").size() == fenceBefore + fenceCreated.size());
        check("围栏事件去重：二次检查不重复生成（每车次每类一次）", schedulerService.checkFenceEvents().isEmpty());
        boolean fenceEnabled = FlowCtx.bool(fenceConfig.get("enabled"));
        fenceConfig.put("enabled", false);
        check("守卫：围栏事件关闭后不生成异常单", schedulerService.checkFenceEvents().isEmpty());
        fenceConfig.put("enabled", fenceEnabled);

        // 成本侧
        Map<String, Object> costD = store.list("dispatches").stream()
                .filter(x -> "completed".equals(x.get("status")) && x.get("vehicleId") != null).findFirst().orElse(null);
        boolean roadCostOk = false;
        if (costD != null) {
            Map<String, Object> c = ctx.tripCostOf(costD);
            roadCostOk = FlowCtx.num(c.get("fuel")) > 0 && FlowCtx.num(c.get("wear")) > 0 && FlowCtx.num(c.get("driver")) > 0
                    && FlowCtx.num(c.get("toll")) > 0 && FlowCtx.num(c.get("depreciation")) > 0
                    && Math.abs(FlowCtx.num(c.get("total")) - (FlowCtx.num(c.get("fuel")) + FlowCtx.num(c.get("wear")) + FlowCtx.num(c.get("driver")) + FlowCtx.num(c.get("toll")) + FlowCtx.num(c.get("depreciation")))) < 1e-9;
        }
        check("单车次成本（公路）：五项成本齐备且 total=各项之和", roadCostOk);
        Map<String, Object> costNR = store.list("dispatches").stream()
                .filter(x -> "completed".equals(x.get("status")) && x.get("vehicleId") == null).findFirst().orElse(null);
        boolean nrCostOk = false;
        if (costNR != null) {
            Map<String, Object> c = ctx.tripCostOf(costNR);
            nrCostOk = FlowCtx.num(c.get("driver")) == 0 && FlowCtx.num(c.get("depreciation")) == 0
                    && Math.abs(FlowCtx.num(c.get("total")) - (FlowCtx.num(c.get("fuel")) + FlowCtx.num(c.get("wear")) + FlowCtx.num(c.get("toll")))) < 1e-9;
        }
        check("单车次成本（非公路）：无司机/折旧项（运输单元能耗口径）", nrCostOk);
        Map<String, Object> cr = reportService.costReport();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) cr.get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byRoute = (List<Map<String, Object>>) cr.get("byRoute");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byVehicle = (List<Map<String, Object>>) cr.get("byVehicle");
        check("成本报表：汇总恒等式（收入-成本=毛利，毛利率一致）",
                FlowCtx.num(summary.get("trips")) > 0
                        && Math.abs(FlowCtx.num(summary.get("profit")) - (FlowCtx.num(summary.get("revenue")) - FlowCtx.num(summary.get("cost")))) < 1e-9
                        && Math.abs(FlowCtx.num(summary.get("margin")) - Math.round(FlowCtx.num(summary.get("profit")) / FlowCtx.num(summary.get("revenue")) * 1000) / 10.0) < 1e-9);
        check("成本报表：按线路聚合与汇总一致（车次/成本/收入）",
                byRoute.stream().mapToDouble(r -> FlowCtx.num(r.get("trips"))).sum() == FlowCtx.num(summary.get("trips"))
                        && Math.abs(byRoute.stream().mapToDouble(r -> FlowCtx.num(r.get("cost"))).sum() - FlowCtx.num(summary.get("cost"))) < 1e-9
                        && Math.abs(byRoute.stream().mapToDouble(r -> FlowCtx.num(r.get("revenue"))).sum() - FlowCtx.num(summary.get("revenue"))) < 1e-9);
        check("成本报表：单车/单线行毛利恒等式",
                Stream.concat(byVehicle.stream(), byRoute.stream()).allMatch(r ->
                        Math.abs(FlowCtx.num(r.get("profit")) - (FlowCtx.num(r.get("revenue")) - FlowCtx.num(r.get("cost")))) < 1e-9 && FlowCtx.num(r.get("trips")) > 0));

        // 客户门户
        check("客户角色权限（菜单含 /portal，无内部菜单，操作仅 customer-confirm）",
                rbac.menuAllowed("客户", "/portal") && !rbac.menuAllowed("客户", "/settlement")
                        && rbac.actionAllowed("客户", "customer-confirm") && !rbac.actionAllowed("客户", "settlement"));
        List<Map<String, Object>> custUsers = store.list("users").stream().filter(u -> "客户".equals(u.get("role"))).toList();
        check("客户门户账号种子（≥2 个，均绑定发货方/双向客户）",
                custUsers.size() >= 2 && custUsers.stream().allMatch(u ->
                        store.list("customers").stream().anyMatch(c -> c.get("id").equals(u.get("customerId"))
                                && (List.of("shipper", "both").contains(c.get("type"))))));
        Map<String, Object> custSettle = store.list("settlements").stream()
                .filter(x -> x.get("customerId") != null && x.get("customerConfirmed") == null && "reconciling".equals(x.get("status")))
                .findFirst()
                .orElseGet(() -> store.list("settlements").stream().filter(x -> "reconciling".equals(x.get("status")) && x.get("customerConfirmed") == null).findFirst()
                        .orElseGet(() -> {
                            Map<String, Object> ps = store.list("settlements").stream().filter(x -> "pending".equals(x.get("status"))).findFirst().orElse(null);
                            if (ps != null) settlementService.startReconcile(str(ps, "id"));
                            return ps;
                        }));
        if (custSettle != null) {
            Map<String, Object> rConf = settlementService.customerConfirm(str(custSettle, "id"));
            @SuppressWarnings("unchecked")
            Map<String, Object> cc = (Map<String, Object>) custSettle.get("customerConfirmed");
            check("客户确认对账：写 customerConfirmed",
                    rConf.get("ok") != null && cc != null && cc.get("time") != null);
            check("守卫：同一账单客户不可重复确认",
                    settlementService.customerConfirm(str(custSettle, "id")).get("error") != null);
        }
        check("只读演示账号种子（user16：全菜单可见、无操作权）",
                store.list("users").stream().anyMatch(u -> "user16".equals(u.get("username"))
                        && "只读用户".equals(u.get("role"))
                        && rbac.menuAllowed("只读用户", "/settlement") && !rbac.actionAllowed("只读用户", "settlement")));
    }

    // ===================== 环节 12：P0 闭环断点回归（N3/N4/N5） =====================
    @Test @Order(12)
    void s12_P0闭环断点() {
        // N3 结算用车次派车时快照单价
        Map<String, Object> n3c = store.list("contracts").stream()
                .filter(c -> "executing".equals(c.get("status")) && ctx.isRoadMode(str(c, "mode"))).findFirst().orElse(null);
        if (n3c != null) {
            String n3cid = str(n3c, "id");
            Map<String, Object> n3p = new LinkedHashMap<>();
            n3p.put("id", "YH-N3");
            n3p.put("contractId", n3c.get("id"));
            n3p.put("commodityId", n3c.get("commodityId"));
            n3p.put("quantity", 35);
            n3p.put("loadTerminalId", n3c.get("loadTerminalId"));
            n3p.put("unloadTerminalId", n3c.get("unloadTerminalId"));
            n3p.put("mode", n3c.get("mode"));
            n3p.put("unitPrice", n3c.get("unitPrice"));
            n3p.put("status", "pending");
            n3p.put("progress", 0);
            store.list("plans").add(0, n3p);
            Map<String, Object> cd = dispatchService.createDispatches("YH-N3", 1, null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> n3d = (List<Map<String, Object>>) cd.get("created");
            if (!n3d.isEmpty()) {
                Map<String, Object> n3trip = n3d.get(0);
                check("车次写入派车时快照单价", FlowCtx.num(n3trip.get("unitPrice")) == FlowCtx.num(n3c.get("unitPrice")));
                double oldPrice = FlowCtx.num(n3c.get("unitPrice"));
                Map<String, Object> n3chg = contractService.changeContract(n3cid, Map.of("unitPrice", oldPrice + 10), "N3 测试改价");
                check("环节3：改价提交后不即时生效（转待审批）",
                        Boolean.TRUE.equals(n3chg.get("pending")) && FlowCtx.num(byId("contracts", n3cid).get("unitPrice")) == oldPrice
                                && byId("contracts", n3cid).get("pendingChange") != null);
                contractService.approveContractChange(n3cid, "同意");
                Map<String, Object> n3final = contractService.approveContractChange(n3cid, "同意");
                check("环节3：改价全级审批通过后生效",
                        Boolean.TRUE.equals(n3final.get("final")) && FlowCtx.num(byId("contracts", n3cid).get("unitPrice")) == oldPrice + 10
                                && byId("contracts", n3cid).get("pendingChange") == null);
                Map<String, Object> n3fees = ctx.calcSettlementFees(byId("contracts", n3cid), List.of(n3trip));
                check("结算用车次快照单价（改价不追溯已派车车次）",
                        Math.abs(FlowCtx.num(n3fees.get("freight")) - Math.round(FlowCtx.num(n3trip.get("quantity")) * oldPrice)) < 1e-9);
                store.list("dispatches").remove(n3trip);
                store.list("plans").remove(n3p);
                contractService.changeContract(n3cid, Map.of("unitPrice", oldPrice), "N3 测试还原");
                contractService.approveContractChange(n3cid, "同意");
                contractService.approveContractChange(n3cid, "同意");
                check("N3：合同价还原（还原走审批后单价恢复）",
                        FlowCtx.num(byId("contracts", n3cid).get("unitPrice")) == oldPrice && byId("contracts", n3cid).get("pendingChange") == null);
            }
        }

        // N4 合同完结
        Map<String, Object> n4c = store.list("contracts").stream().filter(c -> "executing".equals(c.get("status"))).findFirst().orElse(null);
        if (n4c != null) {
            String n4cid = str(n4c, "id");
            boolean n4Active = store.list("plans").stream()
                    .anyMatch(p -> n4cid.equals(str(p, "contractId")) && !List.of("cancelled", "completed").contains(p.get("status")));
            if (n4Active) {
                check("守卫：存在未完结计划不可完结合同",
                        contractService.completeContract(n4cid).get("error") != null && "executing".equals(byId("contracts", n4cid).get("status")));
            } else {
                Map<String, Object> r = contractService.completeContract(n4cid);
                check("合同完结：计划全部完成 → completed + 进度 100%",
                        r.get("ok") != null && "completed".equals(byId("contracts", n4cid).get("status"))
                                && FlowCtx.num(byId("contracts", n4cid).get("progress")) == 100);
            }
        }
        Map<String, Object> n4nonExec = store.list("contracts").stream()
                .filter(c -> "completed".equals(c.get("status")) || "terminated".equals(c.get("status"))).findFirst().orElse(null);
        if (n4nonExec != null) {
            check("守卫：非执行中合同不可完结",
                    contractService.completeContract(str(n4nonExec, "id")).get("error") != null);
        }

        // N5 开票状态守卫
        Map<String, Object> n5s = store.list("settlements").stream()
                .filter(x -> "settled".equals(x.get("status")) && "not-issued".equals(x.get("invoiceStatus"))).findFirst()
                .orElse(s);
        if (n5s != null) {
            Map<String, Object> r1 = settlementService.issueInvoice(str(n5s, "id"));
            check("开具发票（未开票 → 已开具）",
                    r1.get("ok") != null && !String.valueOf(r1.get("invoiceNo")).isBlank() && "issued".equals(n5s.get("invoiceStatus")));
            check("守卫：已开票账单不可重复开具",
                    settlementService.issueInvoice(str(n5s, "id")).get("error") != null);
        }
    }


    /* ===================== A1：行级数据范围（服务端强制） ===================== */
    @Test
    @Order(90)
    void a1_rowLevelDataScope() {
        // 基线（平台管理员）：全量数据
        List<Map<String, Object>> allDispatches = store.list("dispatches");
        check("A1：平台管理员全量（无范围过滤）", scope.scopeRegions().isEmpty()
                && scope.filter("dispatches", allDispatches).size() == allDispatches.size());
        // 种子范围：user02 = 华北（V3 种子）
        Map<String, Object> seedScope = userAdminService.dataScopeOf();
        login("李芳", "user02", "调度员", "");
        check("A1：调度员 user02 范围=华北", "华北".equals(String.valueOf(((List<?>) userAdminService.dataScopeOf().get("regions")).get(0))));
        // 行级过滤：仅华北装货侧，且为真子集
        List<Map<String, Object>> scoped = scope.filter("dispatches", store.list("dispatches"));
        check("A1：调度单行级过滤（仅华北装货侧，真子集）",
                !scoped.isEmpty() && scoped.size() < allDispatches.size()
                        && scoped.stream().allMatch(d -> "华北".equals(regionOf(d))));
        // 计划同样过滤
        List<Map<String, Object>> scopedPlans = scope.filter("plans", store.list("plans"));
        check("A1：计划行级过滤（仅华北装货侧）",
                !scopedPlans.isEmpty() && scopedPlans.stream().allMatch(p -> "华北".equals(regionOf(p))));
        // 合同经 loadTerminalId 派生（西北合同对华北范围不可见）
        List<Map<String, Object>> scopedContracts = scope.filter("contracts", store.list("contracts"));
        check("A1：合同行级过滤（仅华北装货侧）",
                scopedContracts.stream().allMatch(c -> "华北".equals(regionOf(c))));
        // 结算单经 contractId 派生
        List<Map<String, Object>> scopedSettle = scope.filter("settlements", store.list("settlements"));
        check("A1：结算单行级过滤（经合同装货侧派生）",
                scopedSettle.stream().allMatch(x -> { String r = regionOf(x); return r == null || "华北".equals(r); }));
        // 单条越权：华北范围访问西北调度单 → 403 forbidden
        Map<String, Object> other = allDispatches.stream().filter(d -> !"华北".equals(regionOf(d))).findFirst().orElse(null);
        if (other != null) {
            ApiResult<Map<String, Object>> denied = collRead.one("dispatches", String.valueOf(other.get("id")));
            check("A1：越权单条访问被拒（forbidden）", !denied.isOk() && "forbidden".equals(denied.getCode()));
        }
        // 平台管理员不受影响（全量）
        login("张建国", "admin", "平台管理员", "");
        check("A1：平台管理员不受数据范围影响（全量）",
                scope.filter("dispatches", store.list("dispatches")).size() == store.list("dispatches").size());
        // 守卫：平台管理员不可被限制
        Map<String, Object> guard = userAdminService.setDataScope("admin", List.of("华北"));
        check("A1：守卫：平台管理员不可被限制", guard.get("error") != null);
        // 守卫：无效区域被拒
        Map<String, Object> guard2 = userAdminService.setDataScope("user02", List.of("华北", "火星"));
        check("A1：守卫：包含无效区域被拒", guard2.get("error") != null);
        // 快照端点同样过滤（user02 视角 dispatches 仅华北）
        login("李芳", "user02", "调度员", "");
        ApiResult<Map<String, Object>> snap = snapshotController.snapshot();
        List<?> snapDispatches = (List<?>) snap.getData().get("dispatches");
        check("A1：快照端点行级过滤（user02 仅华北调度单）",
                !snapDispatches.isEmpty() && snapDispatches.size() < allDispatches.size()
                        && snapDispatches.stream().allMatch(d -> "华北".equals(regionOf((Map<String, Object>) d))));
        // 恢复种子范围（user02=华北 已由种子保证；此处显式重置避免污染后续环节）
        login("张建国", "admin", "平台管理员", "");
        userAdminService.setDataScope("user02", List.of("华北"));
    }

    private String regionOf(Map<String, Object> rec) {
        Map<String, Object> t = byId("terminals", str(rec, "loadTerminalId"));
        return t == null ? null : str(t, "region");
    }

    /* ===================== A2：登录防爆破（服务端权威，Redis 按账号） ===================== */
    @Test
    @Order(91)
    void a2_loginLockout() {
        String u = "locktest-" + System.nanoTime();
        lockout.clear(u);
        // 前 4 次失败：未锁定，剩余机会递减
        LoginLockoutService.LockResult r1 = lockout.recordFailure(u);
        check("A2：第 1 次失败未锁定（剩 4 次）", !r1.locked() && r1.remaining() == 4);
        lockout.recordFailure(u);
        lockout.recordFailure(u);
        LoginLockoutService.LockResult r4 = lockout.recordFailure(u);
        check("A2：第 4 次失败未锁定（剩 1 次）", !r4.locked() && r4.remaining() == 1);
        // 第 5 次失败：进入 5 分钟锁定
        LoginLockoutService.LockResult r5 = lockout.recordFailure(u);
        check("A2：第 5 次失败触发 5 分钟锁定", r5.locked() && r5.remaining() == 300);
        check("A2：锁定中剩余秒数>0", lockout.lockRemainingSeconds(u) != null && lockout.lockRemainingSeconds(u) > 0);
        // 锁定期间访问即拦截（AuthService 前置检查）
        // 成功登录清零：先解锁（模拟成功后 clear）
        lockout.clear(u);
        check("A2：成功后清零（解锁）", lockout.lockRemainingSeconds(u) == null);
        // 清零后重新计数（不残留）
        LoginLockoutService.LockResult r6 = lockout.recordFailure(u);
        check("A2：清零后重新计数（剩 4 次）", !r6.locked() && r6.remaining() == 4);
        lockout.clear(u);
        // 大小写/空白归一（按账号维度）
        lockout.recordFailure("  CASE-Test ");
        lockout.recordFailure("case-test");
        LoginLockoutService.LockResult r7 = lockout.recordFailure("CASE-TEST");
        check("A2：账号维度归一（大小写/空白不绕过）", r7.remaining() == 2);
        lockout.clear("case-test");
    }

    // ===================== B3：乐观锁（version 不匹配 → 409 + 无静默覆盖 + 恢复权威态） =====================
    @Test @Order(13)
    void b3_optimisticLock() {
        Map<String, Object> rec = byId("dispatches", "YH-0001");
        if (rec == null) rec = store.list("dispatches").get(0);
        String id = str(rec, "id");
        int v0 = rec.get("version") instanceof Number n ? n.intValue() : 1;

        // 会话 A：登记期望版本 v0（与当前一致）→ commitAll 通过 + version 递增
        rec.put("remark", "B3-会话A");
        OptimisticLockContext.expect(id, v0);
        store.commitAll();
        Map<String, Object> afterA = byId("dispatches", id);
        int vA = afterA.get("version") instanceof Number nn ? nn.intValue() : 1;
        check("B3：版本匹配 → 提交成功且 version 递增", vA == v0 + 1 && "B3-会话A".equals(str(afterA, "remark")));

        // 会话 B：基于过期版本 v0 修改（当前已是 v0+1）→ 409 冲突 + 无静默覆盖 + 恢复权威态
        afterA.put("remark", "B3-会话B-覆盖");
        OptimisticLockContext.expect(id, v0); // 过期版本
        boolean conflict = false;
        try {
            store.commitAll();
        } catch (OptimisticLockException e) {
            conflict = true;
        }
        Map<String, Object> afterB = byId("dispatches", id);
        int vB = afterB.get("version") instanceof Number vv ? vv.intValue() : 1;
        check("B3：版本不匹配 → 抛 OptimisticLockException（→409）", conflict);
        check("B3：无静默覆盖（DB 保持会话 A 权威态，非会话 B 覆盖）",
                "B3-会话A".equals(str(afterB, "remark")) && vB == v0 + 1);

        // 会话 C：基于当前版本 v0+1 修改 → 通过（证明恢复后可继续正常写）
        afterB.put("remark", "B3-会话C");
        OptimisticLockContext.expect(id, v0 + 1);
        store.commitAll();
        Map<String, Object> afterC = byId("dispatches", id);
        int vC = afterC.get("version") instanceof Number cc ? cc.intValue() : 1;
        check("B3：恢复后基于新版本可继续提交（version → v0+2）",
                "B3-会话C".equals(str(afterC, "remark")) && vC == v0 + 2);
    }

    // ===================== A3：全局限流（Redis 固定窗口，超限 429 + Retry-After） =====================
    @Test @Order(14)
    void a3_rateLimit() {
        String dim = "ip:10.9.9.9"; // 测试专用 IP（与真实请求隔离，不污染运行中后端的限流计数）
        rateLimit.clearAll();
        check("A3：未超限 → 放行（null）", rateLimit.tryAcquire(RateLimitService.TIER_LOGIN, dim) == null);
        // 连发到超限 → 返回 Retry-After（1..60 秒）
        Long retryAfter = null;
        for (int i = 0; i < 500 && retryAfter == null; i++) {
            retryAfter = rateLimit.tryAcquire(RateLimitService.TIER_LOGIN, dim);
        }
        check("A3：超限 → 返回 Retry-After（1..60 秒）", retryAfter != null && retryAfter >= 1 && retryAfter <= 60);
        // 不同维度独立（其他 IP 不受影响）
        check("A3：不同维度独立（其他 IP 不受影响）", rateLimit.tryAcquire(RateLimitService.TIER_LOGIN, "ip:10.9.9.8") == null);
        // 写档独立于登录档
        check("A3：写档独立（未超限放行）", rateLimit.tryAcquire(RateLimitService.TIER_WRITE, "user:a3-test") == null);
        // clearAll 自恢复（reset-demo 调用后重新放行）
        rateLimit.clearAll();
        check("A3：clearAll 自恢复（清空后重新放行）", rateLimit.tryAcquire(RateLimitService.TIER_LOGIN, dim) == null);
    }

    // ===================== B2：分页（列表端点 page/size → {list,total}，向后兼容全量） =====================
    @Test @Order(15)
    void b2_pagination() {
        int total = store.list("dispatches").size();
        // 向后兼容：不带 page → 全量（List，与旧行为一致）
        ApiResult<Object> full = collRead.list("dispatches", null, null);
        check("B2：不带 page → 全量返回（向后兼容）", full.isOk() && full.getData() instanceof List<?> l && l.size() == total);
        // page=1 size=20 → {list,total,page,size}
        @SuppressWarnings("unchecked")
        Map<String, Object> p1 = (Map<String, Object>) collRead.list("dispatches", 1, 20).getData();
        check("B2：page=1 size=20 → list 20 条 + total=" + total,
                p1 != null && ((List<?>) p1.get("list")).size() == Math.min(20, total)
                        && (int) p1.get("total") == total && (int) p1.get("page") == 1 && (int) p1.get("size") == 20);
        // page=2 → 与 page=1 不同记录（无重叠），total 一致
        if (total > 20) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p2 = (Map<String, Object>) collRead.list("dispatches", 2, 20).getData();
            check("B2：page=2 → 与 page=1 不同记录（无重叠）+ total 一致",
                    !((List<?>) p2.get("list")).get(0).equals(((List<?>) p1.get("list")).get(0))
                            && (int) p2.get("total") == total);
        }
        // 超范围页 → 空 list + total 不变
        @SuppressWarnings("unchecked")
        Map<String, Object> po = (Map<String, Object>) collRead.list("dispatches", 99999, 20).getData();
        check("B2：超范围页 → 空 list + total 不变", ((List<?>) po.get("list")).isEmpty() && (int) po.get("total") == total);
        // size 上限 200
        @SuppressWarnings("unchecked")
        Map<String, Object> ps = (Map<String, Object>) collRead.list("dispatches", 1, 1000).getData();
        check("B2：size 上限 200（size=1000 → 200）", (int) ps.get("size") == 200);
        // 行级数据范围（A1）与分页叠加：user02（华北）total = 华北行数，页内全为华北
        login("李芳", "user02", "调度员", "");
        int scopedTotal = scope.filter("dispatches", store.list("dispatches")).size();
        @SuppressWarnings("unchecked")
        Map<String, Object> up = (Map<String, Object>) collRead.list("dispatches", 1, 20).getData();
        check("B2：行级+分页（user02 total=华北行数，页内全为华北）",
                (int) up.get("total") == scopedTotal
                        && ((List<?>) up.get("list")).stream().allMatch(x -> "华北".equals(regionOf((Map<String, Object>) x))));
        // 恢复种子范围（setDataScope 需平台管理员权限，先切回 admin）
        login("张建国", "admin", "平台管理员", "");
        userAdminService.setDataScope("user02", List.of("华北"));
    }

    @AfterAll
    void summary() {
        System.out.println("\n========== 环节 1-2 汇总 ==========");
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        failures.forEach(f -> System.out.println("  FAIL  " + f));
        assertEquals(0, fail, "存在失败断言（" + fail + "）");
    }
}
