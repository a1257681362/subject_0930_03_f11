package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.AppraisalCorrectRequest;
import com.evops.dto.AppraisalSubmitRequest;
import com.evops.dto.AppraisalWithdrawRequest;
import com.evops.dto.ArbitrationResolveRequest;
import com.evops.entity.AppraisalArbitration;
import com.evops.entity.AppraisalAudit;
import com.evops.entity.AppraisalCorrection;
import com.evops.entity.AppraisalOpinion;
import com.evops.service.AppraisalAuditService;
import com.evops.service.AppraisalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 文物鉴定意见：初鉴/复鉴提交、撤回、发布、追加更正、仲裁裁决；
 * 查看按岗位分级（普通馆员仅结论摘要），详细报告下载记审计流水。
 */
@RestController
@RequestMapping("/api/appraisals")
public class AppraisalController {

    private final AppraisalService appraisalService;
    private final AppraisalAuditService auditService;

    public AppraisalController(AppraisalService appraisalService,
                               AppraisalAuditService auditService) {
        this.appraisalService = appraisalService;
        this.auditService = auditService;
    }

    /** 提交初鉴/复鉴意见（专家、依据、置信说明、附件）；同轮次已有在途意见时拒绝并留审计 */
    @PostMapping
    public ApiResponse<AppraisalOpinion> submit(@Valid @RequestBody AppraisalSubmitRequest request) {
        return ApiResponse.ok(appraisalService.submit(request));
    }

    /** 撤回未发布意见（仅本专家）；已发布意见只能追加更正 */
    @PostMapping("/{id}/withdraw")
    public ApiResponse<AppraisalOpinion> withdraw(@PathVariable Long id,
                                                  @Valid @RequestBody AppraisalWithdrawRequest request) {
        return ApiResponse.ok(appraisalService.withdraw(id, request));
    }

    /** 发布意见（仅鉴定负责人）；发布后不可撤回、不可改写 */
    @PostMapping("/{id}/publish")
    public ApiResponse<AppraisalOpinion> publish(@PathVariable Long id,
                                                 @RequestParam String operator,
                                                 @RequestParam(required = false) String role) {
        return ApiResponse.ok(appraisalService.publish(id, operator, role));
    }

    /** 已发布意见追加更正（只追加，原文不改写） */
    @PostMapping("/{id}/corrections")
    public ApiResponse<AppraisalCorrection> correct(@PathVariable Long id,
                                                    @Valid @RequestBody AppraisalCorrectRequest request) {
        return ApiResponse.ok(appraisalService.correct(id, request));
    }

    /** 仲裁裁决（仅鉴定负责人）；裁决只落案件结论，两条意见原文保留 */
    @PostMapping("/arbitrations/{caseId}/resolve")
    public ApiResponse<AppraisalArbitration> resolve(@PathVariable Long caseId,
                                                     @Valid @RequestBody ArbitrationResolveRequest request,
                                                     @RequestParam(required = false) String role) {
        return ApiResponse.ok(appraisalService.resolveArbitration(caseId, request, role));
    }

    /** 仲裁案件列表（可按状态过滤） */
    @GetMapping("/arbitrations")
    public ApiResponse<List<AppraisalArbitration>> arbitrations(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(appraisalService.listArbitrations(status));
    }

    /** 按岗位查看：鉴定专家/负责人见全文，普通馆员仅见结论摘要 */
    @GetMapping("/relics/{relicId}")
    public ApiResponse<Map<String, Object>> view(@PathVariable Long relicId,
                                                 @RequestParam(required = false) String role) {
        return ApiResponse.ok(appraisalService.viewForRelic(relicId, role));
    }

    /** 下载详细报告（仅专家/负责人），每次下载写审计流水 */
    @GetMapping("/relics/{relicId}/report")
    public ApiResponse<Map<String, Object>> report(@PathVariable Long relicId,
                                                   @RequestParam(required = false) String role,
                                                   @RequestParam(defaultValue = "UNKNOWN") String operator) {
        return ApiResponse.ok(appraisalService.downloadReport(relicId, role, operator));
    }

    /** 鉴定审计流水（提交/撤回/发布/更正/仲裁/下载/并发拒绝） */
    @GetMapping("/relics/{relicId}/audit")
    public ApiResponse<List<AppraisalAudit>> audit(@PathVariable Long relicId) {
        return ApiResponse.ok(auditService.listByRelic(relicId));
    }
}
