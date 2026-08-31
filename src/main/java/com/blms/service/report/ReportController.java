package com.blms.service.report;

import com.blms.common.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 看板/报表只读端点（Phase 4 阶段 1 后端接线：DashboardService/ReportService 已 1:1 搬完，此处暴露）。
 * 口径与前端 dashboard.js / report.js 一致（集成测试口径对拍防漂移）。
 */
@RestController
@RequestMapping("/api")
public class ReportController {

    private final DashboardService dashboard;
    private final ReportService report;

    public ReportController(DashboardService dashboard, ReportService report) {
        this.dashboard = dashboard;
        this.report = report;
    }

    @Operation(summary = "看板核心 KPI（与前端 dashboard.kpi 1:1）")
    @GetMapping("/dashboard/kpi")
    public ApiResult<Map<String, Object>> kpi() {
        return ApiResult.success(dashboard.kpi());
    }

    @Operation(summary = "看板图表（商品结构/运输方式占比/场站吞吐TOP8/车辆状态分布）")
    @GetMapping("/dashboard/charts")
    public ApiResult<Map<String, Object>> charts() {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("commodityStructure", dashboard.commodityStructure());
        r.put("modeShare", dashboard.modeShare());
        r.put("terminalThroughput", dashboard.terminalThroughput());
        r.put("vehicleStatus", dashboard.vehicleStatus());
        return ApiResult.success(r);
    }

    @Operation(summary = "工作台指标（今日/本月 + 环比基期）")
    @GetMapping("/workbench/stats")
    public ApiResult<Map<String, Object>> workbenchStats() {
        return ApiResult.success(dashboard.workbenchStats());
    }

    @Operation(summary = "工作台待办列表（纯数据 key/title/desc/path）")
    @GetMapping("/workbench/todos")
    public ApiResult<List<Map<String, Object>>> workbenchTodos() {
        return ApiResult.success(dashboard.workbenchTodoList());
    }

    @Operation(summary = "月度运营报表（近 6 个月）")
    @GetMapping("/report/monthly")
    public ApiResult<List<Map<String, Object>>> monthly() {
        return ApiResult.success(report.monthlyReport());
    }

    @Operation(summary = "客户经营报表（按结算金额降序）")
    @GetMapping("/report/customer")
    public ApiResult<List<Map<String, Object>>> customer() {
        return ApiResult.success(report.customerReport());
    }

    @Operation(summary = "商品运量报表（含磅单损耗率）")
    @GetMapping("/report/commodity")
    public ApiResult<List<Map<String, Object>>> commodity() {
        return ApiResult.success(report.commodityReport());
    }

    @Operation(summary = "场站吞吐报表（按磅单进出汇总）")
    @GetMapping("/report/terminal")
    public ApiResult<List<Map<String, Object>>> terminal() {
        return ApiResult.success(report.terminalReport());
    }

    @Operation(summary = "成本利润报表（单车次成本归集，按车辆/线路/月聚合）")
    @GetMapping("/report/cost")
    public ApiResult<Map<String, Object>> cost() {
        return ApiResult.success(report.costReport());
    }
}
