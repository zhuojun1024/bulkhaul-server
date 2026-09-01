package com.blms.service.admin;

import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 管理后台主数据 CRUD（与前端 flow.js 管理域 1:1）。
 * 商品/客户/场站/仓库/司机/车辆 的新建·编辑·启停·导入·车辆报修恢复。
 * 权限码与前端 requireAction 一致：commodity/customer/terminal/warehouse-maint/driver/vehicle。
 */
@Service
public class AdminService {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final FlowCtx ctx;

    public AdminService(FlowCtx ctx) {
        this.ctx = ctx;
    }

    private void commit() { ctx.store().commitAll(); }

    /* ================= 商品 ================= */
    public Map<String, Object> saveCommodity(Map<String, Object> p) {
        ctx.requireAction("commodity");
        String name = str(p, "name").trim();
        if (name.isEmpty()) return err("请输入商品名称");
        List<Map<String, Object>> cs = ctx.store().list("commodities");
        if (p.get("id") != null) {
            Map<String, Object> c = ctx.byId("commodities", str(p, "id"));
            if (c == null) return err("商品不存在");
            if (cs.stream().anyMatch(x -> !c.get("id").equals(x.get("id")) && name.equals(x.get("name"))))
                return err("商品名称「" + name + "」已存在");
            c.put("name", name);
            if (p.get("category") != null) c.put("category", p.get("category"));
            if (p.get("unit") != null) c.put("unit", p.get("unit"));
            if (p.get("density") != null) c.put("density", num(p.get("density")));
            if (p.get("price") != null) c.put("price", num(p.get("price")));
            ctx.logAction("商品管理", "编辑商品", "商品 " + c.get("id") + " 更新：" + name, "success");
            commit();
            return ok(c.get("id"));
        }
        if (cs.stream().anyMatch(x -> name.equals(x.get("name")))) return err("商品名称「" + name + "」已存在");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", ctx.genId("CM", 3, cs));
        c.put("name", name);
        c.put("category", p.get("category") != null ? p.get("category") : "煤炭");
        c.put("unit", p.get("unit") != null ? p.get("unit") : "吨");
        c.put("density", p.get("density") != null ? num(p.get("density")) : 1);
        c.put("price", p.get("price") != null ? num(p.get("price")) : 0);
        c.put("indicators", List.of(Map.of("name", "质量要求", "value", "按合同约定")));
        c.put("status", "active");
        c.put("totalVolume", 0);
        c.put("remark", "");
        cs.add(c);
        ctx.logAction("商品管理", "新建商品", "商品 " + c.get("id") + " 创建：" + name, "success");
        commit();
        return ok(c.get("id"));
    }

    public Map<String, Object> toggleCommodityStatus(String id) {
        ctx.requireAction("commodity");
        Map<String, Object> c = ctx.byId("commodities", id);
        if (c == null) return err("商品不存在");
        c.put("status", "active".equals(c.get("status")) ? "inactive" : "active");
        ctx.logAction("商品管理", "active".equals(c.get("status")) ? "启用商品" : "停用商品",
                "商品 " + c.get("name") + (c.get("status").equals("active") ? " 启用" : " 停用"), "success");
        commit();
        return ok(null);
    }

