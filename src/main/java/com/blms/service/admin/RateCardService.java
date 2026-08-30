package com.blms.service.admin;

import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 运价卡管理（与前端 flow.js 运价域 1:1）。
 * createRateCard / updateRateCard（调价留痕 history）/ toggleRateCard / rateOf（线路运价查询）。
 * 权限码：rate。
 */
@Service
public class RateCardService {

    private final FlowCtx ctx;

    public RateCardService(FlowCtx ctx) {
        this.ctx = ctx;
    }

    private void commit() { ctx.store().commitAll(); }

    public Map<String, Object> createRateCard(Map<String, Object> p) {
        ctx.requireAction("rate");
        if (p.get("commodityId") == null || String.valueOf(p.get("commodityId")).isBlank()) return err("请选择商品");
        String load = str(p, "loadTerminalId"), unload = str(p, "unloadTerminalId");
        if (load.isBlank() || unload.isBlank()) return err("请选择装/卸货场站");
        if (load.equals(unload)) return err("装货场站与卸货场站不能相同");
        double unitPrice = num(p.get("unitPrice"));
        if (unitPrice <= 0) return err("运价须大于 0");
        List<Map<String, Object>> cards = ctx.store().list("rateCards");
        boolean dup = cards.stream().anyMatch(r -> "active".equals(r.get("status"))
                && p.get("commodityId").equals(r.get("commodityId"))
                && load.equals(r.get("loadTerminalId")) && unload.equals(r.get("unloadTerminalId"))
                && modeOf(r).equals(modeOf(p)));
        if (dup) {
            Map<String, Object> d = cards.stream().filter(r -> "active".equals(r.get("status"))
                    && p.get("commodityId").equals(r.get("commodityId"))
                    && load.equals(r.get("loadTerminalId")) && unload.equals(r.get("unloadTerminalId"))
                    && modeOf(r).equals(modeOf(p))).findFirst().orElse(null);
            return err("该线路已有启用中的运价卡 " + (d != null ? d.get("id") : "") + "，请勿重复创建（如需调价请编辑原卡）");
        }
        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("id", ctx.genId("YJ-", 3, cards));
        rc.put("commodityId", p.get("commodityId"));
        rc.put("loadTerminalId", load);
        rc.put("unloadTerminalId", unload);
        rc.put("mode", modeOf(p));
        rc.put("unitPrice", unitPrice);
        rc.put("effectiveDate", p.get("effectiveDate") != null ? p.get("effectiveDate") : ctx.today());
        rc.put("status", "active");
        rc.put("remark", p.get("remark") != null ? str(p, "remark") : "");
        rc.put("history", new ArrayList<>());
        cards.add(0, rc);
        ctx.logAction("运价管理", "新建运价卡", "运价卡 " + rc.get("id") + " 创建（" + rc.get("commodityId") + " " + load + "→" + unload + "，" + unitPrice + " 元/吨）", "success");
        commit();
        return Map.of("ok", true, "id", rc.get("id"), "card", rc);
    }

    public Map<String, Object> updateRateCard(String id, Map<String, Object> fields) {
        ctx.requireAction("rate");
        Map<String, Object> rc = ctx.byId("rateCards", id);
        if (rc == null) return err("运价卡 " + id + " 不存在");
        List<String> changes = new ArrayList<>();
        if (fields.get("unitPrice") != null && num(fields.get("unitPrice")) != num(rc.get("unitPrice"))) {
            if (num(fields.get("unitPrice")) <= 0) return err("运价须大于 0");
            changes.add("单价 " + rc.get("unitPrice") + "→" + num(fields.get("unitPrice")) + " 元/吨");
            rc.put("unitPrice", num(fields.get("unitPrice")));
        }
        if (fields.get("effectiveDate") != null && !String.valueOf(fields.get("effectiveDate")).equals(String.valueOf(rc.get("effectiveDate")))) {
            changes.add("生效日期 " + rc.get("effectiveDate") + "→" + fields.get("effectiveDate"));
            rc.put("effectiveDate", fields.get("effectiveDate"));
        }
        if (fields.get("remark") != null && !String.valueOf(fields.get("remark")).equals(String.valueOf(rc.get("remark")))) {
            rc.put("remark", fields.get("remark"));
        }
        if (changes.isEmpty()) return Map.of("changed", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = rc.get("history") instanceof List<?> l ? (List<Map<String, Object>>) l : new ArrayList<>();
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("time", ctx.now());
        h.put("operator", ctx.op().getName());
        h.put("changes", String.join("；", changes));
        history.add(h);
        rc.put("history", history);
        ctx.logAction("运价管理", "运价调整", "运价卡 " + rc.get("id") + " 调价：" + String.join("；", changes), "success");
        ctx.notify("运价卡 " + rc.get("id") + " 已调价", "system", "/contract",
                String.join("；", changes) + "（仅影响后续新签合同，已派车批次不追溯）", ctx.toRoles("rate", "contract"));
        commit();
        return Map.of("changed", true, "changes", changes);
    }

    public Map<String, Object> toggleRateCard(String id) {
        ctx.requireAction("rate");
        Map<String, Object> rc = ctx.byId("rateCards", id);
        if (rc == null) return err("运价卡 " + id + " 不存在");
        rc.put("status", "active".equals(rc.get("status")) ? "inactive" : "active");
        ctx.logAction("运价管理", "active".equals(rc.get("status")) ? "启用运价卡" : "停用运价卡",
                "运价卡 " + rc.get("id") + (rc.get("status").equals("active") ? " 启用" : " 停用"), "success");
        commit();
        return Map.of("ok", true, "status", rc.get("status"));
    }

    public Map<String, Object> rateOf(String commodityId, String loadTerminalId, String unloadTerminalId, String mode) {
        return ctx.store().list("rateCards").stream()
                .filter(r -> "active".equals(r.get("status"))
                        && commodityId.equals(r.get("commodityId"))
                        && loadTerminalId.equals(r.get("loadTerminalId"))
                        && unloadTerminalId.equals(r.get("unloadTerminalId"))
                        && modeOf(r).equals(mode == null ? "公路" : mode))
                .findFirst().orElse(null);
    }

    private static String modeOf(Map<String, Object> m) {
        Object v = m.get("mode");
        return v == null || String.valueOf(v).isBlank() ? "公路" : String.valueOf(v);
    }
    private static Map<String, Object> err(String e) { return Map.of("error", e); }
    private static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? "" : String.valueOf(v); }
    private static double num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}
