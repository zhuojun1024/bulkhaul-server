package com.blms.dto;

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

    @NotBlank(message = "合同名称不能为空")
    @Size(max = 100, message = "合同名称过长（≤100 字）")
    private String name;
    @NotBlank(message = "请选择发货方客户")
    private String shipperId;
    @NotBlank(message = "请选择收货方客户")
    private String consigneeId;
    @NotBlank(message = "请选择商品")
    private String commodityId;
    @NotBlank(message = "请选择装货场站")
    private String loadTerminalId;
    @NotBlank(message = "请选择卸货场站")
    private String unloadTerminalId;
    @NotNull(message = "计划数量不能为空")
    @Positive(message = "计划数量须大于 0")
    private Double quantity;
    @NotNull(message = "合同单价不能为空")
    @Positive(message = "合同单价须大于 0")
    private Double unitPrice;
    // 可选字段（缺省走服务层默认值：mode=公路 / paymentDays=30 / startDate=今天 / endDate=+180 天 / contact·phone=发货方 / remark=空）
    private String mode;
    private Integer paymentDays;
    private String startDate;
    private String endDate;
    private String contact;
    private String phone;
    private String remark;
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
