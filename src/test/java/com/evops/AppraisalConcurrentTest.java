package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物鉴定意见并发验收（museum-11）：
 * 1) 8 并发提交同一文物的初鉴：只有 1 条生效，其余被拒绝并留审计，
 *    拒绝信息说明谁生效、谁被拒绝；
 * 2) 8 并发发布同一条意见：只有 1 个生效；
 * 3) 8 并发裁决同一仲裁案件：只有 1 个生效。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-appraisal-concurrent;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppraisalConcurrentTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static final class Outcome {
        boolean success;
        String message;
        Long id;
    }

    @Test
    void concurrent_same_operation_single_winner_with_audit() throws Exception {
        long relicId = dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"A-C01\",\"name\":\"并发鉴定青铜镜\",\"category\":\"青铜器\","
                + "\"material\":\"青铜\",\"era\":\"汉代\",\"source\":\"移交\","
                + "\"preservationGrade\":\"TWO\"}")).get("id").asLong();

        // ---- 场景 1：8 并发提交同一文物初鉴 → 恰好 1 条生效 ----
        List<Outcome> submits = parallel(8, i -> postJson("/api/appraisals", "{"
                + "\"relicId\":" + relicId + ",\"round\":\"INITIAL\","
                + "\"expertName\":\"并发专家" + i + "\",\"conclusion\":\"AUTHENTIC\","
                + "\"basis\":\"并发提交依据" + i + "\",\"confidenceNote\":\"置信说明" + i + "\"}"));
        int submitOk = 0;
        for (Outcome o : submits) {
            if (o.success) {
                submitOk++;
            } else {
                assertTrue(o.message.contains("生效") && o.message.contains("拒绝"),
                        "拒绝信息应说明谁生效、谁被拒绝，实际: " + o.message);
            }
        }
        assertEquals(1, submitOk, "同一文物同一轮次并发提交只能生效一条");

        JsonNode audit1 = dataOf(getJson("/api/appraisals/relics/" + relicId + "/audit"));
        assertEquals(1, countAction(audit1, "APPRAISAL_SUBMIT"));
        assertEquals(7, countAction(audit1, "APPRAISAL_REJECT"),
                "7 个被拒绝的提交都要留审计");
        for (JsonNode e : audit1) {
            if ("APPRAISAL_REJECT".equals(e.get("action").asText())) {
                assertTrue(e.get("detail").asText().contains("已生效")
                                && e.get("detail").asText().contains("未生效"),
                        "拒绝审计应说明谁生效、谁被拒绝: " + e.get("detail").asText());
            }
        }

        // 生效意见 id（并发下不确定是哪一位专家，从全文视图取）
        JsonNode opinions = dataOf(getJson("/api/appraisals/relics/" + relicId
                + "?role=APPRAISAL_EXPERT")).get("opinions");
        assertEquals(1, opinions.size(), "只应形成一条意见");
        long opinionId = opinions.get(0).get("opinion").get("id").asLong();

        // ---- 场景 2：8 并发发布同一条意见 → 恰好 1 个生效 ----
        List<Outcome> publishes = parallel(8, i -> postJson("/api/appraisals/" + opinionId
                + "/publish?operator=leader" + i + "&role=APPRAISAL_LEADER", null));
        int publishOk = 0;
        for (Outcome o : publishes) {
            if (o.success) {
                publishOk++;
            }
        }
        assertEquals(1, publishOk, "并发发布只能生效一个");
        JsonNode audit2 = dataOf(getJson("/api/appraisals/relics/" + relicId + "/audit"));
        assertEquals(1, countAction(audit2, "APPRAISAL_PUBLISH"));

        // ---- 场景 3：8 并发裁决同一仲裁案件 → 恰好 1 个生效 ----
        dataOf(postJson("/api/appraisals", "{"
                + "\"relicId\":" + relicId + ",\"round\":\"REVIEW\","
                + "\"expertName\":\"复审专家\",\"conclusion\":\"FAKE\","
                + "\"basis\":\"复鉴与初鉴结论冲突\",\"confidenceNote\":\"中置信\"}"));
        long caseId = dataOf(getJson("/api/appraisals/arbitrations?status=ARBITRATING"))
                .get(0).get("id").asLong();
        List<Outcome> resolves = parallel(8, i -> postJson("/api/appraisals/arbitrations/" + caseId
                        + "/resolve?role=APPRAISAL_LEADER",
                "{\"finalConclusion\":\"AUTHENTIC\",\"rulingNote\":\"并发裁决" + i + "\","
                        + "\"arbitrator\":\"arb" + i + "\"}"));
        int resolveOk = 0;
        for (Outcome o : resolves) {
            if (o.success) {
                resolveOk++;
            } else {
                assertTrue(o.message.contains("已生效") || o.message.contains("裁决生效"),
                        "被拒绝的裁决应说明谁已生效，实际: " + o.message);
            }
        }
        assertEquals(1, resolveOk, "并发裁决只能生效一个");
        JsonNode audit3 = dataOf(getJson("/api/appraisals/relics/" + relicId + "/audit"));
        assertEquals(1, countAction(audit3, "ARBITRATION_RESOLVE"));
        assertEquals("RESOLVED", dataOf(getJson("/api/appraisals/arbitrations"))
                .get(0).get("status").asText());
    }

    // ------------------------------------------------------------------

    private int countAction(JsonNode audit, String action) {
        int count = 0;
        for (JsonNode e : audit) {
            if (action.equals(e.get("action").asText())) {
                count++;
            }
        }
        return count;
    }

    /** 同一 URI 模板并发发起，收集每次调用的成败与返回信息 */
    private List<Outcome> parallel(int threads, ThrowingCall call) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                MvcResult result = call.run(idx);
                JsonNode root = objectMapper.readTree(result.getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                Outcome o = new Outcome();
                o.success = root.get("success").asBoolean();
                o.message = root.get("message").asText();
                if (o.success && root.get("data") != null && root.get("data").has("id")) {
                    o.id = root.get("data").get("id").asLong();
                }
                return o;
            }));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private interface ThrowingCall {
        MvcResult run(int index) throws Exception;
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON)
                        .content(body == null ? "{}" : body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("data");
    }
}
