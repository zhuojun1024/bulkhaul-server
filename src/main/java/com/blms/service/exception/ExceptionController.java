package com.blms.service.exception;

import com.blms.common.ApiResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 异常处置接口（与前端 exception 视图契约 1:1）。
 * 上报（reportException）在 DispatchController；此处受理/处置/关闭。
 */
@RestController
@RequestMapping("/api/exception")
public class ExceptionController {

    private final ExceptionService service;

    public ExceptionController(ExceptionService service) {
        this.service = service;
    }

    @PostMapping("/{id}/accept")
    public ApiResult<Map<String, Object>> accept(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResult.success(service.acceptException(id, body.get("handler")));
    }

    @PostMapping("/{id}/finish")
    public ApiResult<Map<String, Object>> finish(@PathVariable String id, @RequestBody Map<String, Object> body) {
        double cost = body.get("cost") == null ? 0 : ((Number) body.get("cost")).doubleValue();
        return ApiResult.success(service.finishException(id, String.valueOf(body.getOrDefault("result", "")), cost));
    }

    @PostMapping("/{id}/close")
    public ApiResult<Map<String, Object>> close(@PathVariable String id) {
        return ApiResult.success(service.closeException(id));
    }
}
