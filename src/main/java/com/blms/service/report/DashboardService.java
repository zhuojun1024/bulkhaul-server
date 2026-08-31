package com.blms.service.report;

import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 看板 KPI（与前端 dashboard.js computeKpi/computeSafeDays 1:1）：
 * 从 DataStore 实时汇总（数据变更后自动更新）。
 * 口径修正：准时率/利用率按实际执行数据计算，不再取随机值。
 */
@Service
public class DashboardService {

    private final FlowCtx ctx;
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public DashboardService(FlowCtx ctx) {
        this.ctx = ctx;
    }

    /** 安全运行天数：距最近一次"重大"事故的间隔（无重大事故时按最近一次任意级别事故计） */
    public int safeDays() {
        List<Map<String, Object>> accidents = ctx.store().list("accidents");
        List<Map<String, Object>> major = accidents.stream().filter(a -> "重大".equals(a.get("level"))).toList();
        List<Map<String, Object>> pool = major.isEmpty() ? accidents : major;
        if (pool.isEmpty()) return 365;
        String latest = pool.stream().map(a -> String.valueOf(a.get("time")))
                .max(Comparator.naturalOrder()).orElse(null);
        if (latest == null) return 365;
        try {
            return Math.max(1, (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(latest, D), LocalDate.now()));
        } catch (Exception e) {
            return 365;
        }
    }

    /** 核心 KPI（口径修正：准时率/利用率按实际执行数据计算） */
    public Map<String, Object> kpi() {
        List<Map<String, Object>> dispatches = ctx.store().list("dispatches");
        List<Map<String, Object>> completed = dispatches.stream()
                .filter(d -> "completed".equals(d.get("status"))).toList();
        int intransitCount = (int) dispatches.stream().filter(d -> "intransit".equals(d.get("status"))).count();
        YearMonth monthStart = YearMonth.now();
        double monthVolume = completed.stream()
                .filter(d -> d.get("dispatchTime") != null && monthStart.equals(YearMonth.parse(String.valueOf(d.get("dispatchTime")).substring(0, 7))))
                .mapToDouble(d -> FlowCtx.num(d.get("quantity"))).sum();
        // 准时交付率：已完成车次中，实际运输时长（装货→卸货）不超过理论时长（50km/h）×120% + 1h 装卸缓冲 的比例
        List<Map<String, Object>> measurable = completed.stream()
                .filter(d -> d.get("loadTime") != null && d.get("unloadTime") != null && d.get("distance") != null).toList();
        long onTime = measurable.stream().filter(d -> {
            double actualMin = minutesBetween(str(d, "loadTime"), str(d, "unloadTime"));
            double threshold = (FlowCtx.num(d.get("distance")) / 50) * 60 * 1.2 + 60;
            return actualMin <= threshold;
        }).count();
        double onTimeRate = measurable.isEmpty() ? 0 : round1(onTime * 100.0 / measurable.size());
        // 车辆利用率：运输中车辆 / 非报废车辆
        List<Map<String, Object>> vehicles = ctx.store().list("vehicles");
        long usable = vehicles.stream().filter(v -> !"scrapped".equals(v.get("status"))).count();
        long inuse = vehicles.stream().filter(v -> "inuse".equals(v.get("status"))).count();
        double utilization = usable > 0 ? round1(inuse * 100.0 / usable) : 0;
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("monthVolume", monthVolume);
        kpi.put("intransitCount", intransitCount);
        kpi.put("onTimeRate", onTimeRate);
        kpi.put("safeDays", safeDays());
        kpi.put("customerCount", ctx.store().list("customers").stream().filter(c -> "active".equals(c.get("status"))).count());
        kpi.put("executingContracts", ctx.store().list("contracts").stream().filter(c -> "executing".equals(c.get("status"))).count());
        kpi.put("utilization", utilization);
        return kpi;
    }

private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    /* ===== 看板图表（与前端 dashboard.js computeCommodityStructure/computeModeShare/
     * computeTerminalThroughput/computeVehicleStatus 1:1）===== */

