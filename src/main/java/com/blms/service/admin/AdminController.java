package com.blms.service.admin;

import com.blms.common.ApiResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理后台接口（与前端 flow.js 管理域/系统管理域/运价域/消息域契约 1:1）。
 * 主数据 CRUD（商品/客户/场站/仓库/司机/车辆）+ 用户/角色/权限/数据范围 + 运价卡 + 消息/免打扰 + 全局校准。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService admin;
    private final UserAdminService userAdmin;
    private final RateCardService rate;
    private final RecalcService recalc;

    public AdminController(AdminService admin, UserAdminService userAdmin, RateCardService rate, RecalcService recalc) {
        this.admin = admin;
        this.userAdmin = userAdmin;
        this.rate = rate;
        this.recalc = recalc;
    }

    /* ===== 商品 ===== */
    @PostMapping("/commodity")
    public ApiResult<Map<String, Object>> saveCommodity(@RequestBody Map<String, Object> body) {
        return ApiResult.success(admin.saveCommodity(body));
    }
    @PostMapping("/commodity/{id}/toggle")
    public ApiResult<Map<String, Object>> toggleCommodity(@PathVariable String id) {
        return ApiResult.success(admin.toggleCommodityStatus(id));
    }
    @PostMapping("/commodity/import")
    public ApiResult<Map<String, Object>> importCommodities(@RequestBody List<Map<String, Object>> rows) {
        return ApiResult.success(admin.importCommodities(rows));
    }

    /* ===== 客户 ===== */
    @PostMapping("/customer/{id}/toggle")
    public ApiResult<Map<String, Object>> toggleCustomer(@PathVariable String id) {
        return ApiResult.success(admin.toggleCustomerStatus(id));
    }
    @PostMapping("/customer/import")
    public ApiResult<Map<String, Object>> importCustomers(@RequestBody List<Map<String, Object>> rows) {
        return ApiResult.success(admin.importCustomers(rows));
    }

    /* ===== 场站 ===== */
    @PostMapping("/terminal")
    public ApiResult<Map<String, Object>> saveTerminal(@RequestBody Map<String, Object> body) {
        return ApiResult.success(admin.saveTerminal(body));
    }

    /* ===== 仓库 ===== */
    @PostMapping("/warehouse")
    public ApiResult<Map<String, Object>> saveWarehouse(@RequestBody Map<String, Object> body) {
        return ApiResult.success(admin.saveWarehouse(body));
    }

    /* ===== 司机 ===== */
    @PostMapping("/driver")
    public ApiResult<Map<String, Object>> saveDriver(@RequestBody Map<String, Object> body) {
        return ApiResult.success(admin.saveDriver(body));
    }
    @PostMapping("/driver/{id}/toggle")
    public ApiResult<Map<String, Object>> toggleDriver(@PathVariable String id) {
        return ApiResult.success(admin.toggleDriverStatus(id));
    }
    @PostMapping("/driver/import")
    public ApiResult<Map<String, Object>> importDrivers(@RequestBody List<Map<String, Object>> rows) {
        return ApiResult.success(admin.importDrivers(rows));
    }

    /* ===== 车辆 ===== */
    @PostMapping("/vehicle/import")
    public ApiResult<Map<String, Object>> importVehicles(@RequestBody List<Map<String, Object>> rows) {
        return ApiResult.success(admin.importVehicles(rows));
    }
    @PostMapping("/vehicle/{id}/repair")
    public ApiResult<Map<String, Object>> sendVehicleRepair(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return ApiResult.success(admin.sendVehicleRepair(id, body == null ? null : body.get("reason")));
    }
    @PostMapping("/vehicle/{id}/resume")
    public ApiResult<Map<String, Object>> resumeVehicle(@PathVariable String id) {
        return ApiResult.success(admin.resumeVehicle(id));
    }

    /* ===== 用户 ===== */
    @PostMapping("/user")
    public ApiResult<Map<String, Object>> saveUser(@RequestBody Map<String, Object> body) {
        return ApiResult.success(userAdmin.saveUser(body));
    }
    @DeleteMapping("/user/{id}")
    public ApiResult<Map<String, Object>> removeUser(@PathVariable String id) {
        return ApiResult.success(userAdmin.removeUser(id));
    }
    @PostMapping("/user/{id}/toggle")
    public ApiResult<Map<String, Object>> toggleUser(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        return ApiResult.success(userAdmin.toggleUserStatus(id, Boolean.TRUE.equals(body.get("active"))));
    }
    @PostMapping("/user/{id}/resetPassword")
    public ApiResult<Map<String, Object>> resetPassword(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(userAdmin.resetPassword(id, body.get("password")));
    }

    /* ===== 角色 / 权限 / 数据范围 ===== */
    @PostMapping("/role")
    public ApiResult<Map<String, Object>> saveRole(@RequestBody Map<String, Object> body) {
        return ApiResult.success(userAdmin.saveRole(body));
    }
    @DeleteMapping("/role/{id}")
    public ApiResult<Map<String, Object>> removeRole(@PathVariable String id) {
        return ApiResult.success(userAdmin.removeRole(id));
    }
    @PutMapping("/role/{name}/perms")
    public ApiResult<Map<String, Object>> updateRolePerms(@PathVariable String name, @RequestBody Map<String, Object> body) {
        return ApiResult.success(userAdmin.updateRolePerms(name, body));
    }
    @PutMapping("/user/{username}/dataScope")
    public ApiResult<Map<String, Object>> setDataScope(@PathVariable String username, @RequestBody Map<String, List<String>> body) {
        return ApiResult.success(userAdmin.setDataScope(username, body.get("regions")));
    }

    /* ===== 免打扰 / 数据范围读 ===== */
    @PutMapping("/dnd")
    public ApiResult<Map<String, Object>> setDnd(@RequestBody Map<String, Object> body) {
        return ApiResult.success(userAdmin.setDnd(body));
    }
    @GetMapping("/dnd")
    public ApiResult<Map<String, Object>> getDnd() {
        return ApiResult.success(userAdmin.getDnd());
    }
    @GetMapping("/dataScope")
    public ApiResult<Map<String, Object>> dataScopeOf() {
        return ApiResult.success(userAdmin.dataScopeOf());
    }

    /* ===== 消息 ===== */
    @GetMapping("/messages")
    public ApiResult<List<Map<String, Object>>> messages() {
        return ApiResult.success(userAdmin.visibleMessages());
    }
    @GetMapping("/messages/unreadCount")
    public ApiResult<Map<String, Object>> unreadCount() {
        return ApiResult.success(Map.of("count", userAdmin.unreadCount()));
    }
    @PostMapping("/messages/readAll")
    public ApiResult<Map<String, Object>> readAll() {
        return ApiResult.success(Map.of("count", userAdmin.markAllMessagesRead()));
    }
    @PostMapping("/messages/{id}/read")
    public ApiResult<Map<String, Object>> read(@PathVariable String id) {
        return ApiResult.success(userAdmin.markMessageRead(id));
    }

    /* ===== 运价卡 ===== */
    @PostMapping("/rateCard")
    public ApiResult<Map<String, Object>> createRateCard(@RequestBody Map<String, Object> body) {
        return ApiResult.success(rate.createRateCard(body));
    }
    @PutMapping("/rateCard/{id}")
    public ApiResult<Map<String, Object>> updateRateCard(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResult.success(rate.updateRateCard(id, body));
    }
    @PostMapping("/rateCard/{id}/toggle")
    public ApiResult<Map<String, Object>> toggleRateCard(@PathVariable String id) {
        return ApiResult.success(rate.toggleRateCard(id));
    }

    /* ===== 全局校准 ===== */
    @PostMapping("/recalc")
    public ApiResult<Map<String, Object>> recalcAll() {
        return ApiResult.success(recalc.recalcAll());
    }

    /* ===== 电子围栏参数（track 监控页，OBJ_COLL fenceConfig 整体读写） ===== */
    @GetMapping("/fenceConfig")
    public ApiResult<Map<String, Object>> getFenceConfig() {
        return ApiResult.success(admin.getFenceConfig());
    }
    @PutMapping("/fenceConfig")
    public ApiResult<Map<String, Object>> saveFenceConfig(@RequestBody Map<String, Object> body) {
        return ApiResult.success(admin.saveFenceConfig(body));
    }
}
