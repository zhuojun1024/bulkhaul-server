package com.blms.service.insurance;

import com.blms.common.ApiResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 保险接口（与前端 safety/insurance 视图契约 1:1）。
 * 报险 / 定责核定 / 理赔结案（冲减账单异常损失）/ 拒赔。
 */
@RestController
@RequestMapping("/api/insurance")
public class InsuranceController {

    private final InsuranceService service;

    public InsuranceController(InsuranceService service) {
        this.service = service;
    }

    @PostMapping("/claim")
    public ApiResult<Map<String, Object>> file(@RequestBody Map<String, Object> body) {
        Map<String, Object> payload = body;
        String accidentId = String.valueOf(body.getOrDefault("accidentId", ""));
        return ApiResult.success(service.fileInsuranceClaim(accidentId, payload));
    }

    @PostMapping("/claim/{id}/assess")
    public ApiResult<Map<String, Object>> assess(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResult.success(service.assessInsuranceClaim(id, body));
    }

    @PostMapping("/claim/{id}/settle")
    public ApiResult<Map<String, Object>> settle(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResult.success(service.settleInsuranceClaim(id, body));
    }

    @PostMapping("/claim/{id}/reject")
    public ApiResult<Map<String, Object>> reject(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.rejectInsuranceClaim(id, body.get("reason")));
    }
}
