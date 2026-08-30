package com.blms.service.warehouse;

import com.blms.common.ApiResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 仓储接口（与前端 warehouse 视图契约 1:1）。
 * manualInbound（手工入库）/ 安全库存预警。
 */
@RestController
@RequestMapping("/api/warehouse")
public class WarehouseController {

    private final WarehouseService service;

    public WarehouseController(WarehouseService service) {
        this.service = service;
    }

    @PostMapping("/inbound")
    public ApiResult<Map<String, Object>> inbound(@RequestBody Map<String, Object> body) {
        double qty = body.get("quantity") == null ? 0 : ((Number) body.get("quantity")).doubleValue();
        String batch = body.get("batch") == null ? "" : String.valueOf(body.get("batch"));
        String remark = body.get("remark") == null ? "" : String.valueOf(body.get("remark"));
        return ApiResult.success(service.manualInbound(
                String.valueOf(body.get("warehouseId")),
                String.valueOf(body.get("commodityId")),
                qty, batch, remark));
    }

    @GetMapping("/inventoryAlerts")
    public ApiResult<List<Map<String, Object>>> inventoryAlerts() {
        return ApiResult.success(service.inventoryAlerts());
    }

    @PostMapping("/safetyStock")
    public ApiResult<Map<String, Object>> setSafetyStock(@RequestBody Map<String, Object> body) {
        double minQty = body.get("minQty") == null ? Double.NaN : ((Number) body.get("minQty")).doubleValue();
        return ApiResult.success(service.setSafetyStock(
                String.valueOf(body.get("warehouseId")), String.valueOf(body.get("commodityId")), minQty));
    }

    @PostMapping("/inventory/{id}/status")
    public ApiResult<Map<String, Object>> setInventoryStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.setInventoryStatus(id, body.get("status")));
    }
}
