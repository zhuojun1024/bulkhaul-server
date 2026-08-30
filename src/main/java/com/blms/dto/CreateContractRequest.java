package com.blms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 新建合同请求（A5：@Valid 字段级校验，缺必填 → 400 + 字段级错误）。
 * 字段与前端 contract 表单 1:1；约束消息对齐服务层既有校验文案（UX 不变）。
 */
@Data
public class CreateContractRequest {

    @Schema(description = "合同名称", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100, example = "2026 年 Q3 煤炭运输合同")
    @NotBlank(message = "合同名称不能为空")
    @Size(max = 100, message = "合同名称过长（≤100 字）")
    private String name;
    @Schema(description = "发货方客户 id", requiredMode = Schema.RequiredMode.REQUIRED, example = "cust01")
    @NotBlank(message = "请选择发货方客户")
    private String shipperId;
    @Schema(description = "收货方客户 id", requiredMode = Schema.RequiredMode.REQUIRED, example = "cust02")
    @NotBlank(message = "请选择收货方客户")
    private String consigneeId;
    @Schema(description = "商品 id", requiredMode = Schema.RequiredMode.REQUIRED, example = "com01")
    @NotBlank(message = "请选择商品")
    private String commodityId;
    @Schema(description = "装货场站 id", requiredMode = Schema.RequiredMode.REQUIRED, example = "term01")
    @NotBlank(message = "请选择装货场站")
    private String loadTerminalId;
    @Schema(description = "卸货场站 id", requiredMode = Schema.RequiredMode.REQUIRED, example = "term02")
    @NotBlank(message = "请选择卸货场站")
    private String unloadTerminalId;
    @Schema(description = "计划数量（>0）", requiredMode = Schema.RequiredMode.REQUIRED, example = "5000")
    @NotNull(message = "计划数量不能为空")
    @Positive(message = "计划数量须大于 0")
    private Double quantity;
    @Schema(description = "合同单价（>0）", requiredMode = Schema.RequiredMode.REQUIRED, example = "320.5")
    @NotNull(message = "合同单价不能为空")
    @Positive(message = "合同单价须大于 0")
    private Double unitPrice;
    // 可选字段（缺省走服务层默认值：mode=公路 / paymentDays=30 / startDate=今天 / endDate=+180 天 / contact·phone=发货方 / remark=空）
    @Schema(description = "运输方式（缺省 公路）", example = "公路")
    private String mode;
    @Schema(description = "账期天数（缺省 30）", example = "30")
    private Integer paymentDays;
    @Schema(description = "开始日期 yyyy-MM-dd（缺省今天）", example = "2026-09-01")
    private String startDate;
    @Schema(description = "结束日期 yyyy-MM-dd（缺省 +180 天）", example = "2027-02-28")
    private String endDate;
    @Schema(description = "联系人（缺省发货方）")
    private String contact;
    @Schema(description = "联系电话（缺省发货方）")
    private String phone;
    @Schema(description = "备注（缺省空）")
    private String remark;
    @Schema(description = "初始状态（缺省 draft）", example = "draft")
    private String status;

    /** 转服务层 Map：必填字段恒在，可选字段非 null 才放（保持服务层 getOrDefault 默认值语义） */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("shipperId", shipperId);
        m.put("consigneeId", consigneeId);
        m.put("commodityId", commodityId);
        m.put("loadTerminalId", loadTerminalId);
        m.put("unloadTerminalId", unloadTerminalId);
        m.put("quantity", quantity);
        m.put("unitPrice", unitPrice);
        if (mode != null) m.put("mode", mode);
        if (paymentDays != null) m.put("paymentDays", paymentDays);
        if (startDate != null) m.put("startDate", startDate);
        if (endDate != null) m.put("endDate", endDate);
        if (contact != null) m.put("contact", contact);
        if (phone != null) m.put("phone", phone);
        if (remark != null) m.put("remark", remark);
        if (status != null) m.put("status", status);
        return m;
    }
}
