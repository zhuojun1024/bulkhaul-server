package com.blms.service.report;

import com.blms.service.contract.ContractService;
import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 报表中心（与前端 report.js 1:1）：按业务口径从 DataStore 实时汇总。
 * 口径说明（与前端一致）：
 *  - 月度运营：车次/运量按"卸货完成时间"归月，结算按账单周期，收款按流水时间
 *  - 客户经营：结算/未付按客户账单汇总，授信占用 = 未付 - 可用预付款
 *  - 商品运量：损耗率 =（进磅净重-出磅净重）/进磅净重，按调度单配对
 *  - 场站吞吐：按磅单场站与类型汇总
 *  - 成本利润：单车次全成本（tripCostOf），收入 = 出磅净重×单价（dispatchRevenueOf），毛利 = 收入-成本
 *  - 运量口径统一：月度/客户/商品三报表运量 = 出磅净重之和（settleQtyOf，无出磅单回退调度量）
 */
@Service
public class ReportService {

    private final FlowCtx ctx;
    private final ContractService contractService;
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    public ReportService(FlowCtx ctx, ContractService contractService) {
        this.ctx = ctx;
        this.contractService = contractService;
    }

    /** 近 6 个月（含本月）YYYY-MM 列表，旧→新（等价 dayjs(NOW).subtract(5-i,'month')） */
    private List<String> lastSixMonths() {
        YearMonth now = YearMonth.now();
        List<String> months = new ArrayList<>();
        for (int i = 5; i >= 0; i--) months.add(now.minusMonths(i).format(YM));
        return months;
    }

    private static String monthOf(String dateTime) {
        return (dateTime != null && dateTime.length() >= 7) ? dateTime.substring(0, 7) : null;
    }

