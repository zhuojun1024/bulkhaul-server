package com.blms.service.weighing;

import com.blms.common.ApiResult;
import com.blms.common.OptimisticLockSupport;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 磅单接口（与前端 terminal/weighing 视图契约 1:1）。
 * correctWeighing：复磅更正 + 结算联动。
 */
@RestController
@RequestMapping("/api/weighing")
public class WeighingController {

    private final WeighingService service;

    public WeighingController(WeighingService service) {
        this.service = service;
    }

    @PostMapping("/{id}/correct")
    public ApiResult<Map<String, Object>> correct(@PathVariable String id, @RequestBody Map<String, Object> body) {
        OptimisticLockSupport.expectFromBody(id, body);
        double newNet = body.get("newNet") == null ? 0 : ((Number) body.get("newNet")).doubleValue();
        String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return ApiResult.success(service.correctWeighing(id, newNet, reason));
    }

    @PostMapping("/manual")
    public ApiResult<Map<String, Object>> manual(@RequestBody Map<String, Object> body) {
        String dispatchId = String.valueOf(body.get("dispatchId"));
        String type = String.valueOf(body.getOrDefault("type", "进磅"));
        double net = body.get("net") == null ? 0 : ((Number) body.get("net")).doubleValue();
        return ApiResult.success(service.manualWeighing(dispatchId, type, net));
    }
}
