package com.blms.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 计划调度（新建车次）请求（A5：@Valid 字段级校验，缺必填 → 400 + 字段级错误）。字段与前端 dispatch 表单 1:1。 */
@Data
public class CreateDispatchesRequest {

    @NotBlank(message = "请选择运输计划")
    private String planId;
    @NotNull(message = "车次数量不能为空")
    @Min(value = 1, message = "车次数量须至少 1")
    private Integer count;
    // 可选（缺省空列表）
    private List<String> vehicleIds;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", planId);
        m.put("count", count);
        m.put("vehicleIds", vehicleIds != null ? vehicleIds : List.of());
        return m;
    }
}
