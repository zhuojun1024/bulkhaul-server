package com.blms.service.dispatch;

import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 调度详情聚合（Phase 4 阶段 1：详情聚合端点，Java 内存组装，非 SQL join）。
 * 与前端 dispatch/detail.vue 的读取面 1:1：
 *   dispatch + commodity + vehicle + driver + terminals(load/unload) + weighings +
 *   contract + plan + settlements(经 settlementId) + exceptions(dispatchId) +
 *   派生值 settleQty/qualityDeduction（口径与 FlowCtx 同，前端 qualityDeductionQty/settleQtyOf 一致）。
 */
@Service
public class DispatchDetailService {

    private final FlowCtx ctx;

    public DispatchDetailService(FlowCtx ctx) {
        this.ctx = ctx;
    }

    /** 聚合调度详情；调度单不存在返回 null（controller 映射 404） */
    public Map<String, Object> detail(String dispatchId) {
        Map<String, Object> d = ctx.dispatchOf(dispatchId);
        if (d == null) return null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dispatch", d);
        r.put("commodity", ctx.byId("commodities", str(d, "commodityId")));
        r.put("vehicle", ctx.vehicleOf(str(d, "vehicleId")));
        r.put("driver", ctx.driverOf(str(d, "driverId")));
        r.put("loadTerminal", ctx.byId("terminals", str(d, "loadTerminalId")));
        r.put("unloadTerminal", ctx.byId("terminals", str(d, "unloadTerminalId")));
        r.put("weighings", ctx.store().list("weighings").stream()
                .filter(w -> dispatchId.equals(w.get("dispatchId")))
                .toList());
        r.put("contract", ctx.contractOf(str(d, "contractId")));
        r.put("plan", ctx.planOf(str(d, "planId")));
        r.put("settlements", ctx.store().list("settlements").stream()
                .filter(s -> dispatchId.equals(s.get("id")) || dispatchId.equals(s.get("settlementId")))
                .toList());
        r.put("exceptions", ctx.store().list("exceptions").stream()
                .filter(e -> dispatchId.equals(e.get("dispatchId")))
                .toList());
        // 派生值（前端 detail.vue 展示口径：settleQtyOf/qualityDeductionQty 1:1）
        r.put("settleQty", ctx.settleQtyOf(d));
        r.put("qualityDeduction", ctx.qualityDeductionQty(d));
        return r;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
