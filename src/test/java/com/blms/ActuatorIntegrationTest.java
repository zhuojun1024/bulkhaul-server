package com.blms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * C3 可观测性：actuator /actuator/health（公开，UP）+ /actuator/metrics（已暴露，未认证 401 受保护）。
 * 独立 MockMvc 类，不依赖 FlowIntegrationTest 的 12 环节顺序流。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void actuatorHealthUp() throws Exception {
        var r = mvc.perform(get("/actuator/health")).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(200, r.getResponse().getStatus(), "health 公开 200: " + body);
        assertTrue(body.contains("\"status\":\"UP\""), "health UP: " + body);
    }

    @Test
    void actuatorInfoExposed() throws Exception {
        var r = mvc.perform(get("/actuator/info")).andReturn();
        assertEquals(200, r.getResponse().getStatus(), "info 公开 200: " + r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void actuatorMetricsProtected() throws Exception {
        // metrics 已暴露（非 404）但未认证 → 401（受保护，已认证可抓取，见 E2E）
        var r = mvc.perform(get("/actuator/metrics")).andReturn();
        assertEquals(401, r.getResponse().getStatus(), "metrics 未认证 401（受保护）: " + r.getResponse().getStatus());
    }
}
