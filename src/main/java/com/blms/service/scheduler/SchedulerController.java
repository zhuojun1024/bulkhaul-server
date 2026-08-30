package com.blms.service.scheduler;

import com.blms.common.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 定时任务接口：手动触发单轮（等价前端 runSchedulerTick 冒烟测试同步调用）。
 * 自动轮询由 @Scheduled(fixedDelay=3000) 驱动，此端点供验证/调试。
 */
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    private final SchedulerService service;

    public SchedulerController(SchedulerService service) {
        this.service = service;
    }

    /** 手动触发单轮定时任务，返回本轮统计（fenceCreated/overdueChanged/escalated/reminded） */
    @Operation(summary = "手动触发单轮定时任务（C4）", description = "等价前端 runSchedulerTick：围栏事件/GPS 遥测/逾期校准/异常升级/合同审批到期。自动轮询由 @Scheduled 驱动（leader 单实例执行）；此端点供验证/调试，不受 leader 租约限制")
    @PostMapping("/tick")
    public ApiResult<Map<String, Object>> tick() {
        return ApiResult.success(service.doTick());
    }
}
