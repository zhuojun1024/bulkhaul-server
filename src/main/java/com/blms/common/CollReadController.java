package com.blms.common;

import com.blms.store.DataStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 只读端点（供前端列表页与验证脚本读取集合状态）。
 * GET /api/coll/<name> → 集合全部记录；GET /api/coll/<name>/<id> → 单条。
 * 数据范围过滤（行级权限）在阶段 5 的列表服务中实现；此处返回全量（演示级）。
 */
@RestController
@RequestMapping("/api/coll")
public class CollReadController {

    private final DataStore store;

    public CollReadController(DataStore store) {
        this.store = store;
    }

    @GetMapping("/{name}")
    public ApiResult<List<Map<String, Object>>> list(@PathVariable String name) {
        if (DataStore.LIST_COLLS.contains(name)) return ApiResult.success(store.list(name));
        if (DataStore.OBJ_COLLS.contains(name)) return ApiResult.success(List.of(store.obj(name)));
        return ApiResult.fail("未知集合: " + name, "not-found");
    }

    @GetMapping("/{name}/{id}")
    public ApiResult<Map<String, Object>> one(@PathVariable String name, @PathVariable String id) {
        if (!DataStore.LIST_COLLS.contains(name)) return ApiResult.fail("未知集合: " + name, "not-found");
        for (Map<String, Object> r : store.list(name)) {
            if (id.equals(r.get("id"))) return ApiResult.success(r);
        }
        return ApiResult.fail("记录不存在: " + id, "not-found");
    }
}
