package com.blms.common;

import com.blms.service.admin.DataScopeService;
import com.blms.store.DataStore;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读端点（供前端列表页与验证脚本读取集合状态）。
 * GET /api/coll/<name> → 集合记录（行级数据范围过滤：dispatches/plans/contracts/transportRequests/
 * settlements/weighings 按当前操作人装货侧区域，regions 为空=全量）；GET /api/coll/<name>/<id> → 单条
 * （越权范围返回 403）。其余集合（customers.region 为省份非数据区域 / logs / messages 菜单角色门控）返回全量。
 */
@RestController
@RequestMapping("/api/coll")
public class CollReadController {

    private final DataStore store;
    private final DataScopeService scope;

    public CollReadController(DataStore store, DataScopeService scope) {
        this.store = store;
        this.scope = scope;
    }

    /**
     * 列表（B2 分页）：
     *  - 不带 page → 全量返回（向后兼容：/api/snapshot hydrate、验证脚本、旧前端客户端分页均不受影响）；
     *  - 带 page → {list, total, page, size}（列表页按页加载，不再拉全量；size 默认 20，上限 200）。
     * 行级数据范围（A1）：区域集合先按当前操作人过滤，total=过滤后行数（分页与行级过滤叠加正确）。
     */
    @Operation(summary = "集合列表（B2 分页 + 可选过滤）", description = "不带 page → 全量（向后兼容）；带 page → {list,total,page,size}（size 默认 20 上限 200）。区域集合按当前操作人数据范围过滤（A1）。可选过滤：status/mode（字段等值）、keyword（id/name 子串）、dateFrom/dateTo（signDate 区间）——Phase 4 阶段 3 列表页服务端分页")
    @GetMapping("/{name}")
    public ApiResult<Object> list(@PathVariable String name,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String mode,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String dateFrom,
                                  @RequestParam(required = false) String dateTo) {
        if (!DataStore.LIST_COLLS.contains(name)) {
            if (DataStore.OBJ_COLLS.contains(name)) return ApiResult.success(List.of(store.obj(name)));
            return ApiResult.fail("未知集合: " + name, "not-found");
        }
        List<Map<String, Object>> rows = DataScopeService.REGION_SCOPED.contains(name)
                ? scope.filter(name, store.list(name))
                : store.list(name);
        rows = applyFilters(name, rows, status, mode, keyword, dateFrom, dateTo);
        if (page == null) return ApiResult.success(rows); // 向后兼容：全量
        int p = Math.max(1, page);
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        int from = Math.min((p - 1) * s, rows.size());
        int to = Math.min(from + s, rows.size());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", new ArrayList<>(rows.subList(from, to))); // 拷贝，不暴露活视图
        data.put("total", rows.size());
        data.put("page", p);
        data.put("size", s);
        return ApiResult.success(data);
    }

    /**
     * 可选过滤（Phase 4 阶段 3 列表页服务端分页）：
     *  - status/mode：字段等值（记录含该字段且非空时生效，通用口径）；
     *  - keyword：id/name 子串（忽略大小写）；
     *  - dateFrom/dateTo：signDate 字符串区间（含端点，字典序即时间序）。
     * 无过滤参数 → 原样返回（向后兼容）。
     *  - name：集合名（contracts 特例：keyword 匹配客户名）。
     */
    private List<Map<String, Object>> applyFilters(String name, List<Map<String, Object>> rows, String status, String mode,
                                                   String keyword, String dateFrom, String dateTo) {
        boolean any = (status != null && !status.isBlank()) || (mode != null && !mode.isBlank())
                || (keyword != null && !keyword.isBlank()) || (dateFrom != null && !dateFrom.isBlank())
                || (dateTo != null && !dateTo.isBlank());
        if (!any) return rows;
        String kw = keyword == null ? null : keyword.toLowerCase();
        // contracts 特例：keyword 同时匹配发货方/收货方客户名（与前端列表页 filtered 口径一致）
        java.util.Set<String> customerIds = null;
        if (kw != null && "contracts".equals(name)) {
            customerIds = new java.util.HashSet<>();
            for (Map<String, Object> cu : store.list("customers")) {
                if (String.valueOf(cu.get("name")).toLowerCase().contains(kw)) customerIds.add(String.valueOf(cu.get("id")));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (status != null && !status.isBlank()) {
                Object v = r.get("status");
                if (v == null || !status.equals(String.valueOf(v))) continue;
            }
            if (mode != null && !mode.isBlank()) {
                Object v = r.get("mode");
                if (v == null || !mode.equals(String.valueOf(v))) continue;
            }
            if (kw != null) {
                String id = r.get("id") == null ? "" : String.valueOf(r.get("id")).toLowerCase();
                String recName = r.get("name") == null ? "" : String.valueOf(r.get("name")).toLowerCase();
                boolean hit = id.contains(kw) || recName.contains(kw);
                if (!hit && customerIds != null) {
                    Object shipper = r.get("shipperId");
                    Object consignee = r.get("consigneeId");
                    hit = (shipper != null && customerIds.contains(String.valueOf(shipper)))
                            || (consignee != null && customerIds.contains(String.valueOf(consignee)));
                }
                if (!hit) continue;
            }
            if (dateFrom != null && !dateFrom.isBlank() || dateTo != null && !dateTo.isBlank()) {
                Object sd = r.get("signDate");
                String s = sd == null ? "" : String.valueOf(sd);
                if (dateFrom != null && !dateFrom.isBlank() && s.compareTo(dateFrom) < 0) continue;
                if (dateTo != null && !dateTo.isBlank() && s.compareTo(dateTo) > 0) continue;
            }
            out.add(r);
        }
        return out;
    }

    @Operation(summary = "集合单条", description = "越权数据范围返回 403 forbidden；不存在返回 404 not-found")
    @GetMapping("/{name}/{id}")
    public ApiResult<Map<String, Object>> one(@PathVariable String name, @PathVariable String id) {
        if (!DataStore.LIST_COLLS.contains(name)) return ApiResult.fail("未知集合: " + name, "not-found");
        for (Map<String, Object> r : store.list(name)) {
            if (id.equals(r.get("id"))) {
                if (DataScopeService.REGION_SCOPED.contains(name) && !scope.inScope(name, r)) {
                    return ApiResult.fail("记录不在当前数据范围内", "forbidden");
                }
                return ApiResult.success(r);
            }
        }
        return ApiResult.fail("记录不存在: " + id, "not-found");
    }
}
