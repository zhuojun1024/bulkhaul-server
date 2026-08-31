package com.blms.service.dispatch;

import com.blms.common.ApiResult;
import com.blms.common.OptimisticLockSupport;
import com.blms.dto.CreateDispatchesRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 调度接口（与前端 dispatch 视图契约 1:1）。
 * 主链路：createDispatches / confirmLoad / depart / arrive / confirmUnload / cancel / reassign / reportException / resume
 */
@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

    private final DispatchService service;
    private final DispatchDetailService detailService;

    public DispatchController(DispatchService service, DispatchDetailService detailService) {
        this.service = service;
        this.detailService = detailService;
    }

    /** Phase 4 阶段 1：详情聚合端点（Java 内存组装 7-8 集合，非 SQL join；前端详情页不再逐集合 join） */
    @io.swagger.v3.oas.annotations.Operation(summary = "调度详情聚合（dispatch+commodity+vehicle+driver+terminals+weighings+contract+plan+settlements+exceptions+派生值）")
    @GetMapping("/{id}/detail")
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        Map<String, Object> r = detailService.detail(id);
        if (r == null) return ApiResult.fail("调度单不存在: " + id, "not-found");
        return ApiResult.success(r);
    }

    @PostMapping("/create")
    public ApiResult<Map<String, Object>> create(@Valid @RequestBody CreateDispatchesRequest req) {
        return ApiResult.success(service.createDispatches(req.getPlanId(), req.getCount(), req.getVehicleIds() != null ? req.getVehicleIds() : List.of()));
    }

    @PostMapping("/{id}/confirmLoad")
    public ApiResult<Map<String, Object>> confirmLoad(@PathVariable String id, @RequestParam(required = false) Integer expectedVersion) {
        OptimisticLockSupport.expectFromQuery(id, expectedVersion);
        return ApiResult.success(service.confirmLoad(id));
    }

    @PostMapping("/{id}/accept")
    public ApiResult<Map<String, Object>> accept(@PathVariable String id, @RequestParam(required = false) Integer expectedVersion) {
        OptimisticLockSupport.expectFromQuery(id, expectedVersion);
        return ApiResult.success(service.acceptDispatch(id));
    }

    @PostMapping("/{id}/depart")
    public ApiResult<Map<String, Object>> depart(@PathVariable String id, @RequestParam(required = false) Integer expectedVersion) {
        OptimisticLockSupport.expectFromQuery(id, expectedVersion);
        return ApiResult.success(service.depart(id));
    }

    @PostMapping("/{id}/arrive")
    public ApiResult<Map<String, Object>> arrive(@PathVariable String id, @RequestParam(required = false) Integer expectedVersion) {
        OptimisticLockSupport.expectFromQuery(id, expectedVersion);
        return ApiResult.success(service.arrive(id));
    }

    @PostMapping("/{id}/confirmUnload")
    public ApiResult<Map<String, Object>> confirmUnload(@PathVariable String id, @RequestParam(required = false) Integer expectedVersion) {
        OptimisticLockSupport.expectFromQuery(id, expectedVersion);
        return ApiResult.success(service.confirmUnload(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResult<Map<String, Object>> cancel(@PathVariable String id, @RequestBody Map<String, Object> body) {
        OptimisticLockSupport.expectFromBody(id, body);
        return ApiResult.success(service.cancelDispatch(id, body.get("reason") == null ? null : String.valueOf(body.get("reason"))));
    }

    @PostMapping("/{id}/reassign")
    public ApiResult<Map<String, Object>> reassign(@PathVariable String id, @RequestBody Map<String, Object> body) {
        OptimisticLockSupport.expectFromBody(id, body);
        return ApiResult.success(service.reassignDispatch(id, body.get("vehicleId") == null ? null : String.valueOf(body.get("vehicleId")), body.get("driverId") == null ? null : String.valueOf(body.get("driverId"))));
    }

    @PostMapping("/{id}/reportException")
    public ApiResult<Map<String, Object>> reportException(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.reportException(id, body.get("description"),
                body.getOrDefault("type", "other"), body.getOrDefault("level", "medium")));
    }

    @PostMapping("/{id}/resume")
    public ApiResult<Map<String, Object>> resume(@PathVariable String id, @RequestParam(required = false) Integer expectedVersion) {
        OptimisticLockSupport.expectFromQuery(id, expectedVersion);
        return ApiResult.success(service.resumeDispatch(id));
    }

    // ===== 司机端（M6 身份守卫：司机本人或 dispatch 权限角色）=====

    @PostMapping("/{id}/driver/depart")
    public ApiResult<Map<String, Object>> driverDepart(@PathVariable String id) {
        return ApiResult.success(service.driverDepart(id));
    }

    @PostMapping("/{id}/driver/arrive")
    public ApiResult<Map<String, Object>> driverArrive(@PathVariable String id) {
        return ApiResult.success(service.driverArrive(id));
    }

    @PostMapping("/{id}/driver/signReceipt")
    public ApiResult<Map<String, Object>> signReceipt(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.signReceipt(id, body.get("signer")));
    }

    @PostMapping("/{id}/supplementReceipt")
    public ApiResult<Map<String, Object>> supplementReceipt(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.supplementReceipt(id, body.get("signer"), body.get("reason")));
    }

    @PostMapping("/{id}/scan/load")
    public ApiResult<Map<String, Object>> scanLoad(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.scanConfirmLoad(id, body.get("code")));
    }

    @PostMapping("/{id}/scan/unload")
    public ApiResult<Map<String, Object>> scanUnload(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.scanConfirmUnload(id, body.get("code")));
    }

    @GetMapping("/{id}/codes")
    public ApiResult<Map<String, String>> codes(@PathVariable String id) {
        Map<String, Object> d = service.dispatchOf(id);
        if (d == null) return ApiResult.fail("调度单不存在", "not-found");
        return ApiResult.success(Map.of("load", service.loadCodeOf(d), "unload", service.unloadCodeOf(d)));
    }

    /** 阶段 2 兼容探针（verify-auth.mjs 用）：验证 RBAC 放行/拦截路径 */
    @com.blms.auth.RequireAction(value = "dispatch", module = "调度管理", action = "下发调度单")
    @PostMapping("/probe")
    public ApiResult<String> probe() {
        var op = com.blms.auth.Operator.current();
        return ApiResult.success("dispatch-ok:" + op.getUsername() + ":" + op.getRole());
    }
}
