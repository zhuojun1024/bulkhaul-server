package com.blms.store;

import com.blms.auth.Operator;
import com.blms.auth.RbacService;
import com.blms.common.AuditLog;
import com.blms.common.ForbiddenException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 共享上下文 + 确定性算法（等价前端 flow.js 的模块级共享状态与工具函数）。
 *
 * 数据访问：经 DataStore 取内存集合（Map 对象，与前端数组元素同构）。
 * 操作人：Operator.current()（JWT），等价前端 setOperator 后的 operator 变量。
 * 随机：运行时随机值用 ThreadLocalRandom（断言均为区间/关系断言，非精确值）；
 *       确定性算法（tareOf/loadVarianceOf/genId/calcSettlementFees）逐行翻译，不依赖随机序列。
 */
@Component
public class FlowCtx {

    public static final List<String> ACTIVE = List.of("loading", "intransit", "unloading");
    public static final List<String> BUSY_STATUSES = List.of("pending", "loading", "intransit", "unloading", "exception");
    public static final List<String> ROAD_MODES = List.of("公路", "多式联运");
    public static final int MAX_MESSAGES = 200;
    public static final double RECONCILE_TOLERANCE = 0.5;
    public static final Map<String, Double> QUALITY_STANDARD = Map.of("moisture", 10.0, "ash", 15.0);
    public static final Map<String, Double> QUALITY_RATE = Map.of("moisture", 0.015, "ash", 0.01);

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private final DataStore store;
    private final RbacService rbac;
    private final AuditLog audit;

    public FlowCtx(DataStore store, RbacService rbac, AuditLog audit) {
        this.store = store;
        this.rbac = rbac;
        this.audit = audit;
    }

    public DataStore store() { return store; }

    /* ===== 操作人 ===== */
    public Operator op() { return Operator.current(); }

    /** 当前操作人是否持有某操作权限（等价 operatorCan；未登录默认拒绝） */
    public boolean can(String action) { return rbac.can(op().getRole(), action); }

    /** RBAC 单点校验（等价 requireAction）：无权限抛 ForbiddenException（切面也会拦，此处供核心函数复用） */
    public void requireAction(String action) {
        if (!can(action)) {
            throw new ForbiddenException("当前角色「" + (op().getRole().isBlank() ? "未登录" : op().getRole()) + "」无此操作权限，操作已被服务层拦截");
        }
    }

    /* ===== 时间 ===== */
    public String now() { return LocalDateTime.now().format(DT); }
    public String today() { return LocalDateTime.now().format(D); }
    public String month() { return LocalDateTime.now().format(YM); }
    public String nowPlusMinutes(int m) { return LocalDateTime.now().plusMinutes(m).format(DT); }
    public String nowPlusHours(int h) { return LocalDateTime.now().plusHours(h).format(DT); }
    public String nowPlusDays(int d) { return LocalDateTime.now().plusDays(d).format(D); }

    /* ===== 随机（运行时） ===== */
    public int randInt(int min, int max) { return ThreadLocalRandom.current().nextInt(min, max + 1); }
    public <T> T pick(List<T> arr) { return arr.get(ThreadLocalRandom.current().nextInt(arr.size())); }

    /* ===== 确定性算法（逐行翻译自 base.js / utils） ===== */

    /** 车辆皮重（10-16t，按车辆 id 确定性派生） */
    public double tareOf(Map<String, Object> vehicle) {
        if (vehicle == null) return 13;
        String id = String.valueOf(vehicle.get("id"));
        int n = 0;
        for (char ch : id.toCharArray()) n += (int) ch;
        return round2(10 + (n % 61) / 10.0);
    }

    /** 进磅装货差异系数（±0.5%，按调度单 id 派生） */
    public double loadVarianceOf(String dispatchId) {
        int n = 0;
        for (char ch : String.valueOf(dispatchId).toCharArray()) n += (int) ch;
        return ((n % 1000) / 1000.0 - 0.5) * 0.01;
    }

