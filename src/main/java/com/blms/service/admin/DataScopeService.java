package com.blms.service.admin;

import com.blms.store.FlowCtx;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行级数据范围（服务端强制，等价前端 flow.js inDataScope/recordRegion/visibleDispatches）。
 * 口径：regions 为空 = 全量数据（平台管理员/只读/客户/司机）；非空 = 按装货侧区域过滤。
 * 适用集合：dispatches/plans/contracts/transportRequests（直接 loadTerminalId）、
 * settlements/weighings（经合同/调度派生）。
 * 说明：customers.region 为省份（山西/陕西），非数据区域（华北/西北），不参与行级过滤；
 * terminals 为区域来源本身、logs/messages 为菜单/角色门控（非区域维度），均不过滤。
 */
@Service
public class DataScopeService {

    /** 参与行级区域过滤的集合（与前端 visible* 口径一致） */
    public static final Set<String> REGION_SCOPED = Set.of(
            "dispatches", "plans", "contracts", "transportRequests", "settlements", "weighings");

    private final FlowCtx ctx;

    public DataScopeService(FlowCtx ctx) {
        this.ctx = ctx;
    }

    /** 当前操作人数据范围：regions 为空 = 全量数据（平台管理员恒为全量） */
    public List<String> scopeRegions() {
        if ("平台管理员".equals(ctx.op().getRole())) return List.of();
        Map<String, Object> dataScopes = ctx.store().obj("dataScopes");
        Object scope = dataScopes.get(ctx.op().getUsername());
        if (scope instanceof Map<?, ?> m && m.get("regions") instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) out.add(String.valueOf(o));
            return out;
        }
        return List.of();
    }

    /** 单条记录是否在当前操作人数据范围内（无范围=全量；无区域归属的记录可见，防御口径） */
    public boolean inScope(String coll, Map<String, Object> rec) {
        List<String> regions = scopeRegions();
        if (regions.isEmpty()) return true;
        if (!REGION_SCOPED.contains(coll)) return true;
        String region = regionOf(rec, maps());
        return region == null || regions.contains(region);
    }

    /** 按数据范围过滤集合行（O(n)：预建 终端/合同/调度 区域映射） */
    public List<Map<String, Object>> filter(String coll, List<Map<String, Object>> rows) {
        List<String> regions = scopeRegions();
        if (regions.isEmpty() || !REGION_SCOPED.contains(coll)) return rows;
        Map<String, String>[] maps = maps();
        List<Map<String, Object>> res = new ArrayList<>();
        for (Map<String, Object> rec : rows) {
            String region = regionOf(rec, maps);
            if (region == null || regions.contains(region)) res.add(rec);
        }
        return res;
    }

    /** 装货侧区域：dispatches/plans/contracts/transportRequests 直接取 loadTerminalId；
     *  settlements 经 contractId 派生；weighings 经 dispatchId（→ 调度单 loadTerminalId，缺失再经其 contractId）派生 */
    private String regionOf(Map<String, Object> rec, Map<String, String>[] m) {
        Map<String, String> terminalRegion = m[0];
        Map<String, String> contractLoad = m[1];
        Map<String, String> dispatchLoad = m[2];
        Map<String, String> dispatchContract = m[3];
        String loadTerminalId = str(rec, "loadTerminalId");
        if (loadTerminalId == null) {
            String contractId = str(rec, "contractId");
            if (contractId != null) loadTerminalId = contractLoad.get(contractId);
            if (loadTerminalId == null) {
                String dispatchId = str(rec, "dispatchId");
                if (dispatchId != null) {
                    loadTerminalId = dispatchLoad.get(dispatchId);
                    if (loadTerminalId == null) {
                        String cid = dispatchContract.get(dispatchId);
                        if (cid != null) loadTerminalId = contractLoad.get(cid);
                    }
                }
            }
        }
        return loadTerminalId == null ? null : terminalRegion.get(loadTerminalId);
    }

    /** 预建区域映射：[0]终端id→region [1]合同id→loadTerminalId [2]调度id→loadTerminalId [3]调度id→contractId */
    @SuppressWarnings("unchecked")
    private Map<String, String>[] maps() {
        Map<String, String> terminalRegion = new HashMap<>();
        for (Map<String, Object> t : ctx.store().list("terminals")) terminalRegion.put(str(t, "id"), str(t, "region"));
        Map<String, String> contractLoad = new HashMap<>();
        for (Map<String, Object> c : ctx.store().list("contracts")) contractLoad.put(str(c, "id"), str(c, "loadTerminalId"));
        Map<String, String> dispatchLoad = new HashMap<>();
        Map<String, String> dispatchContract = new HashMap<>();
        for (Map<String, Object> d : ctx.store().list("dispatches")) {
            dispatchLoad.put(str(d, "id"), str(d, "loadTerminalId"));
            dispatchContract.put(str(d, "id"), str(d, "contractId"));
        }
        return new Map[]{terminalRegion, contractLoad, dispatchLoad, dispatchContract};
    }

    private static String str(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
