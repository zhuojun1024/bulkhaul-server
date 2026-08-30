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