    /** 正规 ID 生成：扫描已有 ID 取最大序列 + 1（删除不复用） */
    public String genId(String prefix, int width, List<Map<String, Object>> list) {
        int max = 0;
        String regex = "^" + prefix + "(\\d+)$";
        for (Map<String, Object> x : list) {
            String id = String.valueOf(x.get("id") == null ? "" : x.get("id"));
            if (id.matches(regex)) {
                max = Math.max(max, Integer.parseInt(id.substring(prefix.length())));
            }
        }
        return prefix + String.format("%0" + width + "d", max + 1);
    }

    /** round(n, digits)：四舍五入到指定位 */
    public static double round(double n, int digits) {
        double p = Math.pow(10, digits);
        return Math.round(n * p) / p;
    }
    public static double round2(double n) { return round(n, 2); }
    public static double round1(double n) { return round(n, 1); }

    /** 金额格式化：1234567.8 -> ¥1,234,567.80（与前端 toLocaleString('zh-CN') 同口径） */
    public static String formatMoney(double n) {
        return "¥" + String.format(java.util.Locale.ROOT, "%,.2f", n);
    }

    /* ===== 集合查找（等价 vehicleOf/driverOf/contractOf/planOf） ===== */
    @SuppressWarnings("unchecked")
    public Map<String, Object> byId(String coll, String id) {
        if (id == null) return null;
        for (Map<String, Object> x : store.list(coll)) {
            if (id.equals(x.get("id"))) return x;
        }
        return null;
    }
    public Map<String, Object> vehicleOf(String id) { return byId("vehicles", id); }
    public Map<String, Object> driverOf(String id) { return byId("drivers", id); }
    public Map<String, Object> contractOf(String id) { return byId("contracts", id); }
    public Map<String, Object> planOf(String id) { return byId("plans", id); }
    public Map<String, Object> dispatchOf(String id) { return byId("dispatches", id); }

    public boolean isRoadMode(String mode) { return ROAD_MODES.contains(mode == null ? "公路" : mode); }

    /** 运输线路距离（等价 base.js ROUTES）：装货场站 -> 卸货场站 */
    private static final Map<String, Double> ROUTE_DIST = new LinkedHashMap<>();
    static {
        ROUTE_DIST.put("T005>T001", 280.0);
        ROUTE_DIST.put("T006>T002", 420.0);
        ROUTE_DIST.put("T007>T002", 380.0);
        ROUTE_DIST.put("T008>T012", 350.0);
        ROUTE_DIST.put("T005>T011", 320.0);
        ROUTE_DIST.put("T003>T009", 1200.0);
        ROUTE_DIST.put("T003>T010", 900.0);
        ROUTE_DIST.put("T012>T009", 300.0);
        ROUTE_DIST.put("T006>T011", 520.0);
        ROUTE_DIST.put("T008>T009", 450.0);
    }

    /** 查线路距离（等价 ROUTES.find）；无匹配返回 null */
    public Double routeDistance(String from, String to) {
        return ROUTE_DIST.get(from + ">" + to);
    }

    /** 运输单元号（等价 unitNoOf，确定性派生） */
    public String unitNoOf(String mode, String seedStr) {
        int n = 0;
        for (char ch : String.valueOf(seedStr).toCharArray()) n = (n * 31 + (int) ch) % 100000;
        if ("铁路".equals(mode)) return "X" + (1000 + (n % 9000)) + "次";
        if ("水运".equals(mode)) return "冀散货" + (100 + (n % 899));
        if ("管道".equals(mode)) return "管线" + new String[]{"一", "二", "三", "四"}[n % 4] + "线";
        return "联运" + (2026000 + (n % 999));
    }

    /* ===== 通知（等价 notify） ===== */
    @SuppressWarnings("unchecked")
    public Map<String, Object> notify(String title, String type, String path, String content, List<String> to) {
        List<Map<String, Object>> msgs = store.list("messages");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", genId("MSG-", 4, msgs));
        m.put("title", title);
        m.put("content", content == null ? "" : content);
        m.put("type", type);
        m.put("path", path);
        m.put("time", now());
        m.put("read", false);
        m.put("to", to == null ? null : to);
        msgs.add(0, m);
        while (msgs.size() > MAX_MESSAGES) msgs.remove(msgs.size() - 1);
        return m;
    }