    /** 月度运营报表（近 6 个月） */
    public List<Map<String, Object>> monthlyReport() {
        List<Map<String, Object>> dispatches = ctx.store().list("dispatches");
        List<Map<String, Object>> settlements = ctx.store().list("settlements");
        List<Map<String, Object>> payments = ctx.store().list("payments");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String m : lastSixMonths()) {
            List<Map<String, Object>> done = dispatches.stream()
                    .filter(d -> "completed".equals(d.get("status")) && m.equals(monthOf(str(d, "unloadTime"))))
                    .toList();
            double volume = round1(done.stream().mapToDouble(ctx::settleQtyOf).sum());
            double settleAmount = settlements.stream().filter(s -> m.equals(str(s, "period")))
                    .mapToDouble(s -> FlowCtx.num(s.get("totalAmount"))).sum();
            double paidAmount = payments.stream()
                    .filter(p -> !FlowCtx.bool(p.get("reversed")) && m.equals(monthOf(str(p, "payTime"))))
                    .mapToDouble(p -> FlowCtx.num(p.get("amount"))).sum();
            long overdueCount = settlements.stream()
                    .filter(s -> "overdue".equals(s.get("status")) && m.equals(str(s, "period"))).count();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("month", m);
            r.put("trips", done.size());
            r.put("volume", volume);
            r.put("settleAmount", settleAmount);
            r.put("paidAmount", paidAmount);
            r.put("overdueCount", overdueCount);
            rows.add(r);
        }
        return rows;
    }

    /** 客户经营报表（按结算金额降序） */
    public List<Map<String, Object>> customerReport() {
        List<Map<String, Object>> customers = ctx.store().list("customers");
        List<Map<String, Object>> contracts = ctx.store().list("contracts");
        List<Map<String, Object>> dispatches = ctx.store().list("dispatches");
        List<Map<String, Object>> settlements = ctx.store().list("settlements");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : customers) {
            if (!("shipper".equals(c.get("type")) || "both".equals(c.get("type")))) continue;
            Set<String> contractIds = contracts.stream().filter(x -> c.get("id").equals(x.get("shipperId")))
                    .map(x -> str(x, "id")).collect(java.util.stream.Collectors.toSet());
            List<Map<String, Object>> done = dispatches.stream()
                    .filter(d -> "completed".equals(d.get("status")) && contractIds.contains(str(d, "contractId")))
                    .toList();
            double volume = round2(done.stream().mapToDouble(ctx::settleQtyOf).sum());
            double settleAmount = settlements.stream().filter(s -> c.get("id").equals(s.get("customerId")))
                    .mapToDouble(s -> FlowCtx.num(s.get("totalAmount"))).sum();
            double outstanding = contractService.outstandingOf(str(c, "id"));
            double prepay = contractService.prepaymentAvailable(str(c, "id"));
            double occupied = Math.max(0, outstanding - prepay);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", c.get("id"));
            r.put("name", c.get("name"));
            r.put("contracts", contractIds.size());
            r.put("trips", done.size());
            r.put("volume", volume);
            r.put("settleAmount", settleAmount);
            r.put("outstanding", outstanding);
            r.put("prepay", prepay);
            r.put("creditLimit", FlowCtx.num(c.get("creditLimit")));
            r.put("creditPct", FlowCtx.num(c.get("creditLimit")) > 0
                    ? Math.round(occupied / FlowCtx.num(c.get("creditLimit")) * 100) : 0);
            if (contractIds.size() > 0) rows.add(r);
        }
        rows.sort((a, b) -> Double.compare(FlowCtx.num(b.get("settleAmount")), FlowCtx.num(a.get("settleAmount"))));
        return rows;
    }

    /** 商品运量报表（含磅单损耗率） */
    public List<Map<String, Object>> commodityReport() {
        List<Map<String, Object>> weighings = ctx.store().list("weighings");
        List<Map<String, Object>> dispatches = ctx.store().list("dispatches");
        // 按调度单配对进/出磅
        Map<String, Map<String, Map<String, Object>>> byDispatch = new HashMap<>();
        for (Map<String, Object> w : weighings) {
            byDispatch.computeIfAbsent(str(w, "dispatchId"), k -> new HashMap<>()).put(str(w, "type"), w);
        }
        Map<String, double[]> lossMap = new HashMap<>(); // commodityId -> {loss, total}
        for (Map.Entry<String, Map<String, Map<String, Object>>> e : byDispatch.entrySet()) {
            Map<String, Map<String, Object>> pair = e.getValue();
            if (!pair.containsKey("进磅") || !pair.containsKey("出磅")) continue;
            Map<String, Object> d = dispatches.stream().filter(x -> e.getKey().equals(x.get("id"))).findFirst().orElse(null);
            if (d == null) continue;
            double[] agg = lossMap.computeIfAbsent(str(d, "commodityId"), k -> new double[2]);
            agg[0] += FlowCtx.num(pair.get("进磅").get("net")) - FlowCtx.num(pair.get("出磅").get("net"));
            agg[1] += FlowCtx.num(pair.get("进磅").get("net"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : ctx.store().list("commodities")) {
            List<Map<String, Object>> done = dispatches.stream()
                    .filter(d -> "completed".equals(d.get("status")) && c.get("id").equals(d.get("commodityId")))
                    .toList();
            double volume = round2(done.stream().mapToDouble(ctx::settleQtyOf).sum());
            double[] l = lossMap.get(str(c, "id"));
            double lossRate = (l != null && l[1] > 0) ? round2(l[0] / l[1] * 100) : 0;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", c.get("id"));
            r.put("name", c.get("name"));
            r.put("category", c.get("category"));
            r.put("trips", done.size());
            r.put("volume", volume);
            r.put("lossRate", lossRate);
            if (done.size() > 0) rows.add(r);
        }
        rows.sort((a, b) -> Double.compare(FlowCtx.num(b.get("volume")), FlowCtx.num(a.get("volume"))));
        return rows;
    }

    /** 场站吞吐报表（按磅单进出汇总） */
    public List<Map<String, Object>> terminalReport() {
        List<Map<String, Object>> weighings = ctx.store().list("weighings");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> t : ctx.store().list("terminals")) {
            List<Map<String, Object>> inW = weighings.stream()
                    .filter(w -> t.get("id").equals(w.get("terminalId")) && "进磅".equals(w.get("type"))).toList();
            List<Map<String, Object>> outW = weighings.stream()
                    .filter(w -> t.get("id").equals(w.get("terminalId")) && "出磅".equals(w.get("type"))).toList();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", t.get("id"));
            r.put("name", t.get("name"));
            r.put("loadTrips", inW.size());
            r.put("loadVolume", round1(inW.stream().mapToDouble(w -> FlowCtx.num(w.get("net"))).sum()));
            r.put("unloadTrips", outW.size());
            r.put("unloadVolume", round1(outW.stream().mapToDouble(w -> FlowCtx.num(w.get("net"))).sum()));
            if (inW.size() > 0 || outW.size() > 0) rows.add(r);
        }
        rows.sort((a, b) -> Double.compare(
                FlowCtx.num(b.get("loadVolume")) + FlowCtx.num(b.get("unloadVolume")),
                FlowCtx.num(a.get("loadVolume")) + FlowCtx.num(a.get("unloadVolume"))));
        return rows;
    }

    /** 成本利润报表：已完成车次单车次成本归集（收入=出磅净重×单价），按车辆/线路/月聚合 */
    public Map<String, Object> costReport() {
        List<Map<String, Object>> done = ctx.store().list("dispatches").stream()
                .filter(d -> "completed".equals(d.get("status"))).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> d : done) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("d", d);
            r.put("cost", FlowCtx.num(ctx.tripCostOf(d).get("total")));
            r.put("revenue", ctx.dispatchRevenueOf(d));
            rows.add(r);
        }
        double cost = rows.stream().mapToDouble(r -> FlowCtx.num(r.get("cost"))).sum();
        double revenue = rows.stream().mapToDouble(r -> FlowCtx.num(r.get("revenue"))).sum();
        Map<String, Object> summary = withProfit(new LinkedHashMap<String, Object>() {{
            put("trips", rows.size());
            put("cost", cost);
            put("revenue", revenue);
        }});
        // 按车辆（公路车次）
        Map<String, Map<String, Object>> vMap = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> d = (Map<String, Object>) r.get("d");
            if (d.get("vehicleId") == null) continue;
            vMap.computeIfAbsent(str(d, "vehicleId"), k -> {
                Map<String, Object> v = ctx.vehicleOf(str(d, "vehicleId"));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", k);
                m.put("plate", v != null ? v.get("plate") : "-");
                m.put("type", v != null ? v.get("type") : "");
                m.put("trips", 0);
                m.put("cost", 0.0);
                m.put("revenue", 0.0);
                return m;
            });
            Map<String, Object> m = vMap.get(str(d, "vehicleId"));
            m.put("trips", FlowCtx.intNum(m.get("trips")) + 1);
            m.put("cost", FlowCtx.num(m.get("cost")) + FlowCtx.num(r.get("cost")));
            m.put("revenue", FlowCtx.num(m.get("revenue")) + FlowCtx.num(r.get("revenue")));
        }
        List<Map<String, Object>> byVehicle = vMap.values().stream().map(ReportService::withProfit)
                .sorted((a, b) -> FlowCtx.intNum(b.get("trips")) - FlowCtx.intNum(a.get("trips"))).toList();
        // 按线路（装货场→卸货场）
        Map<String, Map<String, Object>> rMap = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> d = (Map<String, Object>) r.get("d");
            String key = str(d, "loadTerminalId") + str(d, "unloadTerminalId");
            rMap.computeIfAbsent(key, k -> {
                Map<String, Object> lt = ctx.store().list("terminals").stream()
                        .filter(t -> t.get("id").equals(d.get("loadTerminalId"))).findFirst().orElse(null);
                Map<String, Object> ut = ctx.store().list("terminals").stream()
                        .filter(t -> t.get("id").equals(d.get("unloadTerminalId"))).findFirst().orElse(null);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", k);
                m.put("route", (lt != null ? lt.get("name") : "-") + "→" + (ut != null ? ut.get("name") : "-"));
                m.put("trips", 0);
                m.put("cost", 0.0);
                m.put("revenue", 0.0);
                return m;
            });
            Map<String, Object> m = rMap.get(key);
            m.put("trips", FlowCtx.intNum(m.get("trips")) + 1);
            m.put("cost", FlowCtx.num(m.get("cost")) + FlowCtx.num(r.get("cost")));
            m.put("revenue", FlowCtx.num(m.get("revenue")) + FlowCtx.num(r.get("revenue")));
        }
        List<Map<String, Object>> byRoute = rMap.values().stream().map(ReportService::withProfit)
                .sorted((a, b) -> FlowCtx.intNum(b.get("trips")) - FlowCtx.intNum(a.get("trips"))).toList();
        // 按月（近 6 个月，卸货完成时间归月）
        List<Map<String, Object>> byMonth = new ArrayList<>();
        for (String m : lastSixMonths()) {
            List<Map<String, Object>> list = rows.stream()
                    .filter(r -> m.equals(monthOf(str(((Map<String, Object>) r.get("d")), "unloadTime")))).toList();
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("month", m);
            base.put("trips", list.size());
            base.put("cost", list.stream().mapToDouble(r -> FlowCtx.num(r.get("cost"))).sum());
            base.put("revenue", list.stream().mapToDouble(r -> FlowCtx.num(r.get("revenue"))).sum());
            byMonth.add(withProfit(base));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("byVehicle", byVehicle);
        result.put("byRoute", byRoute);
        result.put("byMonth", byMonth);
        return result;
    }

    private static Map<String, Object> withProfit(Map<String, Object> r) {
        double revenue = FlowCtx.num(r.get("revenue"));
        double cost = FlowCtx.num(r.get("cost"));
        r.put("profit", revenue - cost);
        r.put("margin", revenue > 0 ? round1((revenue - cost) / revenue * 100) : 0);
        return r;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
}
