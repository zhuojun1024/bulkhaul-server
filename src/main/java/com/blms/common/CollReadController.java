package com.blms.common;

import com.blms.service.admin.DataScopeService;
import com.blms.store.DataStore;
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
    @GetMapping("/{name}")
    public ApiResult<Object> list(@PathVariable String name,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        if (!DataStore.LIST_COLLS.contains(name)) {
            if (DataStore.OBJ_COLLS.contains(name)) return ApiResult.success(List.of(store.obj(name)));
            return ApiResult.fail("未知集合: " + name, "not-found");
        }
        List<Map<String, Object>> rows = DataScopeService.REGION_SCOPED.contains(name)
                ? scope.filter(name, store.list(name))
                : store.list(name);
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
