package com.blms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A5 输入校验（@Valid DTO 字段级约束 → 400 + 字段级错误）。
 * 独立于 FlowIntegrationTest 的 12 环节顺序流：仅 POST 非法/合法 body 验证 400 契约，不改共享内存态
 * （非法 body 在 @Valid 层即被拦截，不进 service；合法 body 占位 ID 在 service 引用校验处返回错误，不 commit）。
 * 契约：已认证用户 + 非法 body → 400 validation_error（data.fieldErrors 字段级）；匿名 → 401（认证层，非 A5 范畴）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValidationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String token;

    @BeforeAll
    void login() throws Exception {
        // captcha → login（admin）
        MvcResult cap = mvc.perform(get("/api/auth/captcha")).andReturn();
        JsonNode capNode = om.readTree(cap.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        MvcResult lr = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(java.util.Map.of(
                        "username", "admin", "password", "123456",
                        "captchaId", capNode.get("id").asText(),
                        "captchaCode", capNode.get("code").asText())))).andReturn();
        assertEquals(200, lr.getResponse().getStatus(), "admin 登录应成功: " + lr.getResponse().getContentAsString(StandardCharsets.UTF_8));
        token = om.readTree(lr.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data").get("token").asText();
        assertNotNull(token, "token 非空");
    }

    private String postJson(String path, String json) throws Exception {
        MvcResult r = mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        assertEquals(400, r.getResponse().getStatus(), "缺必填/非法 → 400（实际 " + r.getResponse().getStatus() + "）: " + r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":\"validation_error\""), "code=validation_error: " + body);
        assertTrue(body.contains("\"fieldErrors\""), "带 fieldErrors 对象: " + body);
        return body;
    }

    @Test
    void createContract_missingRequired() throws Exception {
        // 缺 name + quantity（其余必填给占位值）
        String body = postJson("/api/contract",
                "{\"shipperId\":\"C1\",\"consigneeId\":\"C2\",\"commodityId\":\"M1\",\"loadTerminalId\":\"T1\",\"unloadTerminalId\":\"T2\",\"unitPrice\":100}");
        assertTrue(body.contains("\"name\":\"合同名称不能为空\""), "字段级错误 name: " + body);
        assertTrue(body.contains("\"quantity\":\"计划数量不能为空\""), "字段级错误 quantity: " + body);
    }

    @Test
    void createContract_quantityNotPositive() throws Exception {
        // quantity=0（@Positive 失败）
        String body = postJson("/api/contract",
                "{\"name\":\"测试合同\",\"shipperId\":\"C1\",\"consigneeId\":\"C2\",\"commodityId\":\"M1\",\"loadTerminalId\":\"T1\",\"unloadTerminalId\":\"T2\",\"quantity\":0,\"unitPrice\":100}");
        assertTrue(body.contains("\"quantity\":\"计划数量须大于 0\""), "字段级错误 quantity 须>0: " + body);
    }

    @Test
    void createPlan_missingContract() throws Exception {
        // 缺 contractId
        String body = postJson("/api/plan", "{\"quantity\":10}");
        assertTrue(body.contains("\"contractId\":\"请选择合同\""), "字段级错误 contractId: " + body);
    }

    @Test
    void createDispatches_missingPlan() throws Exception {
        // 缺 planId
        String body = postJson("/api/dispatch/create", "{\"count\":2}");
        assertTrue(body.contains("\"planId\":\"请选择运输计划\""), "字段级错误 planId: " + body);
    }

    @Test
    void createDispatches_countBelowMin() throws Exception {
        // count=0（@Min(1) 失败）
        String body = postJson("/api/dispatch/create", "{\"planId\":\"YH-1\",\"count\":0}");
        assertTrue(body.contains("\"count\":\"车次数量须至少 1\""), "字段级错误 count: " + body);
    }

    @Test
    void createContract_allRequiredPresent_passesValidation() throws Exception {
        // 必填齐全（占位 ID，校验只查存在/正数，不查引用完整性）→ 通过 @Valid，进入业务逻辑（非 validation_error）
        MvcResult r = mvc.perform(post("/api/contract").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"测试合同\",\"shipperId\":\"C1\",\"consigneeId\":\"C2\",\"commodityId\":\"M1\",\"loadTerminalId\":\"T1\",\"unloadTerminalId\":\"T2\",\"quantity\":10,\"unitPrice\":100}"))
                .andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNotEquals(400, r.getResponse().getStatus(), "必填齐全不应 400: " + body);
        assertFalse(body.contains("\"code\":\"validation_error\""), "通过 @Valid 后非 validation_error: " + body);
    }
}
