package com.blms.service.scheduler;

import com.blms.common.ApiResult;
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
    @PostMapping("/tick")
    public ApiResult<Map<String, Object>> tick() {
        return ApiResult.success(service.doTick());
    }
}
