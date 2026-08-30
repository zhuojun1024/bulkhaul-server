package com.blms.service.safety;

import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全服务（与 flow.js 安全域 1:1）。
 * registerAccident / closeAccident / addTraining / completeTraining / addInspection。
 */
@Service
public class SafetyService {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final FlowCtx ctx;

    public SafetyService(FlowCtx ctx) {
        this.ctx = ctx;
    }

    private void commit() { ctx.store().commitAll(); }

    /** 事故登记（等价 registerAccident） */
    public Map<String, Object> registerAccident(Map<String, Object> payload) {
        ctx.requireAction("safety");
        Map<String, Object> v = payload.get("vehicleId") != null && !String.valueOf(payload.get("vehicleId")).isBlank()
                ? ctx.vehicleOf(String.valueOf(payload.get("vehicleId"))) : null;
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", ctx.genId("SG-", 3, ctx.store().list("accidents")));
        a.put("time", payload.get("time"));
        a.put("type", payload.get("type"));
        a.put("level", payload.get("level"));
        a.put("vehicleId", v != null ? v.get("id") : "");
        a.put("plate", v != null ? v.get("plate") : (payload.get("plate") == null || String.valueOf(payload.get("plate")).isBlank() ? "-" : payload.get("plate")));
        a.put("location", payload.get("location") == null ? "" : payload.get("location"));
        a.put("description", payload.get("description") == null ? "" : payload.get("description"));
        a.put("handling", payload.get("handling") == null ? "" : payload.get("handling"));
        a.put("loss", payload.get("loss") == null ? 0 : FlowCtx.num(payload.get("loss")));
        a.put("status", payload.get("status") == null || String.valueOf(payload.get("status")).isBlank() ? "handling" : payload.get("status"));
        ctx.store().list("accidents").add(0, a);
        ctx.logAction("安全管理", "事故登记", "事故 " + a.get("id") + " 登记（" + a.get("level") + "·" + a.get("type") + "，车辆 " + a.get("plate") + "，损失 " + FlowCtx.formatMoney(FlowCtx.num(a.get("loss"))) + "）", "success");
        commit();
        return a;
    }

    /** 事故结案（等价 closeAccident）：handling → closed */
    public Map<String, Object> closeAccident(String accidentId) {
        ctx.requireAction("safety");
        Map<String, Object> a = ctx.byId("accidents", accidentId);
        if (a == null) return Map.of("error", "事故不存在");
        if (!"handling".equals(a.get("status"))) return Map.of("error", "事故 " + a.get("id") + " 当前非\"处理中\"状态，无法结案");
        a.put("status", "closed");
        if (a.get("handling") == null || String.valueOf(a.get("handling")).isBlank()) a.put("handling", "已结案");
        ctx.logAction("安全管理", "事故结案", "事故 " + a.get("id") + " 结案", "success");
        commit();
        return Map.of("ok", true);
    }

    /** 培训计划（等价 addTraining） */
    public Map<String, Object> addTraining(Map<String, Object> payload) {
        ctx.requireAction("safety");
        String title = payload.get("title") == null ? "" : String.valueOf(payload.get("title")).trim();
        String date = payload.get("date") == null ? "" : String.valueOf(payload.get("date")).trim();
        if (title.isEmpty()) return Map.of("error", "请填写培训主题");
        if (date.isEmpty()) return Map.of("error", "请选择培训日期");
        if (date.compareTo(ctx.today()) < 0) return Map.of("error", "培训日期不能早于今天");
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", ctx.genId("PX-", 3, ctx.store().list("trainings")));
        t.put("title", title);
        t.put("date", date);
        t.put("trainer", payload.get("trainer") == null ? "" : payload.get("trainer"));
        t.put("participants", 0);
        t.put("driverIds", new java.util.ArrayList<>());
        t.put("status", "scheduled");
        ctx.store().list("trainings").add(0, t);
        ctx.logAction("安全管理", "培训计划", "培训 " + t.get("id") + " 计划：" + t.get("title") + "（" + t.get("date") + "）", "success");
        commit();
        return t;
    }

    /** 培训完成（等价 completeTraining）：scheduled → completed */
    public Map<String, Object> completeTraining(String trainingId, List<String> driverIds) {
        ctx.requireAction("safety");
        Map<String, Object> t = ctx.byId("trainings", trainingId);
        if (t == null) return Map.of("error", "培训不存在");
        if (!"scheduled".equals(t.get("status"))) return Map.of("error", "培训 " + t.get("id") + " 当前非\"计划中\"状态，无法标记完成");
        String date = String.valueOf(t.get("date"));
        if (date.compareTo(ctx.today()) > 0) return Map.of("error", "培训 " + t.get("id") + " 日期（" + date + "）未到，无法标记完成");
        t.put("status", "completed");
        t.put("driverIds", driverIds == null ? new java.util.ArrayList<>() : driverIds);
        t.put("participants", driverIds == null ? 0 : driverIds.size());
        ctx.logAction("安全管理", "培训完成", "培训 " + t.get("id") + " 完成，" + t.get("participants") + " 名司机参训", "success");
        commit();
        return Map.of("ok", true);
    }

    /** 车辆检查登记（等价 addInspection） */
    public Map<String, Object> addInspection(Map<String, Object> payload) {
        ctx.requireAction("safety");
        Map<String, Object> v = payload.get("vehicleId") != null && !String.valueOf(payload.get("vehicleId")).isBlank()
                ? ctx.vehicleOf(String.valueOf(payload.get("vehicleId"))) : null;
        if (v == null) return Map.of("error", "请选择被检车辆");
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("id", ctx.genId("JC-", 3, ctx.store().list("inspections")));
        i.put("vehicleId", v.get("id"));
        i.put("plate", v.get("plate"));
        i.put("date", payload.get("date"));
        i.put("item", payload.get("item") == null ? "" : payload.get("item"));
        i.put("result", "fail".equals(payload.get("result")) ? "fail" : "pass");
        i.put("inspector", payload.get("inspector") == null ? "" : payload.get("inspector"));
        i.put("remark", payload.get("remark") == null ? "" : payload.get("remark"));
        ctx.store().list("inspections").add(0, i);
        ctx.logAction("安全管理", "检查登记", "车辆 " + v.get("plate") + " 检查（" + i.get("item") + "）：" + ("pass".equals(i.get("result")) ? "合格" : "不合格"), "success");
        commit();
        return i;
    }
}