    /* ===== 审计（等价 logAction，成功/失败都记） ===== */
    public void logAction(String module, String action, String detail, String result) {
        audit.log(module, action, detail, result, op(), null);
    }

    /* ===== 角色/权限（等价 rolesWithAction/toRoles） ===== */
    @SuppressWarnings("unchecked")
    public List<String> rolesWithAction(String action) {
        Map<String, Object> rolePerms = store.obj("rolePerms");
        Set<String> names = new LinkedHashSet<>();
        names.addAll(rolePerms.keySet());
        names.addAll(RbacService.ROLE_ACTIONS.keySet());
        List<String> res = new ArrayList<>();
        for (String name : names) {
            Object permObj = rolePerms.get(name);
            Object actions;
            if (permObj instanceof Map) {
                actions = ((Map<String, Object>) permObj).get("actions");
            } else {
                actions = RbacService.ROLE_ACTIONS.get(name);
            }
            if (actions == null || (actions instanceof List && ((List<?>) actions).contains(action))) res.add(name);
        }
        return res;
    }

    @SafeVarargs
    public final List<String> toRoles(String... actions) {
        Set<String> s = new LinkedHashSet<>();
        for (String a : actions) for (String r : rolesWithAction(a)) s.add(r);
        return new ArrayList<>(s);
    }

    /* ===== 资源占用（等价 occupyResource/releaseResource） ===== */
    public void occupyResource(Map<String, Object> d) {
        Map<String, Object> v = vehicleOf(str(d, "vehicleId"));
        Map<String, Object> dr = driverOf(str(d, "driverId"));
        if (v != null && !"scrapped".equals(v.get("status"))) {
            v.put("status", "inuse");
            v.put("version", ((Number) v.getOrDefault("version", 1)).intValue() + 1);
        }
        if (dr != null) {
            dr.put("status", "onduty");
            dr.put("version", ((Number) dr.getOrDefault("version", 1)).intValue() + 1);
        }
    }

    public void releaseResource(Map<String, Object> d) {
        Map<String, Object> v = vehicleOf(str(d, "vehicleId"));
        Map<String, Object> dr = driverOf(str(d, "driverId"));
        if (v != null && store.list("dispatches").stream().noneMatch(x ->
                v.get("id").equals(x.get("vehicleId")) && ACTIVE.contains(x.get("status")))) {
            v.put("status", "idle");
            v.put("version", ((Number) v.getOrDefault("version", 1)).intValue() + 1);
        }
        if (dr != null && store.list("dispatches").stream().noneMatch(x ->
                dr.get("id").equals(x.get("driverId")) && ACTIVE.contains(x.get("status")))) {
            dr.put("status", "available");
            dr.put("version", ((Number) dr.getOrDefault("version", 1)).intValue() + 1);
        }
    }

    /* ===== 回卷（等价 rollupPlan/rollupContract） ===== */
    public void rollupPlan(String planId) {
        Map<String, Object> p = planOf(planId);
        if (p == null || "cancelled".equals(p.get("status"))) return;
        List<Map<String, Object>> ds = store.list("dispatches").stream()
                .filter(x -> planId.equals(x.get("planId"))).collect(Collectors.toList());
        if (ds.isEmpty()) return;
        double doneQty = ds.stream().filter(x -> "completed".equals(x.get("status")))
                .mapToDouble(x -> num(x.get("quantity"))).sum();
        double pQty = num(p.get("quantity"));
        p.put("progress", (int) Math.min(100, Math.round(doneQty / pQty * 100)));
        boolean allDone = ds.stream().allMatch(x -> "completed".equals(x.get("status")));
        boolean active = ds.stream().anyMatch(x -> ACTIVE.contains(x.get("status")) || "exception".equals(x.get("status")));
        p.put("status", allDone ? "completed" : (active || doneQty > 0) ? "intransit" : "dispatched");
        rollupContract(str(p, "contractId"));
    }

