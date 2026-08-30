package com.blms.service.safety;

import com.blms.common.ApiResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 安全接口（与前端 safety 视图契约 1:1）。
 * 事故登记/结案、培训计划/完成、车辆检查登记。
 */
@RestController
@RequestMapping("/api/safety")
public class SafetyController {

    private final SafetyService service;

    public SafetyController(SafetyService service) {
        this.service = service;
    }

    @PostMapping("/accident")
    public ApiResult<Map<String, Object>> registerAccident(@RequestBody Map<String, Object> body) {
        return ApiResult.success(service.registerAccident(body));
    }

    @PostMapping("/accident/{id}/close")
    public ApiResult<Map<String, Object>> closeAccident(@PathVariable String id) {
        return ApiResult.success(service.closeAccident(id));
    }

    @PostMapping("/training")
    public ApiResult<Map<String, Object>> addTraining(@RequestBody Map<String, Object> body) {
        return ApiResult.success(service.addTraining(body));
    }

    @PostMapping("/training/{id}/complete")
    public ApiResult<Map<String, Object>> completeTraining(@PathVariable String id, @RequestBody Map<String, List<String>> body) {
        return ApiResult.success(service.completeTraining(id, body.getOrDefault("driverIds", List.of())));
    }

    @PostMapping("/inspection")
    public ApiResult<Map<String, Object>> addInspection(@RequestBody Map<String, Object> body) {
        return ApiResult.success(service.addInspection(body));
    }
}
