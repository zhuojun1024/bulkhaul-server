package com.blms.common;

import com.blms.service.admin.DataScopeService;
import com.blms.store.DataStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/{name}")
    public ApiResult<List<Map<String, Object>>> list(@PathVariable String name) {
        if (DataStore.LIST_COLLS.contains(name)) {
            return ApiResult.success(DataScopeService.REGION_SCOPED.contains(name)
                    ? scope.filter(name, store.list(name))
                    : store.list(name));
        }
        if (DataStore.OBJ_COLLS.contains(name)) return ApiResult.success(List.of(store.obj(name)));
        return ApiResult.fail("未知集合: " + name, "not-found");
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