    public void rollupContract(String contractId) {
        Map<String, Object> c = contractOf(contractId);
        if (c == null || !"executing".equals(c.get("status"))) return;
        double doneQty = store.list("dispatches").stream()
                .filter(x -> contractId.equals(x.get("contractId")) && "completed".equals(x.get("status")))
                .mapToDouble(x -> num(x.get("quantity"))).sum();
        double cQty = num(c.get("quantity"));
        c.put("progress", (int) Math.min(100, Math.round(doneQty / cQty * 100)));
        if (intNum(c.get("progress")) >= 100) c.put("status", "completed");
    }

    /* ===== 仓储联动（等价 warehouseOut/warehouseIn） ===== */
    @SuppressWarnings("unchecked")
    public String warehouseOut(Map<String, Object> d) {
        Map<String, Object> t = byId("terminals", str(d, "loadTerminalId"));
        Map<String, Object> wh = null;
        if (t != null && t.get("warehouseId") != null && !String.valueOf(t.get("warehouseId")).isBlank()) {
            wh = byId("warehouses", String.valueOf(t.get("warehouseId")));
        }
        if (wh == null || !"operating".equals(wh.get("status"))) return null;
        final String whId = String.valueOf(wh.get("id"));
        String commodityId = str(d, "commodityId");
        List<Map<String, Object>> batches = store.list("inventories").stream()
                .filter(i -> whId.equals(i.get("warehouseId")) && commodityId.equals(i.get("commodityId")))
                .collect(Collectors.toList());
        if (batches.isEmpty()) return null;
        double available = batches.stream().filter(i -> "normal".equals(i.get("status")))
                .mapToDouble(i -> num(i.get("quantity"))).sum();
        double dQty = num(d.get("quantity"));
        if (available < dQty) {
            Map<String, Object> cm = byId("commodities", commodityId);
            String name = cm != null ? String.valueOf(cm.get("name")) : commodityId;
            return "装货场站可发库存不足：" + wh.get("name") + " 该商品（" + name + "）可发库存 " + available + " 吨，本车次需 " + dQty + " 吨，请先补库或调整车次数量后再装货";
        }
        double rest = dQty;
        List<Map<String, Object>> normal = batches.stream().filter(i -> "normal".equals(i.get("status")))
                .sorted(Comparator.comparing(i -> String.valueOf(i.get("inDate")))).collect(Collectors.toList());
        for (Map<String, Object> b : normal) {
            if (rest <= 0) break;
            double take = Math.min(num(b.get("quantity")), rest);
            b.put("quantity", round2(num(b.get("quantity")) - take));
            rest = round2(rest - take);
        }
        wh.put("used", Math.max(0, round2(num(wh.get("used")) - dQty)));
        logAction("仓储管理", "出库", "调度单 " + d.get("id") + " 装货：" + wh.get("name") + " 出库 " + dQty + " 吨", "success");
        checkInventoryAlert(wh, commodityId, available);
        return null;
    }

    public void warehouseIn(Map<String, Object> d) {
        Map<String, Object> t = byId("terminals", str(d, "unloadTerminalId"));
        Map<String, Object> wh = null;
        if (t != null && t.get("warehouseId") != null && !String.valueOf(t.get("warehouseId")).isBlank()) {
            wh = byId("warehouses", String.valueOf(t.get("warehouseId")));
        }
        if (wh == null || !"operating".equals(wh.get("status"))) return;
        Map<String, Object> w = store.list("weighings").stream()
                .filter(x -> d.get("id").equals(x.get("dispatchId")) && "出磅".equals(x.get("type")))
                .findFirst().orElse(null);
        double qty = w != null ? num(w.get("net")) : num(d.get("quantity"));
        List<Map<String, Object>> inv = store.list("inventories");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", genId("INV-", 4, inv));
        b.put("warehouseId", wh.get("id"));
        b.put("commodityId", d.get("commodityId"));
        b.put("batch", "B" + LocalDateTime.now().format(YYMMDD) + "-" + str(d, "id").substring(Math.max(0, str(d, "id").length() - 3)));
        b.put("quantity", qty);
        b.put("inDate", today());
        b.put("status", "normal");
        inv.add(0, b);
        wh.put("used", Math.min(num(wh.get("capacity")), num(wh.get("used")) + qty));
        logAction("仓储管理", "入库", "调度单 " + d.get("id") + " 卸货：" + wh.get("name") + " 入库 " + qty + " 吨", "success");
    }

