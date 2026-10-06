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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物鉴定意见（museum-11）全链路验收：
 * 1) 初鉴/复鉴分别保存专家、依据、置信说明与附件；
 * 2) 初鉴与复鉴结论冲突时进入仲裁状态，两条意见原样保留，不取最后一条覆盖前文；
 * 3) 未发布意见可由本专家撤回；已发布意见不能撤回，只能追加更正（原文不改写）；
 * 4) 普通馆员仅能看到结论摘要，专家/负责人见全文；
 * 5) 详细报告每次下载（含被拒绝的尝试）都记审计流水。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-appraisal-flow;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppraisalFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void appraisal_lifecycle_rules_hold() throws Exception {
        long relicId = createRelic("{"
                + "\"relicNo\":\"A-001\",\"name\":\"鉴定测试瓷瓶\",\"category\":\"瓷器\","
                + "\"material\":\"瓷\",\"era\":\"清代\",\"source\":\"征集\","
                + "\"preservationGrade\":\"TWO\"}");

        // ---- 1. 初鉴：专家/依据/置信说明/附件一并保存 ----
        long initialId = dataOf(postJson("/api/appraisals", "{"
                + "\"relicId\":" + relicId + ",\"round\":\"INITIAL\","
                + "\"expertName\":\"专家甲\",\"conclusion\":\"AUTHENTIC\","
                + "\"basis\":\"器型、胎釉与款识符合清代官窑特征\","
                + "\"confidenceNote\":\"高置信：三项特征互证\","
                + "\"attachments\":["
                + "{\"fileName\":\"胎釉显微照.pdf\",\"storagePath\":\"appr/a001/micro.pdf\",\"contentType\":\"application/pdf\",\"fileSize\":1024},"
                + "{\"fileName\":\"款识拓片.jpg\",\"storagePath\":\"appr/a001/mark.jpg\",\"contentType\":\"image/jpeg\",\"fileSize\":2048}"
                + "]}")).get("id").asLong();
        JsonNode full = dataOf(getJson("/api/appraisals/relics/" + relicId + "?role=APPRAISAL_EXPERT"));
        assertEquals("FULL", full.get("visible").asText());
        JsonNode initialView = full.get("opinions").get(0);
        assertEquals("专家甲", initialView.get("opinion").get("expertName").asText());
        assertEquals("器型、胎釉与款识符合清代官窑特征", initialView.get("opinion").get("basis").asText());
        assertEquals("高置信：三项特征互证", initialView.get("opinion").get("confidenceNote").asText());
        assertEquals(2, initialView.get("attachments").size());
        assertEquals("胎釉显微照.pdf", initialView.get("attachments").get(0).get("fileName").asText());

        // 同一轮次重复提交：被拒绝并说明谁已生效
        expectFail(postJson("/api/appraisals", "{"
                + "\"relicId\":" + relicId + ",\"round\":\"INITIAL\","
                + "\"expertName\":\"专家乙\",\"conclusion\":\"FAKE\","
                + "\"basis\":\"釉面有贼光\",\"confidenceNote\":\"中置信\"}"), "生效");

        // ---- 2. 复鉴结论一致：不进仲裁 ----
        long reviewId1 = dataOf(postJson("/api/appraisals", "{"
                + "\"relicId\":" + relicId + ",\"round\":\"REVIEW\","
                + "\"expertName\":\"专家乙\",\"conclusion\":\"AUTHENTIC\","
                + "\"basis\":\"复核同意初鉴\",\"confidenceNote\":\"高置信\"}")).get("id").asLong();
        assertEquals(0, dataOf(getJson("/api/appraisals/arbitrations?status=ARBITRATING")).size());

        // 撤回：非本专家拒绝；本专家撤回成功，槽位释放可再提交
        expectFail(postJson("/api/appraisals/" + reviewId1 + "/withdraw",
                "{\"expertName\":\"专家丙\",\"reason\":\"想撤回\"}"), "只能撤回本人");
        expectOk(postJson("/api/appraisals/" + reviewId1 + "/withdraw",
                "{\"expertName\":\"专家乙\",\"reason\":\"需补充检测\"}"));

        // ---- 3. 复鉴结论冲突：进入仲裁，两条意见原样保留 ----
        long reviewId2 = dataOf(postJson("/api/appraisals", "{"
                + "\"relicId\":" + relicId + ",\"round\":\"REVIEW\","
                + "\"expertName\":\"专家乙\",\"conclusion\":\"FAKE\","
                + "\"basis\":\"热释光检测数据与清代不符\","
                + "\"confidenceNote\":\"中高置信：单一检测手段\"}")).get("id").asLong();
        JsonNode openCases = dataOf(getJson("/api/appraisals/arbitrations?status=ARBITRATING"));
        assertEquals(1, openCases.size(), "结论冲突必须进入仲裁状态");
        long caseId = openCases.get(0).get("id").asLong();

        // 不取最后一条覆盖前文：初鉴 AUTHENTIC、已撤回复鉴、在途复鉴 FAKE 都还在
        JsonNode both = dataOf(getJson("/api/appraisals/relics/" + relicId + "?role=APPRAISAL_EXPERT"))
                .get("opinions");
        assertEquals("AUTHENTIC", both.get(0).get("opinion").get("conclusion").asText());
        assertEquals("WITHDRAWN", both.get(1).get("opinion").get("status").asText(),
                "撤回意见保留记录，不被覆盖");
        assertEquals("FAKE", both.get(2).get("opinion").get("conclusion").asText());

        // 复鉴在途时初鉴不能撤回
        expectFail(postJson("/api/appraisals/" + initialId + "/withdraw",
                "{\"expertName\":\"专家甲\",\"reason\":\"想撤回\"}"), "复鉴意见在途");

        // 复鉴撤回 → 冲突消失，仲裁自动关闭
        expectOk(postJson("/api/appraisals/" + reviewId2 + "/withdraw",
                "{\"expertName\":\"专家乙\",\"reason\":\"检测样本不足\"}"));
        assertEquals("CLOSED", dataOf(getJson("/api/appraisals/arbitrations")).get(0)
                .get("status").asText());

        // 再次提交冲突复鉴 → 新仲裁案件
        dataOf(postJson("/api/appraisals", "{"
                + "\"relicId\":" + relicId + ",\"round\":\"REVIEW\","
                + "\"expertName\":\"专家丙\",\"conclusion\":\"UNCERTAIN\","
                + "\"basis\":\"现有证据不足以定真\",\"confidenceNote\":\"低置信\"}"));
        JsonNode openCases2 = dataOf(getJson("/api/appraisals/arbitrations?status=ARBITRATING"));
        assertEquals(1, openCases2.size());
        long caseId2 = openCases2.get(0).get("id").asLong();

        // ---- 4. 发布与更正：仅负责人可发布；已发布只能追加更正 ----
        expectFail(postJson("/api/appraisals/" + initialId + "/publish?operator=expert-jia&role=APPRAISAL_EXPERT"
                , null), "仅鉴定负责人");
        expectOk(postJson("/api/appraisals/" + initialId + "/publish?operator=leader-ding&role=APPRAISAL_LEADER",
                null));
        expectFail(postJson("/api/appraisals/" + initialId + "/withdraw",
                "{\"expertName\":\"专家甲\",\"reason\":\"想撤回\"}"), "已发布意见不能撤回");
        expectOk(postJson("/api/appraisals/" + initialId + "/corrections",
                "{\"content\":\"补充：底款为后加款的可能已排除\",\"correctedBy\":\"专家甲\"}"));
        expectOk(postJson("/api/appraisals/" + initialId + "/corrections",
                "{\"content\":\"补充：热释光复测在误差范围内\",\"correctedBy\":\"专家甲\"}"));
        JsonNode afterCorrect = dataOf(getJson("/api/appraisals/relics/" + relicId
                + "?role=APPRAISAL_EXPERT")).get("opinions").get(0);
        assertEquals(2, afterCorrect.get("corrections").size(), "更正逐条追加");
        assertEquals("器型、胎釉与款识符合清代官窑特征",
                afterCorrect.get("opinion").get("basis").asText(), "追加更正不改写原文");

        // ---- 5. 岗位可见性：普通馆员仅结论摘要 ----
        JsonNode summary = dataOf(getJson("/api/appraisals/relics/" + relicId + "?role=CURATOR"));
        assertEquals("SUMMARY", summary.get("visible").asText());
        JsonNode summaryOpinion = summary.get("opinions").get(0);
        assertEquals("AUTHENTIC", summaryOpinion.get("conclusion").asText());
        assertFalse(summaryOpinion.has("basis"), "摘要不得含鉴定依据");
        assertFalse(summaryOpinion.has("expertName"), "摘要不得含专家");
        assertFalse(summaryOpinion.has("confidenceNote"), "摘要不得含置信说明");
        assertFalse(summary.has("relic") && summary.get("relic").has("description")
                && !summary.get("relic").get("description").isNull(), "摘要不含档案全文");

        // ---- 6. 详细报告下载：馆员被拒并留审计，专家下载留审计 ----
        expectFail(getJson("/api/appraisals/relics/" + relicId + "/report?role=CURATOR&operator=curator-wang"),
                "仅可查看结论摘要");
        JsonNode report = dataOf(getJson("/api/appraisals/relics/" + relicId
                + "/report?role=APPRAISAL_EXPERT&operator=expert-jia"));
        assertEquals("APR-A-001", report.get("reportNo").asText());
        assertEquals("FULL", report.get("visible").asText());

        // ---- 7. 仲裁裁决：只落案件结论，意见原文保留 ----
        expectOk(postJson("/api/appraisals/arbitrations/" + caseId2 + "/resolve?role=APPRAISAL_LEADER",
                "{\"finalConclusion\":\"AUTHENTIC\",\"rulingNote\":\"热释光复测支持真品，存疑意见采纳为参考\","
                        + "\"arbitrator\":\"负责人丁\"}"));
        JsonNode resolved = dataOf(getJson("/api/appraisals/arbitrations?status=RESOLVED")).get(0);
        assertEquals("AUTHENTIC", resolved.get("finalConclusion").asText());
        JsonNode opinionsAfterResolve = dataOf(getJson("/api/appraisals/relics/" + relicId
                + "?role=APPRAISAL_LEADER")).get("opinions");
        assertEquals("AUTHENTIC", opinionsAfterResolve.get(0).get("opinion").get("conclusion").asText());
        assertEquals("UNCERTAIN", opinionsAfterResolve.get(3).get("opinion").get("conclusion").asText(),
                "裁决不改写任何一条意见原文（含已撤回记录，共 4 条全保留）");
        assertEquals(4, opinionsAfterResolve.size());

        // ---- 8. 审计流水：全动作留痕 ----
        JsonNode audit = dataOf(getJson("/api/appraisals/relics/" + relicId + "/audit"));
        assertTrue(hasAction(audit, "APPRAISAL_SUBMIT"), "提交留痕");
        assertTrue(hasAction(audit, "APPRAISAL_WITHDRAW"), "撤回留痕");
        assertTrue(hasAction(audit, "APPRAISAL_PUBLISH"), "发布留痕");
        assertTrue(hasAction(audit, "APPRAISAL_CORRECT"), "更正留痕");
        assertTrue(hasAction(audit, "ARBITRATION_OPEN"), "仲裁开立留痕");
        assertTrue(hasAction(audit, "ARBITRATION_CLOSE"), "仲裁关闭留痕");
        assertTrue(hasAction(audit, "ARBITRATION_RESOLVE"), "仲裁裁决留痕");
        assertTrue(hasAction(audit, "APPRAISAL_DOWNLOAD"), "报告下载留痕");
        assertTrue(hasAction(audit, "APPRAISAL_REJECT"), "拒绝留痕");
    }

    // ------------------------------------------------------------------

    private boolean hasAction(JsonNode audit, String action) {
        for (JsonNode e : audit) {
            if (action.equals(e.get("action").asText())) {
                return true;
            }
        }
        return false;
    }

    private long createRelic(String body) throws Exception {
        return dataOf(postJson("/api/relics", body)).get("id").asLong();
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON)
                        .content(body == null ? "{}" : body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(), "应成功: " + root.get("message").asText());
    }

    private void expectFail(MvcResult result, String messagePart) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean(), "应失败");
        assertTrue(root.get("message").asText().contains(messagePart),
                "失败信息应包含【" + messagePart + "】，实际: " + root.get("message").asText());
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("data");
    }
}
