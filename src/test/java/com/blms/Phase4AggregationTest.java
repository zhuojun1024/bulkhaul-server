package com.blms;

import com.blms.auth.Operator;
import com.blms.common.ApiResult;
import com.blms.service.dispatch.DispatchController;
import com.blms.service.report.DashboardService;
import com.blms.service.report.ReportController;
import com.blms.service.report.ReportService;
import com.blms.store.DataStore;
import com.blms.store.FlowCtx;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Phase 4 阶段 1：后端接线口径对拍测试。
 * 目的：ReportController（dashboard/kpi、charts、workbench、report/*）与
 * DispatchController /{id}/detail 暴露的端点值，与"测试内独立重算"
 * （不同代码路径：直接读 DataStore 按前端 dashboard.js/report.js 口径重算）一致，
 * 防前后端聚合口径漂移（前端侧由 npm test 556 断言锚定同一口径）。
 * 状态无关：不依赖种子具体值，只断言自洽（端点值 == 独立重算值）。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase4AggregationTest {

    @Autowired DataStore store;
    @Autowired FlowCtx ctx;
    @Autowired DashboardService dashboardService;
    @Autowired ReportService reportService;
    @Autowired ReportController reportController;
    @Autowired DispatchController dispatchController;

    int pass = 0, fail = 0;
    final List<String> failures = new ArrayList<>();

    void check(String name, boolean cond) {
        if (cond) pass++;
        else { fail++; failures.add(name); }
    }

    void check(String name, boolean cond, String detail) {
        if (cond) pass++;
        else { fail++; failures.add(name + "  ← " + detail); }
    }

    @BeforeAll
    void setup() {
        Operator op = new Operator("张建国", "admin", "平台管理员", "");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(op, null, Collections.emptyList()));
    }

    @AfterAll
    void summary() {
        System.out.println("\n========== Phase4 接线对拍 汇总 ==========");
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        for (String f : failures) System.out.println("  [FAIL] " + f);
        if (fail > 0) throw new AssertionError(fail + " 项口径对拍失败：\n" + String.join("\n", failures));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }

    /* ===== 1. 看板 KPI：端点值 == 独立重算 ===== */
    @Test
    void kpiParity() {
        Map<String, Object> kpi = reportController.kpi().getData();
        List<Map<String, Object>> dispatches = store.list("dispatches");
        List<Map<String, Object>> completed = dispatches.stream()
                .filter(d -> "completed".equals(d.get("status"))).toList();
        YearMonth now = YearMonth.now();
        double monthVolume = completed.stream()
                .filter(d -> str(d, "dispatchTime") != null && now.equals(YearMonth.parse(str(d, "dispatchTime").substring(0, 7))))
                .mapToDouble(d -> num(d.get("quantity"))).sum();
        check("P4-1 kpi.monthVolume 对拍", Math.abs(num(kpi.get("monthVolume")) - monthVolume) < 0.01,
                "endpoint=" + kpi.get("monthVolume") + " recomputed=" + monthVolume);
        long intransit = dispatches.stream().filter(d -> "intransit".equals(d.get("status"))).count();
        check("P4-1 kpi.intransitCount 对拍", num(kpi.get("intransitCount")) == intransit);
        long activeCustomers = store.list("customers").stream().filter(c -> "active".equals(c.get("status"))).count();
        check("P4-1 kpi.customerCount 对拍", num(kpi.get("customerCount")) == activeCustomers);
        long executing = store.list("contracts").stream().filter(c -> "executing".equals(c.get("status"))).count();
        check("P4-1 kpi.executingContracts 对拍", num(kpi.get("executingContracts")) == executing);
        // 准时率/利用率：0-100 区间 + 与 service 直调一致（controller 与 service 同源）
        check("P4-1 kpi.onTimeRate 区间", num(kpi.get("onTimeRate")) >= 0 && num(kpi.get("onTimeRate")) <= 100);
        check("P4-1 kpi.utilization 区间", num(kpi.get("utilization")) >= 0 && num(kpi.get("utilization")) <= 100);
        check("P4-1 kpi.safeDays == service.safeDays()", num(kpi.get("safeDays")) == dashboardService.safeDays());
    }

    /* ===== 2. 看板图表：端点值 == 独立重算 ===== */
    @Test
    void chartsParity() {
        Map<String, Object> charts = reportController.charts().getData();
        // 商品结构：类别 -> 已完成调度量之和
        Map<String, Double> catSum = new LinkedHashMap<>();
        for (Map<String, Object> d : store.list("dispatches")) {
            if (!"completed".equals(d.get("status"))) continue;
            Map<String, Object> c = ctx.byId("commodities", str(d, "commodityId"));
            catSum.merge((c != null && c.get("category") != null) ? str(c, "category") : "其他", num(d.get("quantity")), Double::sum);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cs = (List<Map<String, Object>>) charts.get("commodityStructure");
        check("P4-2 commodityStructure 类别数对拍", cs.size() == catSum.size(), "endpoint=" + cs.size() + " recomputed=" + catSum.size());
        double csTotal = cs.stream().mapToDouble(m -> num(m.get("value"))).sum();
        check("P4-2 commodityStructure 总量对拍", Math.abs(csTotal - catSum.values().stream().mapToDouble(Double::doubleValue).sum()) < 0.01);
        // 运输方式占比：合同量之和 == 全部合同 quantity 之和
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ms = (List<Map<String, Object>>) charts.get("modeShare");
        double modeTotal = ms.stream().mapToDouble(m -> num(m.get("value"))).sum();
        double allContractQty = store.list("contracts").stream().mapToDouble(c -> num(c.get("quantity"))).sum();
        check("P4-2 modeShare 总量对拍", Math.abs(modeTotal - allContractQty) < 0.01);
        // 场站吞吐 TOP8：数量 ≤8 且降序
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tt = (List<Map<String, Object>>) charts.get("terminalThroughput");
        check("P4-2 terminalThroughput ≤8", tt.size() <= 8);
        boolean desc = true;
        for (int i = 1; i < tt.size(); i++) {
            if (num(tt.get(i - 1).get("value")) < num(tt.get(i).get("value"))) desc = false;
        }
        check("P4-2 terminalThroughput 降序", desc);
        // 车辆状态分布：总数 == 车辆数
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vs = (List<Map<String, Object>>) charts.get("vehicleStatus");
        long vsTotal = vs.stream().mapToLong(m -> (long) num(m.get("value"))).sum();
        check("P4-2 vehicleStatus 总数对拍", vsTotal == store.list("vehicles").size());
    }

    /* ===== 3. 工作台：端点值 == 独立重算 ===== */
    @Test
    void workbenchParity() {
        Map<String, Object> stats = reportController.workbenchStats().getData();
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        long todayDispatches = store.list("dispatches").stream()
                .filter(d -> str(d, "dispatchTime") != null && str(d, "dispatchTime").startsWith(today)).count();
        check("P4-3 workbench.todayDispatches 对拍", num(stats.get("todayDispatches")) == todayDispatches);
        double todayLoad = store.list("weighings").stream()
                .filter(w -> "进磅".equals(w.get("type")) && str(w, "time") != null && str(w, "time").startsWith(today))
                .mapToDouble(w -> num(w.get("net"))).sum();
        check("P4-3 workbench.todayLoad 对拍", Math.abs(num(stats.get("todayLoad")) - todayLoad) < 0.05,
                "endpoint=" + stats.get("todayLoad") + " recomputed=" + todayLoad);
        // 待办：各 key 计数对拍
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> todos = (List<Map<String, Object>>) (List<?>) reportController.workbenchTodos().getData();
        Map<String, Map<String, Object>> byKey = new HashMap<>();
        for (Map<String, Object> t : todos) byKey.put(str(t, "key"), t);
        long pendingContracts = store.list("contracts").stream().filter(c -> "pending".equals(c.get("status"))).count();
        check("P4-3 todos.pendingContracts 对拍",
                byKey.containsKey("contract") == (pendingContracts > 0)
                        && (pendingContracts == 0 || str(byKey.get("contract"), "title").contains(String.valueOf(pendingContracts))));
        long pendingExceptions = store.list("exceptions").stream().filter(e -> "pending".equals(e.get("status"))).count();
        check("P4-3 todos.pendingExceptions 对拍",
                byKey.containsKey("exception") == (pendingExceptions > 0)
                        && (pendingExceptions == 0 || str(byKey.get("exception"), "title").contains(String.valueOf(pendingExceptions))));
    }

    /* ===== 4. 五报表：端点值 == 独立重算（抽样口径） ===== */
    @Test
    void reportParity() {
        // 月度：每月 trips == 独立重算
        var monthly = reportController.monthly().getData();
        check("P4-4 monthly 覆盖 6 个月", monthly.size() == 6);
        boolean tripsOk = true, volumeOk = true;
        for (Map<String, Object> row : monthly) {
            String m = str(row, "month");
            long trips = store.list("dispatches").stream()
                    .filter(d -> "completed".equals(d.get("status")) && str(d, "unloadTime") != null && str(d, "unloadTime").startsWith(m))
                    .count();
            if (num(row.get("trips")) != trips) tripsOk = false;
            double volume = store.list("dispatches").stream()
                    .filter(d -> "completed".equals(d.get("status")) && str(d, "unloadTime") != null && str(d, "unloadTime").startsWith(m))
                    .mapToDouble(ctx::settleQtyOf).sum();
            if (Math.abs(num(row.get("volume")) - volume) >= 0.05) volumeOk = false;
        }
        check("P4-4 monthly.trips 逐月对拍", tripsOk);
        check("P4-4 monthly.volume 逐月对拍（settleQtyOf 口径）", volumeOk);
        // 客户：结算金额合计 == 全部账单 totalAmount 之和（客户报表只含 shipper/both 且有合同者，
        // 故对拍口径 = 这些客户的账单之和）
        var customer = reportController.customer().getData();
        Set<String> customerIds = new HashSet<>();
        for (Map<String, Object> row : customer) customerIds.add(str(row, "id"));
        double custSettle = customer.stream().mapToDouble(r -> num(r.get("settleAmount"))).sum();
        double recomputed = store.list("settlements").stream()
                .filter(s -> customerIds.contains(str(s, "customerId")))
                .mapToDouble(s -> num(s.get("totalAmount"))).sum();
        check("P4-4 customer.settleAmount 合计对拍", Math.abs(custSettle - recomputed) < 0.01,
                "endpoint=" + custSettle + " recomputed=" + recomputed);
        // 商品：运量合计 == 全部已完成车次 settleQtyOf 之和（商品报表只含 trips>0 者 = 全部有完成车次的商品）
        var commodity = reportController.commodity().getData();
        double commVolume = commodity.stream().mapToDouble(r -> num(r.get("volume"))).sum();
        double allDoneVolume = store.list("dispatches").stream()
                .filter(d -> "completed".equals(d.get("status")))
                .mapToDouble(ctx::settleQtyOf).sum();
        check("P4-4 commodity.volume 合计对拍", Math.abs(commVolume - allDoneVolume) < 0.05,
                "endpoint=" + commVolume + " recomputed=" + allDoneVolume);
        // 场站：进出磅票数合计 == 全部磅单数
        var terminal = reportController.terminal().getData();
        long loadTrips = terminal.stream().mapToLong(r -> (long) num(r.get("loadTrips"))).sum();
        long unloadTrips = terminal.stream().mapToLong(r -> (long) num(r.get("unloadTrips"))).sum();
        long inW = store.list("weighings").stream().filter(w -> "进磅".equals(w.get("type"))).count();
        long outW = store.list("weighings").stream().filter(w -> "出磅".equals(w.get("type"))).count();
        check("P4-4 terminal 磅单数对拍", loadTrips == inW && unloadTrips == outW,
                "load " + loadTrips + "/" + inW + " unload " + unloadTrips + "/" + outW);
        // 成本：summary.trips == 已完成车次数
        Map<String, Object> cost = reportController.cost().getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) cost.get("summary");
        long doneCount = store.list("dispatches").stream().filter(d -> "completed".equals(d.get("status"))).count();
        check("P4-4 cost.summary.trips 对拍", num(summary.get("trips")) == doneCount);
    }

    /* ===== 5. 调度详情聚合端点：join 完整性 + 派生值对拍 ===== */
    @Test
    void dispatchDetailParity() {
        // 选一个公路已完成车次（有进/出磅 + 质检 + 车辆司机 + 合同）
        Map<String, Object> target = store.list("dispatches").stream()
                .filter(d -> "completed".equals(d.get("status")) && d.get("vehicleId") != null)
                .findFirst().orElse(null);
        check("P4-5 存在公路已完成车次（前置）", target != null);
        if (target == null) return;
        String id = str(target, "id");
        ApiResult<Map<String, Object>> r = dispatchController.detail(id);
        check("P4-5 detail 端点 ok", r.isOk());
        Map<String, Object> data = r.getData();
        check("P4-5 detail.dispatch 同源", id.equals(str((Map<String, Object>) data.get("dispatch"), "id")));
        check("P4-5 detail.commodity join", data.get("commodity") != null
                && str(target, "commodityId").equals(str((Map<String, Object>) data.get("commodity"), "id")));
        check("P4-5 detail.vehicle join", data.get("vehicle") != null
                && str(target, "vehicleId").equals(str((Map<String, Object>) data.get("vehicle"), "id")));
        check("P4-5 detail.driver join", data.get("driver") != null
                && str(target, "driverId").equals(str((Map<String, Object>) data.get("driver"), "id")));
        check("P4-5 detail.loadTerminal join", data.get("loadTerminal") != null
                && str(target, "loadTerminalId").equals(str((Map<String, Object>) data.get("loadTerminal"), "id")));
        check("P4-5 detail.unloadTerminal join", data.get("unloadTerminal") != null
                && str(target, "unloadTerminalId").equals(str((Map<String, Object>) data.get("unloadTerminal"), "id")));
        check("P4-5 detail.contract join", data.get("contract") != null
                && str(target, "contractId").equals(str((Map<String, Object>) data.get("contract"), "id")));
        check("P4-5 detail.plan join", data.get("plan") != null
                && str(target, "planId").equals(str((Map<String, Object>) data.get("plan"), "id")));
        // weighings：端点列表 == 独立过滤
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ws = (List<Map<String, Object>>) data.get("weighings");
        long wCount = store.list("weighings").stream().filter(w -> id.equals(w.get("dispatchId"))).count();
        check("P4-5 detail.weighings 计数对拍", ws.size() == wCount, "endpoint=" + ws.size() + " recomputed=" + wCount);
        check("P4-5 detail.weighings 公路已完成含出磅", ws.stream().anyMatch(w -> "出磅".equals(w.get("type"))));
        // 派生值：settleQty/qualityDeduction 与 FlowCtx 直调一致（前端同口径）
        check("P4-5 detail.settleQty 对拍", Math.abs(num(data.get("settleQty")) - ctx.settleQtyOf(target)) < 0.001);
        check("P4-5 detail.qualityDeduction 对拍", Math.abs(num(data.get("qualityDeduction")) - ctx.qualityDeductionQty(target)) < 0.001);
        // 404：不存在的调度单
        ApiResult<Map<String, Object>> missing = dispatchController.detail("PD-NOT-EXIST");
        check("P4-5 detail 不存在 → 404 not-found", !missing.isOk() && "not-found".equals(missing.getCode()));
    }
}
