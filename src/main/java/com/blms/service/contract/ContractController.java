package com.blms.service.contract;

import com.blms.common.ApiResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 合同/计划接口（与前端 contract/plan 视图契约 1:1）。
 */
@RestController
@RequestMapping("/api")
public class ContractController {

    private final ContractService service;

    public ContractController(ContractService service) {
        this.service = service;
    }

    @PostMapping("/contract")
    public ApiResult<Map<String, Object>> createContract(@RequestBody Map<String, Object> body) {
        return ApiResult.success(service.createContract(body, body.getOrDefault("status", "draft").toString()));
    }

    @GetMapping("/contract/remaining/{id}")
    public ApiResult<Double> remaining(@PathVariable String id) {
        return ApiResult.success(service.contractRemaining(id));
    }

    @GetMapping("/contract/credit-check")
    public ApiResult<Map<String, Object>> creditCheck(@RequestParam String customerId, @RequestParam double orderAmount) {
        return ApiResult.success(service.creditCheck(customerId, orderAmount));
    }

    @PostMapping("/plan")
    public ApiResult<Map<String, Object>> createPlan(@RequestBody Map<String, Object> body) {
        return ApiResult.success(service.createPlan(body));
    }

    @PostMapping("/plan/{id}/cancel")
    public ApiResult<Map<String, Object>> cancelPlan(@PathVariable String id) {
        return ApiResult.success(service.cancelPlan(id));
    }

    // ===== 合同审批（部门审批 → 公司审批） =====
    @PostMapping("/contract/{id}/submitApproval")
    public ApiResult<Map<String, Object>> submitApproval(@PathVariable String id) {
        return ApiResult.success(service.submitContractApproval(id));
    }

    @PostMapping("/contract/{id}/approve")
    public ApiResult<Map<String, Object>> approve(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.approveContract(id, body.get("comment")));
    }

    @PostMapping("/contract/{id}/reject")
    public ApiResult<Map<String, Object>> reject(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.rejectContract(id, body.get("reason")));
    }

    // ===== 合同变更（改价审批） =====
    @PostMapping("/contract/{id}/change")
    public ApiResult<Map<String, Object>> change(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> fields = body.get("fields") instanceof Map ? (Map<String, Object>) body.get("fields") : body;
        return ApiResult.success(service.changeContract(id, fields, body.get("reason") == null ? "" : String.valueOf(body.get("reason"))));
    }

    @PostMapping("/contract/{id}/approveChange")
    public ApiResult<Map<String, Object>> approveChange(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.approveContractChange(id, body.get("comment")));
    }

    @PostMapping("/contract/{id}/rejectChange")
    public ApiResult<Map<String, Object>> rejectChange(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.rejectContractChange(id, body.get("reason")));
    }

    // ===== 合同生命周期 =====
    @PostMapping("/contract/{id}/extend")
    public ApiResult<Map<String, Object>> extend(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.extendContract(id, body.get("newDate"), body.get("reason")));
    }

    @PostMapping("/contract/{id}/terminate")
    public ApiResult<Map<String, Object>> terminate(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean settleNow = body.get("settleNow") == null || Boolean.TRUE.equals(body.get("settleNow"));
        return ApiResult.success(service.terminateContract(id, String.valueOf(body.getOrDefault("reason", "")), settleNow));
    }

    @PostMapping("/contract/{id}/complete")
    public ApiResult<Map<String, Object>> complete(@PathVariable String id) {
        return ApiResult.success(service.completeContract(id));
    }

    @PostMapping("/contract/{id}/archive")
    public ApiResult<Map<String, Object>> archive(@PathVariable String id) {
        return ApiResult.success(service.archiveContract(id));
    }

    // ===== 客户运输需求（客户门户） =====
    @PostMapping("/contract/request")
    public ApiResult<Map<String, Object>> submitTransportRequest(@RequestBody Map<String, Object> body) {
        return ApiResult.success(service.submitTransportRequest(String.valueOf(body.get("customerId")), body));
    }

    @PostMapping("/contract/request/{id}/convert")
    public ApiResult<Map<String, Object>> convertRequest(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResult.success(service.convertRequestToContract(id, body == null ? Map.of() : body));
    }

    @PostMapping("/contract/request/{id}/reject")
    public ApiResult<Map<String, Object>> rejectRequest(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.rejectTransportRequest(id, body.get("reason")));
    }
}