    /** 商品结构（按类别汇总已完成调度量） */
    public List<Map<String, Object>> commodityStructure() {
        Map<String, Double> categoryMap = new LinkedHashMap<>();
        for (Map<String, Object> d : ctx.store().list("dispatches")) {
            if (!"completed".equals(d.get("status"))) continue;
            Map<String, Object> c = ctx.byId("commodities", str(d, "commodityId"));
            String cat = (c != null && c.get("category") != null) ? str(c, "category") : "其他";
            categoryMap.merge(cat, FlowCtx.num(d.get("quantity")), Double::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        categoryMap.forEach((name, value) -> out.add(Map.of("name", name, "value", value)));
        return out;
    }

    /** 运输方式占比（按合同量） */
    public List<Map<String, Object>> modeShare() {
        Map<String, Double> modeMap = new LinkedHashMap<>();
        for (Map<String, Object> c : ctx.store().list("contracts")) {
            modeMap.merge(str(c, "mode"), FlowCtx.num(c.get("quantity")), Double::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        modeMap.forEach((name, value) -> out.add(Map.of("name", name, "value", value)));
        return out;
    }

    /** 场站吞吐量 TOP8（名称去后缀，与前端正则同口径） */
    public List<Map<String, Object>> terminalThroughput() {
        return ctx.store().list("terminals").stream()
                .map(t -> Map.<String, Object>of("name", String.valueOf(t.get("name"))
                        .replaceAll("(煤炭|矿石|散货)?(码头|装车站|煤运站|原料场)$", ""),
                        "value", FlowCtx.num(t.get("todayThroughput"))))
                .sorted((a, b) -> Double.compare(FlowCtx.num(b.get("value")), FlowCtx.num(a.get("value"))))
                .limit(8)
                .toList();
    }

    /** 车辆状态分布 */
    public List<Map<String, Object>> vehicleStatus() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Map<String, Object> v : ctx.store().list("vehicles")) {
            String label = switch (str(v, "status")) {
                case "inuse" -> "运输中";
                case "idle" -> "空闲";
                case "maintenance" -> "维修中";
                case "overload" -> "超载预警";
                case "scrapped" -> "已报废";
                default -> v.get("status") == null ? "未知" : str(v, "status");
            };
            map.merge(label, 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        map.forEach((name, value) -> out.add(Map.of("name", name, "value", value)));
        return out;
    }

    /* ===== 工作台（与前端 dashboard.js workbenchStats/workbenchTodoList 1:1）===== */

    /** 工作台指标（今日/本月 + 环比基期：昨日/上月） */
    public Map<String, Object> workbenchStats() {
        String today = LocalDate.now().format(D);
        String month = YearMonth.now().format(YM);
        String yesterday = LocalDate.now().minusDays(1).format(D);
        String prevMonth = YearMonth.now().minusMonths(1).format(YM);
        List<Map<String, Object>> weighings = ctx.store().list("weighings");
        List<Map<String, Object>> dispatches = ctx.store().list("dispatches");
        List<Map<String, Object>> settlements = ctx.store().list("settlements");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("todayDispatches", dispatches.stream().filter(d -> str(d, "dispatchTime") != null && str(d, "dispatchTime").startsWith(today)).count());
        r.put("todayLoad", FlowCtx.round1(sumNet(weighings, "进磅", today)));
        r.put("todayUnload", FlowCtx.round1(sumNet(weighings, "出磅", today)));
        r.put("monthSettled", settlements.stream()
                .filter(s -> "settled".equals(s.get("status")) && str(s, "settleDate") != null && str(s, "settleDate").startsWith(month))
                .mapToDouble(s -> FlowCtx.num(s.get("totalAmount"))).sum() / 10000);
        r.put("yesterdayDispatches", dispatches.stream().filter(d -> str(d, "dispatchTime") != null && str(d, "dispatchTime").startsWith(yesterday)).count());
        r.put("yesterdayLoad", FlowCtx.round1(sumNet(weighings, "进磅", yesterday)));
        r.put("yesterdayUnload", FlowCtx.round1(sumNet(weighings, "出磅", yesterday)));
        r.put("prevMonthSettled", settlements.stream()
                .filter(s -> "settled".equals(s.get("status")) && str(s, "settleDate") != null && str(s, "settleDate").startsWith(prevMonth))
                .mapToDouble(s -> FlowCtx.num(s.get("totalAmount"))).sum() / 10000);
        return r;
    }

    private static double sumNet(List<Map<String, Object>> weighings, String type, String day) {
        return weighings.stream()
                .filter(w -> type.equals(w.get("type")) && str(w, "time") != null && str(w, "time").startsWith(day))
                .mapToDouble(w -> FlowCtx.num(w.get("net"))).sum();
    }

    /** 工作台待办列表（纯数据：key/title/desc/path；图标配色为视图层关注点） */
    public List<Map<String, Object>> workbenchTodoList() {
        List<Map<String, Object>> list = new ArrayList<>();
        List<Map<String, Object>> pendingContracts = ctx.store().list("contracts").stream()
                .filter(c -> "pending".equals(c.get("status"))).toList();
        if (!pendingContracts.isEmpty()) {
            list.add(todo("contract", pendingContracts.size() + " 份合同待审批", str(pendingContracts.get(0), "name"), "/contract"));
        }
        List<Map<String, Object>> pendingDispatches = ctx.store().list("dispatches").stream()
                .filter(d -> "pending".equals(d.get("status"))).toList();
        if (!pendingDispatches.isEmpty()) {
            list.add(todo("dispatch", pendingDispatches.size() + " 张调度单待装货",
                    "最早下发：" + str(pendingDispatches.get(0), "dispatchTime"), "/dispatch"));
        }
        List<Map<String, Object>> pendingExceptions = ctx.store().list("exceptions").stream()
                .filter(e -> "pending".equals(e.get("status"))).toList();
        if (!pendingExceptions.isEmpty()) {
            list.add(todo("exception", pendingExceptions.size() + " 条异常待处理",
                    str(pendingExceptions.get(0), "description"), "/exception"));
        }
        List<Map<String, Object>> pendingSettlements = ctx.store().list("settlements").stream()
                .filter(s -> "pending".equals(s.get("status"))).toList();
        if (!pendingSettlements.isEmpty()) {
            double total = pendingSettlements.stream().mapToDouble(s -> FlowCtx.num(s.get("totalAmount"))).sum();
            list.add(todo("settlement", pendingSettlements.size() + " 笔结算待对账",
                    "合计 " + FlowCtx.formatMoney(total), "/settlement"));
        }
        List<Map<String, Object>> overdue = ctx.store().list("settlements").stream()
                .filter(s -> "overdue".equals(s.get("status"))).toList();
        if (!overdue.isEmpty()) {
            list.add(todo("overdue", overdue.size() + " 笔结算已逾期", "最早周期：" + str(overdue.get(0), "period"), "/settlement"));
        }
        return list;
    }

    private static Map<String, Object> todo(String key, String title, String desc, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("title", title);
        m.put("desc", desc == null ? "" : desc);
        m.put("path", path);
        return m;
    }

    private static double minutesBetween(String from, String to) {
        try {
            java.time.LocalDateTime f = java.time.LocalDateTime.parse(from, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            java.time.LocalDateTime t = java.time.LocalDateTime.parse(to, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            return java.time.Duration.between(f, t).toMinutes();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
}