    public Map<String, Object> importCommodities(List<Map<String, Object>> rows) {
        ctx.requireAction("commodity");
        List<String> created = new ArrayList<>(), skipped = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> cs = ctx.store().list("commodities");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            String name = str(row, "name").trim();
            if (name.isEmpty()) { errors.add(Map.of("row", i + 1, "reason", "商品名称不能为空")); continue; }
            if (cs.stream().anyMatch(c -> name.equals(c.get("name")))) { skipped.add(name); continue; }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", ctx.genId("CM", 3, cs));
            c.put("name", name);
            c.put("category", str(row, "category").trim().isEmpty() ? "煤炭" : str(row, "category").trim());
            c.put("unit", str(row, "unit").trim().isEmpty() ? "吨" : str(row, "unit").trim());
            c.put("density", num(row.get("density")) > 0 ? num(row.get("density")) : 1);
            c.put("price", num(row.get("price")) > 0 ? Math.round(num(row.get("price"))) : 0);
            c.put("indicators", List.of(Map.of("name", "质量要求", "value", "按合同约定")));
            c.put("status", "active");
            c.put("totalVolume", 0);
            c.put("remark", "导入");
            cs.add(c);
            created.add(name);
        }
        if (!created.isEmpty() || !skipped.isEmpty() || !errors.isEmpty()) {
            ctx.logAction("商品管理", "数据导入", "导入商品 " + created.size() + " 条，跳过重复 " + skipped.size() + " 条，失败 " + errors.size() + " 条", "success");
            ctx.notify("商品数据导入完成", "system", "/commodity",
                    "导入 " + created.size() + " 条，跳过重复 " + skipped.size() + " 条，失败 " + errors.size() + " 条", ctx.toRoles("commodity"));
        }
        commit();
        return Map.of("created", created, "skipped", skipped, "errors", errors);
    }

    /* ================= 客户 ================= */
    public Map<String, Object> toggleCustomerStatus(String id) {
        ctx.requireAction("customer");
        Map<String, Object> c = ctx.byId("customers", id);
        if (c == null) return err("客户不存在");
        if ("active".equals(c.get("status"))) {
            c.put("status", "frozen");
            ctx.logAction("客户管理", "客户冻结", "客户 " + c.get("name") + " 冻结，不可新建合同", "success");
        } else {
            c.put("status", "active");
            ctx.logAction("客户管理", "客户解冻", "客户 " + c.get("name") + " 解冻，恢复合作", "success");
        }
        commit();
        return ok(null);
    }

    public Map<String, Object> importCustomers(List<Map<String, Object>> rows) {
        ctx.requireAction("customer");
        List<String> created = new ArrayList<>(), skipped = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> cs = ctx.store().list("customers");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            String name = str(row, "name").trim();
            if (name.isEmpty()) { errors.add(Map.of("row", i + 1, "reason", "客户名称不能为空")); continue; }
            if (cs.stream().anyMatch(c -> name.equals(c.get("name")))) { skipped.add(name); continue; }
            String level = row.get("level") != null && List.of("A", "B", "C").contains(row.get("level")) ? str(row, "level") : "C";
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", ctx.genId("CUS", 3, cs));
            c.put("name", name);
            c.put("type", Map.of("发货方", "shipper", "收货方", "consignee", "双向客户", "both").getOrDefault(str(row, "type"), "shipper"));
            c.put("region", str(row, "region").trim().isEmpty() ? "其他" : str(row, "region").trim());
            c.put("address", "");
            c.put("level", level);
            c.put("contact", str(row, "contact").trim().isEmpty() ? "-" : str(row, "contact").trim());
            c.put("phone", str(row, "phone").trim().isEmpty() ? "-" : str(row, "phone").trim());
            c.put("creditLimit", num(row.get("creditLimit")) > 0 ? Math.round(num(row.get("creditLimit")))
                    : (level.equals("A") ? 5000000 : level.equals("B") ? 2000000 : 500000));
            c.put("totalBusiness", 0);
            c.put("joinDate", ctx.today());
            c.put("status", "active");
            c.put("remark", "导入");
            cs.add(c);
            created.add(name);
        }
        if (!created.isEmpty() || !skipped.isEmpty() || !errors.isEmpty()) {
            ctx.logAction("客户管理", "数据导入", "导入客户 " + created.size() + " 条，跳过重复 " + skipped.size() + " 条，失败 " + errors.size() + " 条", "success");
            ctx.notify("客户数据导入完成", "system", "/customer",
                    "导入 " + created.size() + " 条，跳过重复 " + skipped.size() + " 条，失败 " + errors.size() + " 条", ctx.toRoles("customer"));
        }
        commit();
        return Map.of("created", created, "skipped", skipped, "errors", errors);
    }

    /* ================= 场站 ================= */
    public Map<String, Object> saveTerminal(Map<String, Object> p) {
        ctx.requireAction("terminal");
        String name = str(p, "name").trim();
        if (name.isEmpty()) return err("请输入场站名称");
        String type = p.get("type") != null && List.of("loading", "unloading", "both").contains(p.get("type")) ? str(p, "type") : "both";
        double capacity = num(p.get("capacity"));
        if (capacity <= 0) return err("日能力须大于 0");
        List<Map<String, Object>> ts = ctx.store().list("terminals");
        if (p.get("id") != null) {
            Map<String, Object> t = ctx.byId("terminals", str(p, "id"));
            if (t == null) return err("场站不存在");
            if (ts.stream().anyMatch(x -> !t.get("id").equals(x.get("id")) && name.equals(x.get("name"))))
                return err("场站名称「" + name + "」已存在");
            t.put("name", name);
            t.put("type", type);
            if (p.get("region") != null) t.put("region", p.get("region"));
            if (p.get("address") != null) t.put("address", p.get("address"));
            t.put("capacity", capacity);
            if (p.get("contact") != null) t.put("contact", p.get("contact"));
            if (p.get("phone") != null) t.put("phone", p.get("phone"));
            if (p.containsKey("warehouseId")) t.put("warehouseId", p.get("warehouseId") == null ? null : p.get("warehouseId"));
            if (p.containsKey("remark")) t.put("remark", str(p, "remark"));
            ctx.logAction("场站管理", "编辑场站", "场站 " + t.get("id") + " 更新：" + name, "success");
            commit();
            return ok(t.get("id"));
        }
        if (ts.stream().anyMatch(x -> name.equals(x.get("name")))) return err("场站名称「" + name + "」已存在");
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", ctx.genId("T", 3, ts));
        t.put("name", name);
        t.put("type", type);
        t.put("region", p.get("region") != null ? p.get("region") : "华北");
        t.put("address", p.get("address") != null ? p.get("address") : "");
        t.put("capacity", capacity);
        t.put("warehouseId", p.get("warehouseId"));
        t.put("contact", p.get("contact") != null ? p.get("contact") : "");
        t.put("phone", p.get("phone") != null ? p.get("phone") : "");
        t.put("status", "operating");
        t.put("todayThroughput", 0);
        t.put("queueVehicles", 0);
        t.put("remark", p.get("remark") != null ? str(p, "remark") : "");
        ts.add(t);
        ctx.logAction("场站管理", "新建场站", "场站 " + t.get("id") + " 创建：" + name + "（" + t.get("region") + "，日能力 " + capacity + " 吨）", "success");
        commit();
        return ok(t.get("id"));
    }

    /* ================= 仓库 ================= */
    public Map<String, Object> saveWarehouse(Map<String, Object> p) {
        ctx.requireAction("warehouse-maint");
        String name = str(p, "name").trim();
        if (name.isEmpty()) return err("请输入仓库名称");
        double capacity = num(p.get("capacity"));
        if (capacity <= 0) return err("容量须大于 0");
        List<Map<String, Object>> ws = ctx.store().list("warehouses");
        if (p.get("id") != null) {
            Map<String, Object> w = ctx.byId("warehouses", str(p, "id"));
            if (w == null) return err("仓库不存在");
            if (ws.stream().anyMatch(x -> !w.get("id").equals(x.get("id")) && name.equals(x.get("name"))))
                return err("仓库名称「" + name + "」已存在");
            if (capacity < FlowCtx.num(w.get("used"))) return err("容量不能低于已用库存 " + w.get("used") + " 吨");
            w.put("name", name);
            if (p.get("type") != null) w.put("type", p.get("type"));
            if (p.get("address") != null) w.put("address", p.get("address"));
            w.put("capacity", capacity);
            if (p.get("manager") != null) w.put("manager", p.get("manager"));
            if (p.get("phone") != null) w.put("phone", p.get("phone"));
            if (p.containsKey("remark")) w.put("remark", str(p, "remark"));
            ctx.logAction("仓储管理", "编辑仓库", "仓库 " + w.get("id") + " 更新：" + name, "success");
            commit();
            return ok(w.get("id"));
        }
        if (ws.stream().anyMatch(x -> name.equals(x.get("name")))) return err("仓库名称「" + name + "」已存在");
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("id", ctx.genId("WH", 3, ws));
        w.put("name", name);
        w.put("type", p.get("type") != null ? p.get("type") : "煤仓");
        w.put("address", p.get("address") != null ? p.get("address") : "");
        w.put("capacity", capacity);
        w.put("used", 0);
        w.put("manager", p.get("manager") != null ? p.get("manager") : "");
        w.put("phone", p.get("phone") != null ? p.get("phone") : "");
        w.put("status", "operating");
        w.put("remark", p.get("remark") != null ? str(p, "remark") : "");
        ws.add(w);
        ctx.logAction("仓储管理", "新建仓库", "仓库 " + w.get("id") + " 创建：" + name + "（" + w.get("type") + "，容量 " + capacity + " 吨）", "success");
        commit();
        return ok(w.get("id"));
    }

    /* ================= 司机 ================= */
    public Map<String, Object> saveDriver(Map<String, Object> p) {
        ctx.requireAction("driver");
        String name = str(p, "name").trim();
        String phone = str(p, "phone").trim();
        if (name.isEmpty()) return err("请填写司机姓名");
        if (phone.isEmpty()) return err("请填写手机号");
        List<Map<String, Object>> ds = ctx.store().list("drivers");
        if (p.get("id") != null) {
            Map<String, Object> d = ctx.byId("drivers", str(p, "id"));
            if (d == null) return err("司机不存在");
            if (ds.stream().anyMatch(x -> !d.get("id").equals(x.get("id")) && phone.equals(x.get("phone"))))
                return err("手机号「" + phone + "」已被其他司机使用");
            d.put("name", name);
            d.put("phone", phone);
            if (p.get("licenseType") != null) d.put("licenseType", p.get("licenseType"));
            if (p.get("licenseNo") != null) d.put("licenseNo", str(p, "licenseNo"));
            if (p.get("licenseExpire") != null) d.put("licenseExpire", p.get("licenseExpire"));
            if (p.containsKey("emergencyContact")) d.put("emergencyContact", str(p, "emergencyContact"));
            if (p.containsKey("remark")) d.put("remark", str(p, "remark"));
            ctx.logAction("司机管理", "编辑司机", "司机 " + d.get("name") + "（" + phone + "）信息更新", "success");
            commit();
            return ok(d.get("id"));
        }
        if (ds.stream().anyMatch(x -> phone.equals(x.get("phone")))) return err("手机号「" + phone + "」已存在，请更换");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", ctx.genId("D", 3, ds));
        d.put("name", name);
        d.put("phone", phone);
        d.put("licenseType", p.get("licenseType") != null ? p.get("licenseType") : "A2");
        d.put("licenseNo", p.get("licenseNo") != null ? str(p, "licenseNo") : "");
        d.put("licenseExpire", p.get("licenseExpire") != null ? p.get("licenseExpire") : LocalDate.now().plusYears(3).format(D));
        d.put("status", "available");
        d.put("version", 1);
        d.put("rating", 5);
        d.put("totalTrips", 0);
        d.put("totalMileage", 0);
        d.put("joinDate", ctx.today());
        d.put("emergencyContact", p.get("emergencyContact") != null ? str(p, "emergencyContact") : "");
        d.put("remark", p.get("remark") != null ? str(p, "remark") : "");
        ds.add(d);
        ctx.logAction("司机管理", "新增司机", "新增司机 " + d.get("name") + "（" + phone + "，" + d.get("licenseType") + " 证）", "success");
        commit();
        return ok(d.get("id"));
    }

    public Map<String, Object> toggleDriverStatus(String id) {
        ctx.requireAction("driver");
        Map<String, Object> d = ctx.byId("drivers", id);
        if (d == null) return err("司机不存在");
        if ("disabled".equals(d.get("status"))) {
            d.put("status", "available");
            ctx.logAction("司机管理", "司机启用", "司机 " + d.get("name") + " 启用，恢复可派单", "success");
        } else {
            if (ctx.store().list("dispatches").stream().anyMatch(x -> d.get("id").equals(x.get("driverId")) && FlowCtx.ACTIVE.contains(x.get("status"))))
                return err("司机 " + d.get("name") + " 有执行中车次，无法停用");
            d.put("status", "disabled");
            ctx.logAction("司机管理", "司机停用", "司机 " + d.get("name") + " 停用，不可派单", "success");
        }
        Map<String, Object> u = ctx.store().list("users").stream().filter(x -> d.get("id").equals(x.get("driverId"))).findFirst().orElse(null);
        if (u != null) u.put("status", "disabled".equals(d.get("status")) ? "disabled" : "active");
        commit();
        return ok(null);
    }

    public Map<String, Object> importDrivers(List<Map<String, Object>> rows) {
        ctx.requireAction("driver");
        List<String> created = new ArrayList<>(), skipped = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> ds = ctx.store().list("drivers");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            String name = str(row, "name").trim();
            String phone = str(row, "phone").trim();
            if (name.isEmpty() || phone.isEmpty()) { errors.add(Map.of("row", i + 1, "reason", "姓名和手机号为必填项")); continue; }
            if (ds.stream().anyMatch(x -> phone.equals(x.get("phone")))) { skipped.add(phone); continue; }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", ctx.genId("D", 3, ds));
            d.put("name", name);
            d.put("phone", phone);
            d.put("licenseType", row.get("licenseType") != null && List.of("A2", "B2").contains(row.get("licenseType")) ? str(row, "licenseType") : "A2");
            d.put("licenseNo", str(row, "licenseNo").trim());
            d.put("licenseExpire", row.get("licenseExpire") != null ? row.get("licenseExpire") : LocalDate.now().plusYears(3).format(D));
            d.put("status", "available");
            d.put("version", 1);
            d.put("rating", 5);
            d.put("totalTrips", 0);
            d.put("totalMileage", 0);
            d.put("joinDate", ctx.today());
            d.put("emergencyContact", str(row, "emergencyContact").trim());
            d.put("remark", "导入");
            ds.add(d);
            created.add(name);
        }
        if (!created.isEmpty() || !skipped.isEmpty() || !errors.isEmpty()) {
            ctx.logAction("司机管理", "数据导入", "导入司机 " + created.size() + " 条，跳过重复 " + skipped.size() + " 条，失败 " + errors.size() + " 条", "success");
            ctx.notify("司机数据导入完成", "system", "/driver",
                    "导入 " + created.size() + " 条，跳过重复 " + skipped.size() + " 条，失败 " + errors.size() + " 条", ctx.toRoles("driver"));
        }
        commit();
        return Map.of("created", created, "skipped", skipped, "errors", errors);
    }

    /* ================= 车辆 ================= */
    public Map<String, Object> importVehicles(List<Map<String, Object>> rows) {
        ctx.requireAction("vehicle");
        List<String> created = new ArrayList<>(), skipped = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> vs = ctx.store().list("vehicles");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            String plate = str(row, "plate").trim();
            if (plate.isEmpty()) { errors.add(Map.of("row", i + 1, "reason", "车牌号不能为空")); continue; }
            if (vs.stream().anyMatch(v -> plate.equals(v.get("plate")))) { skipped.add(plate); continue; }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", ctx.genId("V", 3, vs));
            v.put("plate", plate);
            v.put("type", str(row, "type").trim().isEmpty() ? "重型半挂车" : str(row, "type").trim());
            v.put("capacity", num(row.get("capacity")) > 0 ? num(row.get("capacity")) : 35);
            v.put("owner", "自有".equals(row.get("owner")) ? "自有" : "外协");
            v.put("fuelType", str(row, "fuelType").trim().isEmpty() ? "柴油" : str(row, "fuelType").trim());
            v.put("status", "idle");
            v.put("version", 1);
            v.put("purchaseDate", ctx.today());
            v.put("nextInspection", LocalDate.now().plusDays(365).format(D));
            v.put("mileage", 0);
            v.put("monthlyCost", 0);
            v.put("remark", "导入");
            vs.add(v);
            created.add(plate);
        }
        if (!created.isEmpty() || !skipped.isEmpty() || !errors.isEmpty()) {
            ctx.logAction("车辆管理", "数据导入", "导入车辆 " + created.size() + " 条，跳过重复 " + skipped.size() + " 条，失败 " + errors.size() + " 条", "success");
            ctx.notify("车辆数据导入完成", "system", "/vehicle",
                    "导入 " + created.size() + " 条，跳过重复 " + skipped.size() + " 条，失败 " + errors.size() + " 条", ctx.toRoles("vehicle"));
        }
        commit();
        return Map.of("created", created, "skipped", skipped, "errors", errors);
    }

    public Map<String, Object> sendVehicleRepair(String id, String reason) {
        ctx.requireAction("vehicle");
        Map<String, Object> v = ctx.byId("vehicles", id);
        if (v == null) return err("车辆不存在");
        if (!"idle".equals(v.get("status"))) return err("车辆 " + v.get("plate") + " 当前非\"空闲\"状态，无法报修");
        v.put("status", "maintenance");
        ctx.logAction("车辆管理", "车辆报修", "车辆 " + v.get("plate") + " 报修：" + (reason == null || reason.isBlank() ? "未填写原因" : reason), "success");
        commit();
        return ok(null);
    }

    public Map<String, Object> resumeVehicle(String id) {
        ctx.requireAction("vehicle");
        Map<String, Object> v = ctx.byId("vehicles", id);
        if (v == null) return err("车辆不存在");
        if (!"maintenance".equals(v.get("status"))) return err("车辆 " + v.get("plate") + " 当前非\"维修中\"状态，无法恢复");
        v.put("status", "idle");
        ctx.logAction("车辆管理", "车辆恢复", "车辆 " + v.get("plate") + " 维修完成，恢复空闲", "success");
        commit();
        return ok(null);
    }

    /* ===== 工具 ===== */
    private static Map<String, Object> ok(Object id) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        if (id != null) r.put("id", id);
        return r;
    }
    private static Map<String, Object> err(String e) { return Map.of("error", e); }
    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }
    private static double num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    /* ================= 电子围栏参数（track 监控页可配置，OBJ_COLL fenceConfig） ================= */
    public Map<String, Object> getFenceConfig() {
        Map<String, Object> cfg = ctx.store().obj("fenceConfig");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("config", cfg);
        return r;
    }
    public Map<String, Object> saveFenceConfig(Map<String, Object> p) {
        ctx.requireAction("terminal");
        Map<String, Object> cfg = ctx.store().obj("fenceConfig");
        if (p.get("enabled") != null) cfg.put("enabled", Boolean.parseBoolean(String.valueOf(p.get("enabled"))));
        if (p.get("deviateLimit") != null) cfg.put("deviateLimit", (int) num(p.get("deviateLimit")));
        if (p.get("delayMinutes") != null) cfg.put("delayMinutes", (int) num(p.get("delayMinutes")));
        ctx.logAction("在途监控", "围栏参数", "围栏参数更新：启用=" + cfg.get("enabled") + " 偏离阈值=" + cfg.get("deviateLimit") + " 超时=" + cfg.get("delayMinutes") + "分钟", "success");
        commit();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("config", cfg);
        return r;
    }
}
