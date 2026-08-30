package com.blms.service.dispatch;

import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 调度服务（与 flow.js 主链路 1:1）：
 * createDispatches（事务化两阶段提交）/ confirmLoad / depart / arrive / confirmUnload /
 * cancelDispatch / reassignDispatch / reportException / resumeDispatch。
 * 每个写方法：requireAction → 改内存 → commitAll（写锁内回写）。
 */
@Service
public class DispatchService {

    private final FlowCtx ctx;
    private final com.blms.service.settlement.SettlementService settlementService;

    public DispatchService(FlowCtx ctx, com.blms.service.settlement.SettlementService settlementService) {
        this.ctx = ctx;
        this.settlementService = settlementService;
    }

    /** 只读：取调度单（供 controller 读取码等） */
    public Map<String, Object> dispatchOf(String id) {
        return ctx.dispatchOf(id);
    }

    /** 计划调度（等价 createDispatches，事务化两阶段提交） */
    public Map<String, Object> createDispatches(String planId, int count, List<String> vehicleIds) {
        ctx.requireAction("dispatch");
        Map<String, Object> p = ctx.planOf(planId);
        if (p == null) return Map.of("created", List.of(), "error", "计划不存在");
        Map<String, Object> c = ctx.contractOf(str(p, "contractId"));
        if (c != null && "terminated".equals(c.get("status"))) return Map.of("created", List.of(), "error", "合同已终止，不能再下发调度单");
        Double routeDist = ctx.routeDistance(str(p, "loadTerminalId"), str(p, "unloadTerminalId"));
        // F5b 拆车余数修正
        int effCount = Math.max(1, Math.min(count, (int) FlowCtx.num(p.get("quantity"))));
        int per = (int) (FlowCtx.num(p.get("quantity")) / effCount);
        boolean road = ctx.isRoadMode(str(p, "mode"));
        List<Map<String, Object>> created = new ArrayList<>();
        List<Map<String, Object>> dispatches = ctx.store().list("dispatches");
        if (road) {
            Set<String> busyV = dispatches.stream().filter(x -> FlowCtx.BUSY_STATUSES.contains(x.get("status")))
                    .map(x -> String.valueOf(x.get("vehicleId"))).collect(Collectors.toSet());
            Set<String> busyD = dispatches.stream().filter(x -> FlowCtx.BUSY_STATUSES.contains(x.get("status")))
                    .map(x -> String.valueOf(x.get("driverId"))).collect(Collectors.toSet());
            if (vehicleIds != null && !vehicleIds.isEmpty()) {
                List<String> expiredSel = ctx.store().list("vehicles").stream()
                        .filter(v -> vehicleIds.contains(v.get("id")) && ctx.vehicleInspectionExpired(v))
                        .map(v -> String.valueOf(v.get("plate"))).collect(Collectors.toList());
                if (!expiredSel.isEmpty()) return Map.of("created", created, "error", "年检过期车辆不可派车：" + String.join("、", expiredSel));
            }
            List<Map<String, Object>> pool;
            if (vehicleIds != null && !vehicleIds.isEmpty()) {
                pool = ctx.store().list("vehicles").stream()
                        .filter(v -> !"铁路敞车".equals(v.get("type")) && !"散货船".equals(v.get("type"))
                                && vehicleIds.contains(v.get("id")) && !busyV.contains(String.valueOf(v.get("id"))))
                        .collect(Collectors.toList());
            } else {
                pool = ctx.store().list("vehicles").stream()
                        .filter(v -> !"铁路敞车".equals(v.get("type")) && !"散货船".equals(v.get("type"))
                                && "idle".equals(v.get("status")) && !ctx.vehicleInspectionExpired(v)
                                && !busyV.contains(String.valueOf(v.get("id"))))
                        .collect(Collectors.toList());
            }
            List<Map<String, Object>> avail = ctx.store().list("drivers").stream()
                    .filter(x -> "available".equals(x.get("status")) && !ctx.driverLicenseExpired(x)
                            && !busyD.contains(String.valueOf(x.get("id"))))
                    .collect(Collectors.toList());
            if (pool.isEmpty() || avail.isEmpty()) {
                List<String> reasons = new ArrayList<>();
                if (pool.isEmpty()) reasons.add("无可用车辆（须空闲、年检未过期且无未完结车次）");
                if (avail.isEmpty()) reasons.add("无可用司机（须空闲、驾照未过期且无未完结车次）");
                return Map.of("created", created, "error", String.join("；", reasons));
            }
            if (effCount > pool.size() || effCount > avail.size()) {
                List<String> reasons = new ArrayList<>();
                if (effCount > pool.size()) reasons.add("可用车辆不足（需 " + effCount + " 辆，仅 " + pool.size() + " 辆空闲）");
                if (effCount > avail.size()) reasons.add("可用司机不足（需 " + effCount + " 名，仅 " + avail.size() + " 名空闲）");
                return Map.of("created", created, "error", String.join("；", reasons));
            }
            // 两阶段提交：先构建并校验全部，再统一落库
            int pdSeq = 0;
            for (Map<String, Object> x : dispatches) {
                String id = String.valueOf(x.get("id") == null ? "" : x.get("id"));
                if (id.matches("^PD-(\\d+)$")) pdSeq = Math.max(pdSeq, Integer.parseInt(id.substring(3)));
            }
            List<Map<String, Object>> pending = new ArrayList<>();
            for (int i = 0; i < effCount; i++) {
                Map<String, Object> v = pool.get(i);
                Map<String, Object> dr = avail.get(i);
                // 乐观锁：选择时快照版本，提交前二次校验
                if (!validateResourceCommit(v, dr, (int) FlowCtx.num(v.getOrDefault("version", 1)), (int) FlowCtx.num(dr.getOrDefault("version", 1)))) {
                    return Map.of("created", created, "error", "车辆或司机已被其他操作占用（并发冲突），请重新派车");
                }
                String id = "PD-" + String.format("%05d", pdSeq + i + 1);
                double qty = qtyOf(i, effCount, per, p);
                pending.add(buildDispatch(id, p, qty, v.get("id"), dr.get("id"), "多式联运".equals(p.get("mode")) ? ctx.unitNoOf("多式联运", id) : "", routeDist));
            }
            for (int i = pending.size() - 1; i >= 0; i--) dispatches.add(0, pending.get(i));
            created.addAll(pending);
        } else {
            for (int i = 0; i < effCount; i++) {
                String id = ctx.genId("PD-", 5, dispatches);
                double qty = qtyOf(i, effCount, per, p);
                Map<String, Object> d = buildDispatch(id, p, qty, null, null, ctx.unitNoOf(str(p, "mode"), id), routeDist);
                dispatches.add(0, d);
                created.add(d);
            }
        }
        p.put("status", "dispatched");
        ctx.logAction("调度管理", "下发调度单", "计划 " + p.get("id") + " 生成 " + created.size() + " 张调度单（" + p.get("mode") + (road ? "" : "，按运输单元执行") + "）", "success");
        if (!created.isEmpty()) ctx.notify("计划 " + p.get("id") + " 下发 " + created.size() + " 张调度单", "dispatch", "/dispatch", "", ctx.toRoles("dispatch"));
        commit();
        return Map.of("created", created);
    }