    @SuppressWarnings("unchecked")
    public void checkInventoryAlert(Map<String, Object> wh, String commodityId, double beforeAvail) {
        List<Map<String, Object>> sqs = store.list("safetyStocks");
        Map<String, Object> sq = sqs.stream()
                .filter(x -> wh.get("id").equals(x.get("warehouseId")) && commodityId.equals(x.get("commodityId")))
                .findFirst().orElse(null);
        if (sq == null) return;
        double after = availableStockOf(String.valueOf(wh.get("id")), commodityId);
        double minQty = num(sq.get("minQty"));
        if (beforeAvail >= minQty && after < minQty) {
            Map<String, Object> cm = byId("commodities", commodityId);
            String name = cm != null ? String.valueOf(cm.get("name")) : commodityId;
            logAction("仓储管理", "安全库存预警", wh.get("name") + " " + name + " 可发库存 " + after + " 吨，跌破安全库存下限 " + minQty + " 吨", "success");
            notify("安全库存预警：" + wh.get("name") + " " + name + " 可发库存 " + after + " 吨，低于安全库存 " + minQty + " 吨",
                    "system", "/warehouse/inventory", "缺口 " + round2(minQty - after) + " 吨，请及时安排补库", toRoles("warehouse"));
        }
    }

    public double availableStockOf(String warehouseId, String commodityId) {
        return store.list("inventories").stream()
                .filter(i -> warehouseId.equals(i.get("warehouseId")) && commodityId.equals(i.get("commodityId")) && "normal".equals(i.get("status")))
                .mapToDouble(i -> num(i.get("quantity"))).sum();
    }

    /* ===== 结算费用（等价 settleQtyOf/calcSettlementFees/qualityDeductionQty） ===== */
    public double settleQtyOf(Map<String, Object> d) {
        Map<String, Object> w = store.list("weighings").stream()
                .filter(x -> d.get("id").equals(x.get("dispatchId")) && "出磅".equals(x.get("type")))
                .findFirst().orElse(null);
        return w != null ? num(w.get("net")) : num(d.get("quantity"));
    }

    /** 单车次结算运费（收入口径）：出磅净重 × 快照单价（与结算 freight 同口径，等价 dispatchRevenueOf） */
    public double dispatchRevenueOf(Map<String, Object> d) {
        Map<String, Object> c = byId("contracts", str(d, "contractId"));
        double price = d.get("unitPrice") != null ? num(d.get("unitPrice")) : (c != null ? num(c.get("unitPrice")) : 0);
        return Math.round(settleQtyOf(d) * price);
    }

    public double qualityDeductionQty(Map<String, Object> d) {
        if (d == null || !(d.get("quality") instanceof Map)) return 0;
        Map<String, Object> q = (Map<String, Object>) d.get("quality");
        double net = settleQtyOf(d);
        double over = Math.max(0, num(q.get("moisture")) - QUALITY_STANDARD.get("moisture")) * QUALITY_RATE.get("moisture")
                + Math.max(0, num(q.get("ash")) - QUALITY_STANDARD.get("ash")) * QUALITY_RATE.get("ash");
        return round2(net * over);
    }

