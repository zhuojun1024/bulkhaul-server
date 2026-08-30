package com.blms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 新建运输计划请求（A5：@Valid 字段级校验，缺必填 → 400 + 字段级错误）。字段与前端 plan 表单 1:1。 */
@Data
public class CreatePlanRequest {

    @NotBlank(message = "请选择合同")
    private String contractId;
    @NotNull(message = "批次数量不能为空")
    @Positive(message = "批次数量须大于 0")
    private Double quantity;
    // 可选（缺省走服务层默认值：planDate=+1 天 / remark=空）
    private String planDate;
    private String remark;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contractId", contractId);
        m.put("quantity", quantity);
        if (planDate != null) m.put("planDate", planDate);
        if (remark != null) m.put("remark", remark);
        return m;
    }
}
