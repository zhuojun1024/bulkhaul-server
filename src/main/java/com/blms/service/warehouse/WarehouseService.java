package com.blms.service.warehouse;

import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 仓储服务（与 flow.js 仓储域 1:1）。
 * manualInbound（手工入库）/ safetyStockOf / availableStockOf / inventoryAlerts。
 */
@Service
public class WarehouseService {

    private static final Pattern M_BATCH = Pattern.compile("-M\\d+$");
    private final FlowCtx ctx;

    public WarehouseService(FlowCtx ctx) {
        this.ctx = ctx;
    }

    private void commit() { ctx.store().commitAll(); }

    /** 手工入库（等价 manualInbound） */
    public Map<String, Object> manualInbound(String warehouseId, String commodityId, double quantity, String batch, String remark) {
        ctx.requireAction("warehouse");
        Map<String, Object> wh = ctx.byId("warehouses", warehouseId);
        if (wh == null) return Map.of("error", "请选择仓库");
        if (!"operating".equals(wh.get("status"))) return Map.of("error", "仓库 " + wh.get("name") + " 非运营中状态，不可入库");
        Map<String, Object> cm = ctx.byId("commodities", commodityId);
        if (cm == null) return Map.of("error", "请选择商品");
        double qty = quantity;
        if (Double.isNaN(qty) || Double.isInfinite(qty) || qty <= 0) return Map.of("error", "入库数量须为大于 0 的数值");
        double fixed = FlowCtx.round2(qty);
        double used = FlowCtx.num(wh.get("used"));
        double capacity = FlowCtx.num(wh.get("capacity"));
        if (used + fixed > capacity) {
            return Map.of("error", "入库后超仓库容量：" + wh.get("name") + " 当前占用 " + used + " 吨，容量 " + capacity + " 吨，本次 " + fixed + " 吨");
        }
        List<Map<String, Object>> inventories = ctx.store().list("inventories");
        int mSeq = (int) inventories.stream().filter(x -> M_BATCH.matcher(String.valueOf(x.get("batch"))).find()).count() + 1;
        String b = batch == null ? "" : batch.trim();
        String batchNo = b.isEmpty() ? "B" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")) + "-M" + String.format("%03d", mSeq) : b;
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("id", ctx.genId("INV-", 4, inventories));
        inv.put("warehouseId", wh.get("id"));
        inv.put("commodityId", cm.get("id"));
        inv.put("batch", batchNo);
        inv.put("quantity", fixed);
        inv.put("inDate", ctx.today());
        inv.put("status", "normal");
        inv.put("source", "manual");
        inventories.add(0, inv);
        wh.put("used", FlowCtx.round2(used + fixed));
        ctx.logAction("仓储管理", "手工入库", wh.get("name") + " " + cm.get("name") + " 手工入库 " + fixed + " 吨（批次 " + batchNo + "）" + (remark != null && !remark.trim().isEmpty() ? "，" + remark.trim() : ""), "success");
        ctx.notify("手工入库 " + fixed + " 吨", "system", "/warehouse/inventory", wh.get("name") + " " + cm.get("name") + " 批次 " + batchNo, ctx.toRoles("warehouse"));
        commit();
        return Map.of("ok", true, "id", inv.get("id"), "batch", batchNo);
    }

    /** 设置安全库存下限（等价 setSafetyStock） */
    public Map<String, Object> setSafetyStock(String warehouseId, String commodityId, double minQty) {
        ctx.requireAction("warehouse");
        Map<String, Object> wh = ctx.byId("warehouses", warehouseId);
        if (wh == null) return Map.of("error", "仓库不存在");
        Map<String, Object> cm = ctx.byId("commodities", commodityId);
        if (cm == null) return Map.of("error", "商品不存在");
        double qty = minQty;
        if (Double.isNaN(qty) || qty < 0) return Map.of("error", "安全库存下限须为不小于 0 的数字");
        List<Map<String, Object>> sqs = ctx.store().list("safetyStocks");
        Map<String, Object> sq = sqs.stream()
                .filter(x -> warehouseId.equals(x.get("warehouseId")) && commodityId.equals(x.get("commodityId")))
                .findFirst().orElse(null);
        if (sq != null) {
            sq.put("minQty", Math.round(qty));
        } else {
            sq = new LinkedHashMap<>();
            sq.put("id", ctx.genId("SQ-", 4, sqs));
            sq.put("warehouseId", warehouseId);
            sq.put("commodityId", commodityId);
            sq.put("minQty", Math.round(qty));
            sqs.add(sq);
        }
        ctx.logAction("仓储管理", "设置安全库存", wh.get("name") + " " + cm.get("name") + " 安全库存下限设为 " + sq.get("minQty") + " 吨", "success");
        commit();
        return Map.of("ok", true, "id", sq.get("id"));
    }

    /** 库存批次状态变更（等价 setInventoryStatus）：locked/normal/near-expiry */
    public Map<String, Object> setInventoryStatus(String invId, String status) {
        ctx.requireAction("warehouse");
        if (status == null || !List.of("locked", "normal", "near-expiry").contains(status)) return Map.of("error", "无效的库存状态");
        Map<String, Object> inv = ctx.byId("inventories", invId);
        if (inv == null) return Map.of("error", "库存批次不存在");
        if (status.equals(inv.get("status"))) return Map.of("error", "批次 " + inv.get("batch") + " 已处于该状态，无需重复操作");
        Map<String, Object> wh = ctx.byId("warehouses", String.valueOf(inv.get("warehouseId")));
        double beforeAvail = availableStockOf(String.valueOf(inv.get("warehouseId")), String.valueOf(inv.get("commodityId")));
        inv.put("status", status);
        String label = switch (status) { case "locked" -> "库存锁定"; case "normal" -> "库存解锁"; default -> "标记临期"; };
        ctx.logAction("仓储管理", label, "批次 " + inv.get("batch") + " " + label + " " + inv.get("quantity") + " 吨", "success");
        if (wh != null && !"normal".equals(status)) ctx.checkInventoryAlert(wh, String.valueOf(inv.get("commodityId")), beforeAvail);
        commit();
        return Map.of("ok", true);
    }

    /** 安全库存记录（等价 safetyStockOf） */
    public Map<String, Object> safetyStockOf(String warehouseId, String commodityId) {
        return ctx.store().list("safetyStocks").stream()
                .filter(x -> warehouseId.equals(x.get("warehouseId")) && commodityId.equals(x.get("commodityId")))
                .findFirst().orElse(null);
    }

    /** 可发库存（等价 availableStockOf） */
    public double availableStockOf(String warehouseId, String commodityId) {
        return ctx.availableStockOf(warehouseId, commodityId);
    }

    /** 安全库存预警列表（等价 inventoryAlerts） */
    public List<Map<String, Object>> inventoryAlerts() {
        List<Map<String, Object>> res = new ArrayList<>();
        for (Map<String, Object> sq : ctx.store().list("safetyStocks")) {
            double available = availableStockOf(str(sq, "warehouseId"), str(sq, "commodityId"));
            if (available < FlowCtx.num(sq.get("minQty"))) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("warehouseId", sq.get("warehouseId"));
                r.put("commodityId", sq.get("commodityId"));
                r.put("available", available);
                r.put("minQty", FlowCtx.num(sq.get("minQty")));
                r.put("gap", FlowCtx.round2(FlowCtx.num(sq.get("minQty")) - available));
                res.add(r);
            }
        }
        return res;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