    public Map<String, Object> calcSettlementFees(Map<String, Object> contract, List<Map<String, Object>> dispatches) {
        double fallbackPrice = contract != null ? num(contract.get("unitPrice")) : 0;
        double dispatchQuantity = dispatches.stream().mapToDouble(d -> num(d.get("quantity"))).sum();
        double totalQuantity = round2(dispatches.stream().mapToDouble(this::settleQtyOf).sum());
        double lossQty = round2(dispatchQuantity - totalQuantity);
        double freight = Math.round(dispatches.stream().mapToDouble(d -> settleQtyOf(d) * priceOf(d, fallbackPrice)).sum());
        double loadingFee = Math.round(totalQuantity * 8);
        double unloadingFee = Math.round(totalQuantity * 6);
        double lossDeduction = Math.round(dispatches.stream().mapToDouble(d -> (num(d.get("quantity")) - settleQtyOf(d)) * priceOf(d, fallbackPrice)).sum());
        double qualityQty = round2(dispatches.stream().mapToDouble(this::qualityDeductionQty).sum());
        double qualityDeduction = Math.round(dispatches.stream().mapToDouble(d -> qualityDeductionQty(d) * priceOf(d, fallbackPrice)).sum());
        double exceptionLoss = dispatches.stream().mapToDouble(d ->
                store.list("exceptions").stream()
                        .filter(e -> d.get("id").equals(e.get("dispatchId")) && "closed".equals(e.get("status")))
                        .mapToDouble(e -> num(e.get("cost"))).sum()).sum();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dispatchQuantity", dispatchQuantity);
        r.put("totalQuantity", totalQuantity);
        r.put("lossQty", lossQty);
        r.put("freight", freight);
        r.put("loadingFee", loadingFee);
        r.put("unloadingFee", unloadingFee);
        r.put("lossDeduction", lossDeduction);
        r.put("qualityQty", qualityQty);
        r.put("qualityDeduction", qualityDeduction);
        r.put("exceptionLoss", exceptionLoss);
        return r;
    }

    private double priceOf(Map<String, Object> d, double fallback) {
        Object up = d.get("unitPrice");
        return up != null ? num(up) : fallback;
    }

    /* ===== 趟次成本/应付（等价 tripCostOf/driverIncomeOf/outsourceFreightOf/doCreateTripPayable） ===== */
    public Map<String, Object> tripCostOf(Map<String, Object> d) {
        double dist = num(d.getOrDefault("distance", 300));
        Map<String, Object> v = vehicleOf(str(d, "vehicleId"));
        if (v == null) {
            double fuel = Math.round(dist * 2.2), wear = Math.round(dist * 0.5), toll = Math.round(dist * 0.35);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("fuel", fuel); r.put("wear", wear); r.put("driver", 0.0); r.put("toll", toll); r.put("depreciation", 0.0);
            r.put("total", fuel + wear + toll);
            return r;
        }
        double loadFactor = Math.max(0.5, Math.min(1, num(d.get("quantity")) / num(v.getOrDefault("capacity", 35))));
        double fuel = Math.round(dist * 1.8 * loadFactor);
        double wear = Math.round(dist * 0.6);
        double driver = Math.round(600 + dist * 0.25);
        double toll = Math.round(dist * 0.35);
        double depreciation = Math.round(num(v.getOrDefault("monthlyCost", 0)) / 30);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fuel", fuel); r.put("wear", wear); r.put("driver", driver); r.put("toll", toll); r.put("depreciation", depreciation);
        r.put("total", fuel + wear + driver + toll + depreciation);
        return r;
    }

    public double driverIncomeOf(Map<String, Object> d) {
        if (d == null || d.get("driverId") == null || String.valueOf(d.get("driverId")).isBlank()) return 0;
        return num(tripCostOf(d).get("driver"));
    }

