package com.blms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 新建运输计划请求（A5：@Valid 字段级校验，缺必填 → 400 + 字段级错误）。字段与前端 plan 表单 1:1。 */
@Data
public class CreatePlanRequest {

    @Schema(description = "合同 id", requiredMode = Schema.RequiredMode.REQUIRED, example = "ctr01")
    @NotBlank(message = "请选择合同")
    private String contractId;
    @Schema(description = "批次数量（>0）", requiredMode = Schema.RequiredMode.REQUIRED, example = "500")
    @NotNull(message = "批次数量不能为空")
    @Positive(message = "批次数量须大于 0")
    private Double quantity;
    // 可选（缺省走服务层默认值：planDate=+1 天 / remark=空）
    @Schema(description = "计划日期 yyyy-MM-dd（缺省 +1 天）", example = "2026-09-02")
    private String planDate;
    @Schema(description = "备注（缺省空）")
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
