package com.blms.service.settlement;

import com.blms.common.ApiResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 结算接口（与前端 settlement/invoice/portal 视图契约 1:1）。
 * 结算流转：candidates → generate → startReconcile → customerConfirm → confirmSettle
 *          → recordPayment / applyPrepayment / dunning；发票 issue/redFlush。
 */
@RestController
@RequestMapping("/api/settlement")
public class SettlementController {

    private final SettlementService service;

    public SettlementController(SettlementService service) {
        this.service = service;
    }

    @GetMapping("/candidates")
    public ApiResult<List<Map<String, Object>>> candidates() {
        return ApiResult.success(service.settlementCandidates());
    }

    @PostMapping("/generate")
    public ApiResult<Map<String, Object>> generate(@RequestBody Map<String, List<String>> body) {
        return ApiResult.success(service.generateSettlements(body.getOrDefault("keys", List.of())));
    }

    @PostMapping("/{id}/startReconcile")
    public ApiResult<Map<String, Object>> startReconcile(@PathVariable String id) {
        return ApiResult.success(service.startReconcile(id));
    }

    @PostMapping("/{id}/recalc")
    public ApiResult<Map<String, Object>> recalc(@PathVariable String id) {
        return ApiResult.success(service.recalcSettlement(id));
    }

    @PostMapping("/{id}/customerConfirm")
    public ApiResult<Map<String, Object>> customerConfirm(@PathVariable String id) {
        return ApiResult.success(service.customerConfirm(id));
    }

    @PostMapping("/{id}/customerObjection")
    public ApiResult<Map<String, Object>> customerObjection(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.customerObjection(id, body.get("reason")));
    }

    @PostMapping("/{id}/confirmSettle")
    public ApiResult<Map<String, Object>> confirmSettle(@PathVariable String id) {
        return ApiResult.success(service.confirmSettle(id));
    }

    @PostMapping("/{id}/recordPayment")
    public ApiResult<Map<String, Object>> recordPayment(@PathVariable String id, @RequestBody Map<String, Object> body) {
        double amount = body.get("amount") == null ? 0 : ((Number) body.get("amount")).doubleValue();
        return ApiResult.success(service.recordPayment(id, amount, String.valueOf(body.getOrDefault("method", "银行转账"))));
    }

    @PostMapping("/{id}/revertPayment/{paymentId}")
    public ApiResult<Map<String, Object>> revertPayment(@PathVariable String id, @PathVariable String paymentId, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.revertPayment(id, paymentId, body.get("reason")));
    }

    @PostMapping("/{id}/applyPrepayment")
    public ApiResult<Map<String, Object>> applyPrepayment(@PathVariable String id, @RequestBody Map<String, Object> body) {
        double amount = body.get("amount") == null ? 0 : ((Number) body.get("amount")).doubleValue();
        return ApiResult.success(service.applyPrepayment(id, amount));
    }

    @PostMapping("/{id}/dunning")
    public ApiResult<Map<String, Object>> dunning(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.dunning(id, body.getOrDefault("level", "reminder")));
    }

    @PostMapping("/{id}/issueInvoice")
    public ApiResult<Map<String, Object>> issueInvoice(@PathVariable String id) {
        return ApiResult.success(service.issueInvoice(id));
    }

    @PostMapping("/invoice/{invoiceId}/redFlush")
    public ApiResult<Map<String, Object>> redFlush(@PathVariable String invoiceId, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.redFlushInvoiceRow(invoiceId, body.get("reason")));
    }

    @PostMapping("/prepayment/collect")
    public ApiResult<Map<String, Object>> collectPrepayment(@RequestBody Map<String, Object> body) {
        double amount = body.get("amount") == null ? 0 : ((Number) body.get("amount")).doubleValue();
        return ApiResult.success(service.collectPrepayment(String.valueOf(body.get("customerId")), amount,
                String.valueOf(body.getOrDefault("method", "银行转账")), body.get("remark") == null ? "" : String.valueOf(body.get("remark"))));
    }
}
