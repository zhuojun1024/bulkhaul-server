package com.blms.service.settlement;

import com.blms.common.ApiResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 财务接口（与前端 settlement 应付/银行核销视图契约 1:1）。
 * 趟次应付：generatePayables / payPayable / payableStats。
 * 银行核销：addBankStatement / matchBankRecord / autoMatchBank。
 */
@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    private final FinanceService service;

    public FinanceController(FinanceService service) {
        this.service = service;
    }

    @PostMapping("/payables/generate")
    public ApiResult<Map<String, Object>> generatePayables() {
        return ApiResult.success(service.generatePayables());
    }

    @PostMapping("/payables/{id}/pay")
    public ApiResult<Map<String, Object>> pay(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.payPayable(id, body.get("method")));
    }

    @GetMapping("/payables/stats")
    public ApiResult<Map<String, Object>> payableStats() {
        return ApiResult.success(service.payableStats());
    }

    @PostMapping("/bank/statement")
    public ApiResult<Map<String, Object>> addBankStatement(@RequestBody Map<String, Object> body) {
        return ApiResult.success(service.addBankStatement(body));
    }

    @PostMapping("/bank/{bankId}/match")
    public ApiResult<Map<String, Object>> match(@PathVariable String bankId, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.matchBankRecord(bankId, body.get("settlementId")));
    }

    @PostMapping("/bank/autoMatch")
    public ApiResult<List<Map<String, Object>>> autoMatch() {
        return ApiResult.success(service.autoMatchBank());
    }
}
