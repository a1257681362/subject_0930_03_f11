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
 * 文物修复同一操作并发发起验收（museum-11）：
 * 1) 8 并发开工同一修复项目：只有 1 个生效，其余被拒绝；
 * 2) 8 并发作废同一修复项目：只有 1 个生效；
 * 被拒绝者收到"谁生效、谁被拒绝"的说明，且台账保留 REPAIR_OP_REJECT 审计。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-repair-op-concurrent;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairOperationConcurrentTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static final class Outcome {
        boolean success;
        String message;
    }

    @Test
    void concurrent_repair_operations_single_winner_with_audit() throws Exception {
        long relicId = dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"R-C01\",\"name\":\"并发修复陶俑\",\"category\":\"陶器\","
                + "\"material\":\"陶\",\"era\":\"唐代\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"ONE\"}")).get("id").asLong();

        // ---- 场景 1：8 并发开工 → 恰好 1 个生效 ----
        long projectId = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-C01\",\"relicId\":" + relicId
                + ",\"client\":\"藏品部\",\"plan\":\"清洗加固\"}")).get("id").asLong();
        approvePlan(projectId);

        List<Outcome> starts = parallel(8, i ->
                postEmpty("/api/repair-projects/" + projectId + "/start?operator=op" + i));
        int startOk = 0;
        for (Outcome o : starts) {
            if (o.success) {
                startOk++;
            } else {
                assertTrue(o.message.contains("未生效") && o.message.contains("拒绝"),
                        "拒绝信息应说明谁生效、谁被拒绝，实际: " + o.message);
            }
        }
        assertEquals(1, startOk, "并发开工只能生效一个");

        JsonNode ledger = dataOf(getJson("/api/relics/" + relicId + "/ledger"));
        assertEquals(1, countAction(ledger, "REPAIR_START"), "开工台账只有一条");
        assertEquals(7, countAction(ledger, "REPAIR_OP_REJECT"),
                "7 个被拒绝的开工都要留台账审计");
        for (JsonNode e : ledger) {
            if ("REPAIR_OP_REJECT".equals(e.get("action").asText())) {
                assertTrue(e.get("summary").asText().contains("先生效")
                                && e.get("summary").asText().contains("未生效"),
                        "拒绝台账应说明谁生效、谁被拒绝: " + e.get("summary").asText());
            }
        }

        // ---- 场景 2：8 并发作废 → 恰好 1 个生效 ----
        long project2 = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-C02\",\"relicId\":" + relicId
                + ",\"client\":\"藏品部\",\"plan\":\"暂缓修复\"}")).get("id").asLong();
        List<Outcome> cancels = parallel(8, i -> postJson("/api/repair-projects/" + project2
                + "/cancel", "{\"reason\":\"计划调整" + i + "\",\"operator\":\"op" + i + "\"}"));
        int cancelOk = 0;
        for (Outcome o : cancels) {
            if (o.success) {
                cancelOk++;
            } else {
                assertTrue(o.message.contains("未生效") && o.message.contains("拒绝"),
                        "拒绝信息应说明谁生效、谁被拒绝，实际: " + o.message);
            }
        }
        assertEquals(1, cancelOk, "并发作废只能生效一个");

        JsonNode ledger2 = dataOf(getJson("/api/relics/" + relicId + "/ledger"));
        assertEquals(1, countAction(ledger2, "CANCEL"), "作废台账只有一条");
        assertEquals(7 + 7, countAction(ledger2, "REPAIR_OP_REJECT"),
                "开工 7 条 + 作废 7 条拒绝审计");
    }

    // ------------------------------------------------------------------

    /** 提交方案版本并按一级文物顺序会签通过（外部专家 -> 馆内专家 -> 保管人员） */
    private void approvePlan(long projectId) throws Exception {
        long versionId = dataOf(postJson("/api/repair-projects/" + projectId + "/plan-versions",
                "{\"planContent\":\"清洗后加固\",\"internalExpert\":\"馆内专家甲\","
                        + "\"externalExpert\":\"外部专家乙\",\"custodian\":\"保管员丙\","
                        + "\"submittedBy\":\"项目负责人\"}")).get("version").get("id").asLong();
        sign(versionId, "EXTERNAL", "外部专家乙");
        sign(versionId, "INTERNAL", "馆内专家甲");
        sign(versionId, "CUSTODIAN", "保管员丙");
    }

    private void sign(long versionId, String role, String name) throws Exception {
        mockMvc.perform(post("/api/plan-versions/" + versionId + "/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signerRole\":\"" + role + "\",\"signerName\":\"" + name
                                + "\",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());
    }

    private int countAction(JsonNode ledger, String action) {
        int count = 0;
        for (JsonNode e : ledger) {
            if (action.equals(e.get("action").asText())) {
                count++;
            }
        }
        return count;
    }

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
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON))
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