    public double outsourceFreightOf(Map<String, Object> d) {
        Map<String, Object> v = d != null && d.get("vehicleId") != null ? vehicleOf(str(d, "vehicleId")) : null;
        if (v == null || !"外协".equals(v.get("owner"))) return 0;
        double dist = num(d.getOrDefault("distance", 300));
        return Math.round(dist * 1.5 + num(d.get("quantity")) * 25);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> doCreateTripPayable(Map<String, Object> d) {
        if (d == null || !"completed".equals(d.get("status")) || !isRoadMode(str(d, "mode"))) return null;
        List<Map<String, Object>> payables = store.list("payables");
        Map<String, Object> existing = payables.stream().filter(p -> d.get("id").equals(p.get("dispatchId"))).findFirst().orElse(null);
        if (existing != null) return existing;
        Map<String, Object> v = vehicleOf(str(d, "vehicleId"));
        double driverFee = driverIncomeOf(d);
        double outsourceFee = (v != null && "外协".equals(v.get("owner"))) ? outsourceFreightOf(d) : 0;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", genId("AF-", 4, payables));
        p.put("dispatchId", d.get("id"));
        p.put("driverId", d.get("driverId") == null ? "" : d.get("driverId"));
        p.put("vehicleId", d.get("vehicleId") == null ? "" : d.get("vehicleId"));
        p.put("plate", v != null ? v.get("plate") : "-");
        p.put("owner", v != null ? v.get("owner") : "-");
        p.put("driverFee", driverFee);
        p.put("outsourceFee", outsourceFee);
        p.put("amount", driverFee + outsourceFee);
        p.put("status", "pending");
        p.put("createTime", now());
        p.put("payTime", null);
        p.put("payMethod", null);
        payables.add(0, p);
        return p;
    }

    /* ===== 磅单登记（等价 pushWeighing） ===== */
    public void pushWeighing(Map<String, Object> d, String type, double net, String time) {
        Map<String, Object> v = vehicleOf(str(d, "vehicleId"));
        double tare = tareOf(v);
        List<Map<String, Object>> ws = store.list("weighings");
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("id", genId("BZ-", 5, ws));
        w.put("dispatchId", d.get("id"));
        w.put("plate", v != null ? v.get("plate") : "-");
        w.put("terminalId", "进磅".equals(type) ? d.get("loadTerminalId") : d.get("unloadTerminalId"));
        w.put("type", type);
        w.put("gross", round2(tare + net));
        w.put("tare", tare);
        w.put("net", round2(net));
        w.put("time", time);
        w.put("operator", randomName());
        ws.add(0, w);
    }

    public String randomName() {
        String[] surnames = "王李张刘陈杨黄赵吴周徐孙马朱胡郭何林罗郑梁谢宋唐许韩冯邓曹彭".split("");
        String[] given = {"伟","芳","娜","敏","静","丽","强","磊","军","洋","勇","杰","涛","明","超","霞","平","刚","华","建国",
                "建军","志强","海燕","文斌","秀兰","桂英","德福","春生","国庆","卫东","学文","永强","宝山","铁柱","大伟","金龙",
                "凤霞","玉梅","桂芳","春梅","志远","建华","立新","少康","国栋","子涵","雨泽","浩然","天佑"};
        return pick(Arrays.asList(surnames)) + pick(Arrays.asList(given));
    }

    /** 年检/驾照过期（等价 vehicleInspectionExpired/driverLicenseExpired） */
    public boolean vehicleInspectionExpired(Map<String, Object> v) {
        if (v == null || v.get("nextInspection") == null) return false;
        return String.valueOf(v.get("nextInspection")).compareTo(today()) < 0;
    }

    public boolean driverLicenseExpired(Map<String, Object> d) {
        if (d == null || d.get("licenseExpire") == null) return false;
        return String.valueOf(d.get("licenseExpire")).compareTo(today()) < 0;
    }

    /* ===== 电子围栏（等价 hashOffset/trackPointsOf/maxDeviationOf） ===== */
    /** 地图节点坐标（与前端 base.js MAP_NODES 一致） */
    public static final Map<String, double[]> MAP_NODES = new LinkedHashMap<>();
    static {
        MAP_NODES.put("T001", new double[]{520, 140});
        MAP_NODES.put("T002", new double[]{420, 165});
        MAP_NODES.put("T003", new double[]{470, 215});
        MAP_NODES.put("T004", new double[]{495, 155});
        MAP_NODES.put("T005", new double[]{330, 195});
        MAP_NODES.put("T006", new double[]{235, 250});
        MAP_NODES.put("T007", new double[]{195, 320});
        MAP_NODES.put("T008", new double[]{560, 330});
        MAP_NODES.put("T009", new double[]{690, 430});
        MAP_NODES.put("T010", new double[]{630, 75});
        MAP_NODES.put("T011", new double[]{385, 245});
        MAP_NODES.put("T012", new double[]{620, 395});
    }

    /** 地图坐标哈希偏移（与前端 hashOffset 一致，保证回放轨迹与实时位置一致） */
    public static int hashOffset(String id) {
        int h = 0;
        for (char ch : String.valueOf(id).toCharArray()) h = (h * 31 + ch) % 997;
        return (h % 5) - 2;
    }

    /** 轨迹点：沿线段均匀取 21 点，叠加按单号确定性派生的横向偏移（基础偏移 + 正弦波动） */
    public List<double[]> trackPointsOf(Map<String, Object> d) {
        double[] from = MAP_NODES.get(str(d, "loadTerminalId"));
        double[] to = MAP_NODES.get(str(d, "unloadTerminalId"));
        if (from == null || to == null) return new ArrayList<>();
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len == 0) len = 1;
        double nx = -dy / len;
        double ny = dx / len;
        int base = hashOffset(String.valueOf(d.get("id"))) * 5;
        double phase = (hashOffset(String.valueOf(d.get("id"))) % 6) * 0.7;
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            double p = i / 20.0;
            double off = base + 8 * Math.sin(i * 0.6 + phase);
            pts.add(new double[]{from[0] + dx * p + nx * off, from[1] + dy * p + ny * off});
        }
        return pts;
    }

    /** 轨迹最大偏离：轨迹点到线路直线的最大垂直距离（地图坐标单位） */
    public int maxDeviationOf(Map<String, Object> d) {
        double[] from = MAP_NODES.get(str(d, "loadTerminalId"));
        double[] to = MAP_NODES.get(str(d, "unloadTerminalId"));
        if (from == null || to == null) return 0;
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double len2 = dx * dx + dy * dy;
        if (len2 == 0) len2 = 1;
        double max = 0;
        for (double[] p : trackPointsOf(d)) {
            double t = ((p[0] - from[0]) * dx + (p[1] - from[1]) * dy) / len2;
            double px = from[0] + dx * t;
            double py = from[1] + dy * t;
            max = Math.max(max, Math.sqrt((p[0] - px) * (p[0] - px) + (p[1] - py) * (p[1] - py)));
        }
        return (int) Math.round(max);
    }

    /* ===== 天气/公告（等价 dashboard.js weatherOf + system.js announcements，纯函数/静态演示数据） ===== */
    private static final String[] WEATHERS = {"晴", "多云", "阴", "小雨", "晴", "晴", "多云", "雷阵雨"};

    /** 天气（按日期确定性派生，演示数据源；后续可替换为真实天气接口） */
    public Map<String, Object> weatherOf(String dateStr) {
        int n = 0;
        for (char ch : String.valueOf(dateStr).toCharArray()) n = (n * 31 + ch) % 997;
        String cond = WEATHERS[n % WEATHERS.length];
        int temp = 16 + (n % 18);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("city", "北京");
        r.put("temp", temp);
        r.put("cond", cond);
        r.put("tip", ("小雨".equals(cond) || "雷阵雨".equals(cond)) ? "雨天路滑，注意行车安全" : "适宜运输");
        return r;
    }

    /** 公告（静态演示数据，等价 system.js db.announcements） */
    public List<Map<String, Object>> announcements() {
        java.time.format.DateTimeFormatter MD = java.time.format.DateTimeFormatter.ofPattern("MM-dd");
        java.time.LocalDate now = java.time.LocalDate.now();
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(ann("G001", "关于 8 月份煤炭运输旺季运力保障的通知", now.minusDays(1), "重要"));
        list.add(ann("G002", "秦皇岛港 1 号煤仓 8 月 20 日检修，预计影响 2 天", now.minusDays(2), "场站"));
        list.add(ann("G003", "新版磅单系统上线，请各场站操作员完成培训", now.minusDays(4), "系统"));
        list.add(ann("G004", "汛期安全行车提示：关注 G6/G18 沿线雨情预警", now.minusDays(6), "安全"));
        list.add(ann("G005", "7 月结算单已全部完成对账，请各客户核对", now.minusDays(8), "结算"));
        return list;
    }

    private static Map<String, Object> ann(String id, String title, java.time.LocalDate date, String tag) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("date", date.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd")));
        m.put("tag", tag);
        return m;
    }

    /* ===== 类型转换工具 ===== */
    public static double num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
    public static int intNum(Object o) { return (int) num(o); }
    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
    public static boolean bool(Object o) { return o instanceof Boolean ? (Boolean) o : "true".equals(String.valueOf(o)); }
}