    private double qtyOf(int i, int effCount, int per, Map<String, Object> p) {
        return i < effCount - 1 ? per : FlowCtx.num(p.get("quantity")) - per * (effCount - 1);
    }

    private Map<String, Object> buildDispatch(String id, Map<String, Object> p, double qty, Object vehicleId, Object driverId, String unitNo, Double routeDist) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("planId", p.get("id"));
        d.put("contractId", p.get("contractId"));
        d.put("commodityId", p.get("commodityId"));
        d.put("quantity", qty);
        d.put("mode", p.get("mode"));
        d.put("loadTerminalId", p.get("loadTerminalId"));
        d.put("unloadTerminalId", p.get("unloadTerminalId"));
        d.put("vehicleId", vehicleId);
        d.put("driverId", driverId);
        d.put("unitNo", unitNo);
        d.put("distance", routeDist != null ? routeDist : 300);
        d.put("status", "pending");
        d.put("accepted", false);
        d.put("dispatchTime", ctx.now());
        d.put("loadTime", null);
        d.put("unloadTime", null);
        d.put("progress", 0);
        d.put("speed", 0);
        d.put("eta", ctx.nowPlusHours(8));
        d.put("fee", Math.round(qty * FlowCtx.num(p.get("unitPrice"))));
        d.put("unitPrice", p.get("unitPrice"));
        return d;
    }

    /** 乐观锁提交校验（等价 validateResourceCommit） */
    private boolean validateResourceCommit(Map<String, Object> v, Map<String, Object> dr, int seenV, int seenD) {
        if (v == null || dr == null) return false;
        if ((int) FlowCtx.num(v.getOrDefault("version", 1)) != seenV) return false;
        if ((int) FlowCtx.num(dr.getOrDefault("version", 1)) != seenD) return false;
        List<Map<String, Object>> dispatches = ctx.store().list("dispatches");
        if (dispatches.stream().anyMatch(x -> FlowCtx.BUSY_STATUSES.contains(x.get("status")) && v.get("id").equals(x.get("vehicleId")))) return false;
        if (dispatches.stream().anyMatch(x -> FlowCtx.BUSY_STATUSES.contains(x.get("status")) && dr.get("id").equals(x.get("driverId")))) return false;
        return true;
    }

    /** 确认装货核心（等价 doConfirmLoad） */
    private Map<String, Object> doConfirmLoad(Map<String, Object> d) {
        if (!"pending".equals(d.get("status"))) return Map.of("error", "调度单 " + d.get("id") + " 当前非\"待装货\"状态，无法确认装货");
        if (ctx.isRoadMode(str(d, "mode")) && d.get("driverId") != null && !FlowCtx.bool(d.get("accepted"))) {
            return Map.of("error", "司机尚未接单，请先由司机接单后再确认装货");
        }
        String outErr = ctx.warehouseOut(d);
        if (outErr != null) return Map.of("error", outErr);
        d.put("status", "loading");
        d.put("loadTime", ctx.now());
        d.put("progress", 5);
        if (ctx.isRoadMode(str(d, "mode"))) {
            double inNet = FlowCtx.round2(FlowCtx.num(d.get("quantity")) * (1 + ctx.loadVarianceOf(str(d, "id"))));
            ctx.pushWeighing(d, "进磅", inNet, str(d, "loadTime"));
            ctx.logAction("场站管理", "确认装货", "调度单 " + d.get("id") + " 确认装货（进磅 " + inNet + " 吨）", "success");
        } else {
            ctx.logAction("场站管理", "确认装货", "调度单 " + d.get("id") + " 确认装货（" + d.get("mode") + " " + d.get("unitNo") + "，" + d.get("quantity") + " 吨）", "success");
        }
        ctx.rollupPlan(str(d, "planId"));
        return Map.of("ok", true);
    }

    public Map<String, Object> confirmLoad(String dispatchId) {
        ctx.requireAction("dispatch");
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        Map<String, Object> r = doConfirmLoad(d);
        commit();
        return r;
    }

    /** 发车（等价 doDepart） */
    public Map<String, Object> depart(String dispatchId) {
        ctx.requireAction("dispatch");
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        if (!"loading".equals(d.get("status"))) return Map.of("error", "调度单 " + d.get("id") + " 当前非\"装货中\"状态，无法发车");
        d.put("status", "intransit");
        d.put("progress", 10);
        int speed = ctx.randInt(40, 68);
        d.put("speed", speed);
        double hours = FlowCtx.round(FlowCtx.num(d.getOrDefault("distance", 300)) / speed, 1);
        d.put("eta", ctx.nowPlusMinutes((int) Math.round(hours * 60) + 30));
        ctx.occupyResource(d);
        ctx.rollupPlan(str(d, "planId"));
        ctx.logAction("调度管理", "车辆发车", "调度单 " + d.get("id") + " 发车，预计 " + d.get("eta") + " 到达", "success");
        commit();
        return Map.of("ok", true);
    }

    /** 到达（等价 doArrive） */
    public Map<String, Object> arrive(String dispatchId) {
        ctx.requireAction("dispatch");
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        if (!"intransit".equals(d.get("status"))) return Map.of("error", "调度单 " + d.get("id") + " 当前非\"在途\"状态，无法确认到达");
        d.put("status", "unloading");
        d.put("progress", 96);
        d.put("speed", 0);
        d.put("eta", ctx.nowPlusMinutes(ctx.randInt(30, 90)));
        ctx.rollupPlan(str(d, "planId"));
        ctx.logAction("调度管理", "到达卸货场", "调度单 " + d.get("id") + " 到达，开始卸货", "success");
        commit();
        return Map.of("ok", true);
    }

    /** 确认卸货（等价 doConfirmUnload）：completed + 出磅 + 质检 + 入库 + 释放 + 趟次应付 + 回卷 */
    public Map<String, Object> confirmUnload(String dispatchId) {
        ctx.requireAction("dispatch");
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        if (!"unloading".equals(d.get("status"))) return Map.of("error", "调度单 " + d.get("id") + " 当前非\"卸货中\"状态，无法确认卸货");
        d.put("status", "completed");
        d.put("unloadTime", ctx.now());
        d.put("progress", 100);
        d.put("speed", 0);
        double loss = 0;
        double outNet = 0;
        if (ctx.isRoadMode(str(d, "mode"))) {
            Map<String, Object> inW = ctx.store().list("weighings").stream()
                    .filter(w -> d.get("id").equals(w.get("dispatchId")) && "进磅".equals(w.get("type")))
                    .findFirst().orElse(null);
            double inBase = inW != null ? FlowCtx.num(inW.get("net")) : FlowCtx.num(d.get("quantity"));
            loss = FlowCtx.round2(inBase * (ctx.randInt(10, 20) / 1000.0));
            outNet = FlowCtx.round2(inBase - loss);
            ctx.pushWeighing(d, "出磅", outNet, str(d, "unloadTime"));
            Map<String, Object> quality = new LinkedHashMap<>();
            quality.put("moisture", FlowCtx.round(ctx.randInt(80, 140) / 10.0, 1));
            quality.put("ash", FlowCtx.round(ctx.randInt(120, 200) / 10.0, 1));
            quality.put("time", str(d, "unloadTime"));
            d.put("quality", quality);
        }
        ctx.warehouseIn(d);
        ctx.releaseResource(d);
        ctx.doCreateTripPayable(d);
        ctx.rollupPlan(str(d, "planId"));
        ctx.logAction("场站管理", "确认卸货", ctx.isRoadMode(str(d, "mode"))
                ? "调度单 " + d.get("id") + " 确认卸货（出磅 " + outNet + " 吨，损耗 " + loss + " 吨）"
                : "调度单 " + d.get("id") + " 确认卸货（" + d.get("mode") + " " + d.get("unitNo") + "，" + d.get("quantity") + " 吨，无磅单损耗）", "success");
        commit();
        return Map.of("ok", true);
    }

    /** 取消调度单（等价 cancelDispatch） */
    public Map<String, Object> cancelDispatch(String dispatchId, String reason) {
        ctx.requireAction("dispatch");
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        if (!"pending".equals(d.get("status"))) return Map.of("error", "调度单 " + d.get("id") + " 当前非\"待装货\"状态，无法取消（仅装货前可取消）");
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) return Map.of("error", "请填写取消原因");
        d.put("status", "cancelled");
        d.put("cancelReason", r);
        d.put("cancelTime", ctx.now());
        ctx.rollupPlan(str(d, "planId"));
        ctx.logAction("调度管理", "取消调度单", "调度单 " + d.get("id") + " 取消（" + r + "）", "success");
        ctx.notify("调度单 " + d.get("id") + " 已取消", "dispatch", "/dispatch", r, ctx.toRoles("dispatch"));
        commit();
        return Map.of("ok", true);
    }

    /** 改派调度单（等价 reassignDispatch） */
    public Map<String, Object> reassignDispatch(String dispatchId, String vehicleId, String driverId) {
        ctx.requireAction("dispatch");
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        if (!"pending".equals(d.get("status"))) return Map.of("error", "调度单 " + d.get("id") + " 当前非\"待装货\"状态，无法改派（仅装货前可改派）");
        if (!ctx.isRoadMode(str(d, "mode"))) return Map.of("error", d.get("mode") + " 车次按运输单元执行，无车辆/司机可改派");
        Map<String, Object> v = ctx.byId("vehicles", vehicleId);
        Map<String, Object> dr = ctx.byId("drivers", driverId);
        if (v == null) return Map.of("error", "目标车辆不存在");
        if (dr == null) return Map.of("error", "目标司机不存在");
        if (ctx.vehicleInspectionExpired(v)) return Map.of("error", "车辆 " + v.get("plate") + " 年检过期，不可改派");
        if (ctx.driverLicenseExpired(dr)) return Map.of("error", "司机 " + dr.get("name") + " 驾照过期，不可改派");
        boolean busyV = ctx.store().list("dispatches").stream().anyMatch(x -> !x.get("id").equals(d.get("id"))
                && FlowCtx.BUSY_STATUSES.contains(x.get("status")) && v.get("id").equals(x.get("vehicleId")));
        boolean busyD = ctx.store().list("dispatches").stream().anyMatch(x -> !x.get("id").equals(d.get("id"))
                && FlowCtx.BUSY_STATUSES.contains(x.get("status")) && dr.get("id").equals(x.get("driverId")));
        if (!"idle".equals(v.get("status")) || busyV) return Map.of("error", "车辆 " + v.get("plate") + " 当前不可用（非空闲或有未完结车次）");
        if (!"available".equals(dr.get("status")) || busyD) return Map.of("error", "司机 " + dr.get("name") + " 当前不可用（非空闲或有未完结车次）");
        d.put("vehicleId", v.get("id"));
        d.put("driverId", dr.get("id"));
        d.put("accepted", false);
        ctx.logAction("调度管理", "改派调度单", "调度单 " + d.get("id") + " 改派：车辆 → " + v.get("plate") + "，司机 → " + dr.get("name"), "success");
        ctx.notify("调度单 " + d.get("id") + " 已改派", "dispatch", "/dispatch", "车辆 " + v.get("plate") + " / 司机 " + dr.get("name"), ctx.toRoles("dispatch"));
        commit();
        return Map.of("ok", true);
    }

    /** 上报异常（等价 reportException → createException） */
    public Map<String, Object> reportException(String dispatchId, String description, String type, String level) {
        ctx.requireAction("exception");
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        if (!List.of("pending", "loading", "intransit", "unloading").contains(d.get("status"))) {
            return Map.of("error", "调度单 " + d.get("id") + " 当前非执行中状态，无法上报异常");
        }
        d.put("exceptionFrom", d.get("status"));
        d.put("status", "exception");
        d.put("speed", 0);
        List<Map<String, Object>> exceptions = ctx.store().list("exceptions");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", ctx.genId("YC-", 4, exceptions));
        e.put("dispatchId", d.get("id"));
        e.put("type", type);
        e.put("level", level);
        e.put("status", "pending");
        e.put("occurTime", ctx.now());
        e.put("handler", "");
        e.put("description", description);
        e.put("result", "");
        e.put("cost", 0);
        e.put("source", "");
        exceptions.add(0, e);
        if ("accident".equals(type)) {
            Map<String, Object> v = ctx.vehicleOf(str(d, "vehicleId"));
            List<Map<String, Object>> accidents = ctx.store().list("accidents");
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", ctx.genId("SG-", 3, accidents));
            a.put("time", ctx.today());
            a.put("type", "碰撞");
            a.put("level", "high".equals(level) ? "重大" : "medium".equals(level) ? "较大" : "一般");
            a.put("vehicleId", d.get("vehicleId"));
            a.put("plate", v != null ? v.get("plate") : "-");
            a.put("location", "调度单 " + d.get("id") + " 在途");
            a.put("description", description);
            a.put("handling", "处置中");
            a.put("loss", 0);
            a.put("status", "handling");
            a.put("exceptionId", e.get("id"));
            accidents.add(0, a);
            e.put("accidentId", a.get("id"));
        }
        ctx.rollupPlan(str(d, "planId"));
        ctx.logAction("异常处理", "上报异常", "调度单 " + d.get("id") + " 上报异常（" + type + "），生成异常单 " + e.get("id") + ("accident".equals(type) ? " 及事故记录 " + e.get("accidentId") : ""), "success");
        ctx.notify("调度单 " + d.get("id") + " 上报异常", "exception", "/exception", description, ctx.toRoles("exception"));
        commit();
        return Map.of("ok", true, "exception", e);
    }

    /** 恢复运输（等价 resumeDispatch） */
    public Map<String, Object> resumeDispatch(String dispatchId) {
        ctx.requireAction("dispatch");
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        if (!"exception".equals(d.get("status"))) return Map.of("error", "调度单 " + d.get("id") + " 当前非\"异常\"状态，无法恢复运输");
        Object fromObj = d.get("exceptionFrom");
        String from = fromObj != null ? String.valueOf(fromObj) : null;
        if ("unloading".equals(from)) {
            d.put("status", "unloading");
            d.put("progress", 96);
            d.put("speed", 0);
            d.put("eta", ctx.nowPlusMinutes(ctx.randInt(30, 90)));
        } else if ("intransit".equals(from) || (from == null && d.get("loadTime") != null)) {
            d.put("status", "intransit");
            d.put("progress", Math.max(10, Math.min(FlowCtx.intNum(d.getOrDefault("progress", 10)), 90)));
            d.put("speed", ctx.randInt(40, 68));
            d.put("eta", ctx.nowPlusHours(4));
        } else {
            d.put("status", "loading");
            d.put("progress", 5);
        }
        d.put("exceptionFrom", null);
        ctx.occupyResource(d);
        ctx.rollupPlan(str(d, "planId"));
        ctx.logAction("异常处理", "恢复运输", "调度单 " + d.get("id") + " 恢复运输（" + ("intransit".equals(d.get("status")) ? "在途" : "unloading".equals(d.get("status")) ? "卸货" : "装货") + "）", "success");
        commit();
        return Map.of("ok", true);
    }

    /** 司机端身份守卫（等价 requireDriverApp，M6） */
    public String requireDriverApp(Map<String, Object> d) {
        if ("司机".equals(ctx.op().getRole())) {
            if (ctx.op().getDriverId() == null || ctx.op().getDriverId().isBlank() || !ctx.op().getDriverId().equals(d.get("driverId"))) {
                return "司机账号只能操作指派给本人的车次（调度单 " + d.get("id") + " 未指派给当前司机）";
            }
            return null;
        }
        if (ctx.can("dispatch")) return null;
        return "当前角色「" + (ctx.op().getRole().isBlank() ? "未登录" : ctx.op().getRole()) + "」非司机端身份，无此操作权限，操作已被服务层拦截";
    }

    /** 司机接单（等价 acceptDispatch，M6） */
    public Map<String, Object> acceptDispatch(String dispatchId) {
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        String guardErr = requireDriverApp(d);
        if (guardErr != null) return Map.of("error", guardErr);
        d.put("accepted", true);
        Map<String, Object> dr = ctx.driverOf(str(d, "driverId"));
        ctx.logAction("司机端", "司机接单", "调度单 " + d.get("id") + " 司机 " + (dr != null ? dr.get("name") : "-") + " 接单", "success");
        ctx.notify("司机接单提醒", "dispatch", "/dispatch", "调度单 " + d.get("id") + " 司机 " + (dr != null ? dr.get("name") : "-") + " 已接单", ctx.toRoles("dispatch"));
        commit();
        return Map.of("ok", true);
    }

    /** 司机端发车（等价 driverDepart，走 doDepart 同核心） */
    public Map<String, Object> driverDepart(String dispatchId) {
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        String guardErr = requireDriverApp(d);
        if (guardErr != null) return Map.of("error", guardErr);
        Map<String, Object> r = depart(dispatchId);
        if (r.containsKey("error")) return r;
        Map<String, Object> dr = ctx.driverOf(str(d, "driverId"));
        ctx.logAction("司机端", "车辆发车", "司机 " + (dr != null ? dr.get("name") : "-") + " 确认调度单 " + d.get("id") + " 发车", "success");
        return Map.of("ok", true);
    }

    /** 司机端确认到达（等价 driverArrive） */
    public Map<String, Object> driverArrive(String dispatchId) {
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        String guardErr = requireDriverApp(d);
        if (guardErr != null) return Map.of("error", guardErr);
        Map<String, Object> r = arrive(dispatchId);
        if (r.containsKey("error")) return r;
        Map<String, Object> dr = ctx.driverOf(str(d, "driverId"));
        ctx.logAction("司机端", "确认到达", "司机 " + (dr != null ? dr.get("name") : "-") + " 确认调度单 " + d.get("id") + " 到达卸货场站", "success");
        return Map.of("ok", true);
    }

    /** 司机端电子签收（等价 signReceipt，M6） */
    public Map<String, Object> signReceipt(String dispatchId, String signer) {
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        String guardErr = requireDriverApp(d);
        if (guardErr != null) return Map.of("error", guardErr);
        if (!"completed".equals(d.get("status"))) return Map.of("error", "调度单 " + d.get("id") + " 尚未卸货完成，签收单只能在卸货完成后生成");
        if (d.get("receipt") != null) return Map.of("error", "调度单 " + d.get("id") + " 已存在电子签收单，不可重复签收");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("code", "QS-" + str(d, "id").substring(Math.max(0, str(d, "id").length() - 5)));
        receipt.put("signer", signer == null || signer.isBlank() ? "收货方" : signer);
        receipt.put("time", ctx.now());
        d.put("receipt", receipt);
        ctx.logAction("司机端", "电子签收", "调度单 " + d.get("id") + " 电子签收，签收人 " + receipt.get("signer") + "（" + receipt.get("code") + "）", "success");
        commit();
        return Map.of("ok", true, "receipt", receipt);
    }

    /** 补签（等价 supplementReceipt，环节1） */
    public Map<String, Object> supplementReceipt(String dispatchId, String signer, String reason) {
        ctx.requireAction("dispatch");
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        if (!ctx.isRoadMode(str(d, "mode"))) return Map.of("error", d.get("mode") + " 车次按运输单元执行，无签收凭证，无需补签（非公路豁免）");
        if (!"completed".equals(d.get("status"))) return Map.of("error", "调度单 " + d.get("id") + " 尚未完成，仅已完成车次可补签");
        if (d.get("receipt") != null) return Map.of("error", "调度单 " + d.get("id") + " 已存在电子签收单，无需补签");
        if (signer == null || signer.trim().isEmpty()) return Map.of("error", "请填写签收人");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("code", "QS-B" + str(d, "id").substring(Math.max(0, str(d, "id").length() - 5)));
        receipt.put("signer", signer.trim());
        receipt.put("time", ctx.now());
        receipt.put("supplement", true);
        receipt.put("reason", reason == null ? "" : reason.trim());
        d.put("receipt", receipt);
        ctx.logAction("调度管理", "补签签收单", "调度单 " + d.get("id") + " 补签电子签收，签收人 " + receipt.get("signer") + (receipt.get("reason").toString().isEmpty() ? "" : "（原因：" + receipt.get("reason") + "）"), "success");
        ctx.notify("调度单 " + d.get("id") + " 已补签", "settlement", "/settlement", "签收人 " + receipt.get("signer"), ctx.toRoles("settlement"));
        // 已入账单且有对账结果 → 重建对账清除"未签收"标记（结算域 buildReconciliation，阶段 3 结算部分）
        Map<String, Object> s = d.get("settlementId") != null ? ctx.byId("settlements", str(d, "settlementId")) : null;
        if (s != null && s.get("reconciliation") != null) {
            settlementService.buildReconciliation(s, null);
        }
        commit();
        return Map.of("ok", true, "receipt", receipt);
    }

    /** 扫码确认装货（等价 scanConfirmLoad） */
    public Map<String, Object> scanConfirmLoad(String dispatchId, String code) {
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        String guardErr = requireDriverApp(d);
        if (guardErr != null) return Map.of("error", guardErr);
        String expect = loadCodeOf(d);
        if (String.valueOf(code == null ? "" : code).trim().equals(expect)) {
            Map<String, Object> r = confirmLoad(dispatchId);
            if (r.containsKey("error")) return r;
            ctx.logAction("司机端", "扫码确认装货", "调度单 " + d.get("id") + " 扫装货码 " + expect + " 核验通过，确认装货", "success");
            return Map.of("ok", true);
        }
        return Map.of("error", "装货码校验失败：「" + (code == null ? "空" : code) + "」与本车次装货码 " + expect + " 不符");
    }

    /** 扫码确认卸货（等价 scanConfirmUnload） */
    public Map<String, Object> scanConfirmUnload(String dispatchId, String code) {
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return Map.of("error", "调度单不存在");
        String guardErr = requireDriverApp(d);
        if (guardErr != null) return Map.of("error", guardErr);
        String expect = unloadCodeOf(d);
        if (String.valueOf(code == null ? "" : code).trim().equals(expect)) {
            Map<String, Object> r = confirmUnload(dispatchId);
            if (r.containsKey("error")) return r;
            ctx.logAction("司机端", "扫码确认卸货", "调度单 " + d.get("id") + " 扫卸货码 " + expect + " 核验通过，确认卸货", "success");
            return Map.of("ok", true);
        }
        return Map.of("error", "卸货码校验失败：「" + (code == null ? "空" : code) + "」与本车次卸货码 " + expect + " 不符");
    }

    /** 装货码（确定性派生，ZD + 6 位） */
    public String loadCodeOf(Map<String, Object> d) {
        return "ZD" + (100000 + (hashStr(str(d, "id") + ":load") % 900000));
    }

    /** 卸货码（确定性派生，XD + 6 位） */
    public String unloadCodeOf(Map<String, Object> d) {
        return "XD" + (100000 + (hashStr(str(d, "id") + ":unload") % 900000));
    }

    private static int hashStr(String s) {
        int n = 0;
        for (char ch : String.valueOf(s).toCharArray()) n = (n * 31 + (int) ch) % 2147483647;
        return n;
    }

    private void commit() {
        ctx.store().commitAll();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
