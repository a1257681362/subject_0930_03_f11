package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.AppraisalCorrectRequest;
import com.evops.dto.AppraisalSubmitRequest;
import com.evops.dto.AppraisalWithdrawRequest;
import com.evops.dto.ArbitrationResolveRequest;
import com.evops.entity.AppraisalArbitration;
import com.evops.entity.AppraisalAttachment;
import com.evops.entity.AppraisalCorrection;
import com.evops.entity.AppraisalOpinion;
import com.evops.entity.Relic;
import com.evops.mapper.AppraisalArbitrationMapper;
import com.evops.mapper.AppraisalAttachmentMapper;
import com.evops.mapper.AppraisalCorrectionMapper;
import com.evops.mapper.AppraisalOpinionMapper;
import com.evops.mapper.RelicMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物鉴定意见：
 * <ul>
 *   <li>初鉴/复鉴分别保存专家、依据、置信说明与附件，提交后原文不改写。</li>
 *   <li>同一文物同一轮次只允许一条在途意见：文物行锁串行化并发，
 *       active_key 稀疏唯一约束兜底；后到者被拒绝并写审计，
 *       说明谁生效、谁被拒绝。</li>
 *   <li>初鉴与复鉴结论冲突时开立仲裁案件，两条意见原样保留，
 *       绝不取最后一条覆盖前文；裁决只落案件结论。</li>
 *   <li>未发布意见可由本专家撤回；已发布意见只能追加更正。</li>
 *   <li>普通馆员仅见结论摘要；详细报告下载逐次写审计流水。</li>
 * </ul>
 */
@Service
public class AppraisalService {

    private static final List<String> ROUNDS = Arrays.asList(ROUND_INITIAL, ROUND_REVIEW);
    private static final List<String> CONCLUSIONS =
            Arrays.asList(CONC_AUTHENTIC, CONC_FAKE, CONC_UNCERTAIN);
    private static final List<String> PRIVILEGED_ROLES =
            Arrays.asList(ROLE_APPRAISAL_EXPERT, ROLE_APPRAISAL_LEADER);

    private final AppraisalOpinionMapper opinionMapper;
    private final AppraisalAttachmentMapper attachmentMapper;
    private final AppraisalCorrectionMapper correctionMapper;
    private final AppraisalArbitrationMapper arbitrationMapper;
    private final RelicMapper relicMapper;
    private final RelicService relicService;
    private final AppraisalAuditService auditService;

    public AppraisalService(AppraisalOpinionMapper opinionMapper,
                            AppraisalAttachmentMapper attachmentMapper,
                            AppraisalCorrectionMapper correctionMapper,
                            AppraisalArbitrationMapper arbitrationMapper,
                            RelicMapper relicMapper,
                            RelicService relicService,
                            AppraisalAuditService auditService) {
        this.opinionMapper = opinionMapper;
        this.attachmentMapper = attachmentMapper;
        this.correctionMapper = correctionMapper;
        this.arbitrationMapper = arbitrationMapper;
        this.relicMapper = relicMapper;
        this.relicService = relicService;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------
    // 提交鉴定意见
    // ------------------------------------------------------------------

    /**
     * 提交初鉴/复鉴意见。同一文物同一轮次只生效一条在途意见：
     * 并发或重复提交时，先到者生效，后到者被拒绝并留审计（说明谁生效、谁被拒绝）。
     */
    @Transactional
    public AppraisalOpinion submit(AppraisalSubmitRequest req) {
        Relic relic = relicService.requireActiveRelic(req.getRelicId());
        if (!ROUNDS.contains(req.getRound())) {
            throw new IllegalArgumentException("鉴定轮次只能为 INITIAL（初鉴）/ REVIEW（复鉴）");
        }
        if (!CONCLUSIONS.contains(req.getConclusion())) {
            throw new IllegalArgumentException("鉴定结论只能为 AUTHENTIC/FAKE/UNCERTAIN");
        }

        // 文物行锁：同一文物的并发提交在此串行化
        relicMapper.lockRow(relic.getId());

        // 锁内复查：同一轮次已有在途意见 → 本次被拒绝，留审计
        AppraisalOpinion active = findActive(relic.getId(), req.getRound());
        if (active != null) {
            throw duplicateSubmit(relic.getId(), req, active);
        }

        AppraisalOpinion initial = null;
        if (ROUND_REVIEW.equals(req.getRound())) {
            initial = findActive(relic.getId(), ROUND_INITIAL);
            if (initial == null) {
                throw new IllegalArgumentException("该文物尚未提交在途初鉴意见，不能复鉴");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        AppraisalOpinion opinion = new AppraisalOpinion();
        opinion.setOpinionNo(nextOpinionNo());
        opinion.setRelicId(relic.getId());
        opinion.setAppraisalRound(req.getRound());
        opinion.setExpertName(req.getExpertName());
        opinion.setConclusion(req.getConclusion());
        opinion.setBasis(req.getBasis());
        opinion.setConfidenceNote(req.getConfidenceNote());
        opinion.setStatus(OPINION_SUBMITTED);
        opinion.setActiveKey(activeKey(relic.getId(), req.getRound()));
        opinion.setSubmittedBy(req.getExpertName());
        opinion.setSubmittedAt(now);
        try {
            opinionMapper.insert(opinion);
        } catch (DuplicateKeyException e) {
            // 稀疏唯一约束兜底：理论上行锁已串行化，此处为最后一道防线
            throw duplicateSubmit(relic.getId(), req, findActive(relic.getId(), req.getRound()));
        }

        if (req.getAttachments() != null) {
            for (com.evops.dto.AppraisalAttachmentDto dto : req.getAttachments()) {
                AppraisalAttachment attachment = new AppraisalAttachment();
                attachment.setOpinionId(opinion.getId());
                attachment.setFileName(dto.getFileName());
                attachment.setStoragePath(dto.getStoragePath());
                attachment.setContentType(dto.getContentType());
                attachment.setFileSize(dto.getFileSize());
                attachment.setUploadedBy(req.getExpertName());
                attachment.setUploadedAt(now);
                attachmentMapper.insert(attachment);
            }
        }

        auditService.append(relic.getId(), opinion.getId(), null,
                ACTION_APPRAISAL_SUBMIT, req.getExpertName(), ROLE_APPRAISAL_EXPERT,
                roundLabel(req.getRound()) + "意见已提交（未发布），意见编号 " + opinion.getOpinionNo()
                        + "，结论 " + req.getConclusion() + "，附件 "
                        + (req.getAttachments() == null ? 0 : req.getAttachments().size()) + " 份");

        // 结论冲突：初鉴与复鉴原样保留，开立仲裁案件，绝不取最后一条覆盖前文
        if (initial != null && !initial.getConclusion().equals(opinion.getConclusion())) {
            openArbitration(relic, initial, opinion, now);
        }
        return opinion;
    }

    // ------------------------------------------------------------------
    // 撤回（仅未发布、仅本专家）
    // ------------------------------------------------------------------

    /**
     * 撤回未发布意见：仅 SUBMITTED 可撤回，且只能由提交该意见的本专家操作。
     * 已发布意见不能撤回，只能追加更正。复鉴撤回后，因冲突开立的仲裁案件自动关闭。
     */
    @Transactional
    public AppraisalOpinion withdraw(Long opinionId, AppraisalWithdrawRequest req) {
        AppraisalOpinion opinion = requireOpinion(opinionId);
        relicMapper.lockRow(opinion.getRelicId());
        opinion = requireOpinion(opinionId); // 锁内重读，拿到串行化后的最新状态

        if (!req.getExpertName().equals(opinion.getExpertName())) {
            throw new IllegalArgumentException("只能撤回本人提交的意见：该意见由专家 "
                    + opinion.getExpertName() + " 提交");
        }
        if (!OPINION_SUBMITTED.equals(opinion.getStatus())) {
            String detail = "撤回被拒绝：意见 " + opinion.getOpinionNo() + " 当前状态 "
                    + opinion.getStatus() + "（" + statusLabel(opinion) + "），本次撤回人 "
                    + req.getExpertName() + " 未生效";
            auditService.appendIsolated(opinion.getRelicId(), opinionId, null,
                    ACTION_APPRAISAL_REJECT, req.getExpertName(), ROLE_APPRAISAL_EXPERT, detail);
            throw new IllegalArgumentException(OPINION_PUBLISHED.equals(opinion.getStatus())
                    ? "已发布意见不能撤回，只能追加更正: " + opinion.getOpinionNo()
                    : "意见已撤回，不能重复操作: " + opinion.getOpinionNo());
        }
        if (ROUND_INITIAL.equals(opinion.getAppraisalRound())
                && findActive(opinion.getRelicId(), ROUND_REVIEW) != null) {
            throw new IllegalArgumentException("复鉴意见在途，初鉴不能撤回；请先由复鉴专家撤回复鉴");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = opinionMapper.withdrawIfSubmitted(opinionId, req.getExpertName(),
                req.getReason(), now);
        if (updated == 0) {
            // 行锁下理论不可达，兜底：并发方已改变状态
            AppraisalOpinion current = requireOpinion(opinionId);
            auditService.appendIsolated(current.getRelicId(), opinionId, null,
                    ACTION_APPRAISAL_REJECT, req.getExpertName(), ROLE_APPRAISAL_EXPERT,
                    "撤回被拒绝：意见当前状态 " + current.getStatus() + "，本次撤回未生效");
            throw new IllegalArgumentException("意见状态已变化，撤回未生效: " + opinion.getOpinionNo());
        }

        auditService.append(opinion.getRelicId(), opinionId, null,
                ACTION_APPRAISAL_WITHDRAW, req.getExpertName(), ROLE_APPRAISAL_EXPERT,
                roundLabel(opinion.getAppraisalRound()) + "意见已撤回（记录保留），原因：" + req.getReason());

        // 复鉴撤回 → 冲突消失，仲裁中的案件自动关闭（非裁决）
        if (ROUND_REVIEW.equals(opinion.getAppraisalRound())) {
            AppraisalArbitration openCase = findOpenArbitration(opinion.getRelicId());
            if (openCase != null && opinionId.equals(openCase.getReviewOpinionId())) {
                arbitrationMapper.closeIfArbitrating(openCase.getId(),
                        "复鉴意见已撤回，结论冲突消失，仲裁关闭", now);
                auditService.append(opinion.getRelicId(), null, openCase.getId(),
                        ACTION_ARBITRATION_CLOSE, req.getExpertName(), ROLE_APPRAISAL_EXPERT,
                        "复鉴意见撤回，仲裁案件 " + openCase.getCaseNo() + " 自动关闭");
            }
        }
        opinion.setStatus(OPINION_WITHDRAWN);
        return opinion;
    }

    // ------------------------------------------------------------------
    // 发布（仅鉴定负责人；发布后只能追加更正）
    // ------------------------------------------------------------------

    @Transactional
    public AppraisalOpinion publish(Long opinionId, String operator, String role) {
        requireLeader(role, "发布鉴定意见");
        AppraisalOpinion opinion = requireOpinion(opinionId);
        relicMapper.lockRow(opinion.getRelicId());
        opinion = requireOpinion(opinionId);

        if (!OPINION_SUBMITTED.equals(opinion.getStatus())) {
            String detail = "发布被拒绝：意见 " + opinion.getOpinionNo() + " 当前状态 "
                    + opinion.getStatus() + "（" + statusLabel(opinion) + "），本次发布人 "
                    + operator + " 未生效";
            auditService.appendIsolated(opinion.getRelicId(), opinionId, null,
                    ACTION_APPRAISAL_REJECT, operator, role, detail);
            throw new IllegalArgumentException("意见当前状态不允许发布: " + opinion.getStatus());
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = opinionMapper.publishIfSubmitted(opinionId, operator, now);
        if (updated == 0) {
            AppraisalOpinion current = requireOpinion(opinionId);
            auditService.appendIsolated(current.getRelicId(), opinionId, null,
                    ACTION_APPRAISAL_REJECT, operator, role,
                    "发布被拒绝：意见当前状态 " + current.getStatus() + "，本次发布未生效");
            throw new IllegalArgumentException("意见状态已变化，发布未生效: " + opinion.getOpinionNo());
        }
        auditService.append(opinion.getRelicId(), opinionId, null,
                ACTION_APPRAISAL_PUBLISH, operator, role,
                "意见已发布，此后只能追加更正，不能撤回或改写");
        opinion.setStatus(OPINION_PUBLISHED);
        return opinion;
    }

    // ------------------------------------------------------------------
    // 追加更正（仅已发布意见；只追加，不改写原文）
    // ------------------------------------------------------------------

    @Transactional
    public AppraisalCorrection correct(Long opinionId, AppraisalCorrectRequest req) {
        AppraisalOpinion opinion = requireOpinion(opinionId);
        if (!OPINION_PUBLISHED.equals(opinion.getStatus())) {
            throw new IllegalArgumentException(OPINION_SUBMITTED.equals(opinion.getStatus())
                    ? "未发布意见无需更正，可由本专家撤回后重新提交: " + opinion.getOpinionNo()
                    : "已撤回意见不能追加更正: " + opinion.getOpinionNo());
        }
        LocalDateTime now = LocalDateTime.now();
        AppraisalCorrection correction = new AppraisalCorrection();
        correction.setOpinionId(opinionId);
        correction.setContent(req.getContent());
        correction.setCorrectedBy(req.getCorrectedBy());
        correction.setCorrectedAt(now);
        correctionMapper.insert(correction);

        auditService.append(opinion.getRelicId(), opinionId, null,
                ACTION_APPRAISAL_CORRECT, req.getCorrectedBy(), ROLE_APPRAISAL_EXPERT,
                "已发布意见追加更正（原文不改写）：" + req.getContent());
        return correction;
    }

    // ------------------------------------------------------------------
    // 仲裁裁决
    // ------------------------------------------------------------------

    /**
     * 仲裁裁决：只落案件结论，初鉴/复鉴两条意见原文均不改写。
     * 并发裁决只有第一个生效，后到者被拒绝并留审计。
     */
    @Transactional
    public AppraisalArbitration resolveArbitration(Long caseId, ArbitrationResolveRequest req,
                                                   String role) {
        requireLeader(role, "仲裁裁决");
        if (!CONCLUSIONS.contains(req.getFinalConclusion())) {
            throw new IllegalArgumentException("裁决结论只能为 AUTHENTIC/FAKE/UNCERTAIN");
        }
        AppraisalArbitration arbitration = requireArbitration(caseId);
        relicMapper.lockRow(arbitration.getRelicId());

        LocalDateTime now = LocalDateTime.now();
        int updated = arbitrationMapper.resolveIfArbitrating(caseId, req.getFinalConclusion(),
                req.getRulingNote(), req.getArbitrator(), now);
        if (updated == 0) {
            AppraisalArbitration current = requireArbitration(caseId);
            String winner = ARB_RESOLVED.equals(current.getStatus())
                    ? "仲裁人 " + current.getArbitrator() + " 于 " + current.getResolvedAt() + " 裁决生效"
                    : "案件已关闭";
            auditService.appendIsolated(current.getRelicId(), null, caseId,
                    ACTION_APPRAISAL_REJECT, req.getArbitrator(), role,
                    "仲裁裁决被拒绝：案件 " + current.getCaseNo() + " 已由" + winner
                            + "，本次仲裁人 " + req.getArbitrator() + " 未生效");
            throw new IllegalArgumentException("仲裁案件 " + current.getCaseNo()
                    + " 已由" + winner + "，本次裁决被拒绝并已留审计");
        }
        auditService.append(arbitration.getRelicId(), null, caseId,
                ACTION_ARBITRATION_RESOLVE, req.getArbitrator(), role,
                "仲裁裁决：" + req.getFinalConclusion() + "；初鉴与复鉴意见原文均保留，"
                        + "裁决说明：" + req.getRulingNote());
        return requireArbitration(caseId);
    }

    // ------------------------------------------------------------------
    // 查看：普通馆员仅结论摘要，专家/负责人见全文
    // ------------------------------------------------------------------

    /**
     * 按岗位可见性返回鉴定信息：鉴定专家/负责人见全文（意见原文+附件+更正+仲裁），
     * 普通馆员等其他岗位仅见结论摘要（轮次/结论/状态/仲裁结论）。
     */
    public Map<String, Object> viewForRelic(Long relicId, String role) {
        Relic relic = relicService.requireRelic(relicId);
        List<AppraisalOpinion> opinions = opinionMapper.selectList(
                new LambdaQueryWrapper<AppraisalOpinion>()
                        .eq(AppraisalOpinion::getRelicId, relicId)
                        .orderByAsc(AppraisalOpinion::getId));
        List<AppraisalArbitration> cases = arbitrationMapper.selectList(
                new LambdaQueryWrapper<AppraisalArbitration>()
                        .eq(AppraisalArbitration::getRelicId, relicId)
                        .orderByAsc(AppraisalArbitration::getId));

        Map<String, Object> view = new LinkedHashMap<>();
        if (isPrivileged(role)) {
            view.put("visible", "FULL");
            view.put("relic", relic);
            List<Map<String, Object>> opinionViews = new ArrayList<>();
            for (AppraisalOpinion opinion : opinions) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("opinion", opinion);
                item.put("attachments", attachmentMapper.selectList(
                        new LambdaQueryWrapper<AppraisalAttachment>()
                                .eq(AppraisalAttachment::getOpinionId, opinion.getId())));
                item.put("corrections", correctionMapper.selectList(
                        new LambdaQueryWrapper<AppraisalCorrection>()
                                .eq(AppraisalCorrection::getOpinionId, opinion.getId())
                                .orderByAsc(AppraisalCorrection::getCorrectedAt)));
                opinionViews.add(item);
            }
            view.put("opinions", opinionViews);
            view.put("arbitrations", cases);
        } else {
            // 普通馆员：仅结论摘要，不含专家、依据、置信说明、附件与更正明细
            view.put("visible", "SUMMARY");
            view.put("relicId", relic.getId());
            view.put("relicNo", relic.getRelicNo());
            view.put("relicName", relic.getName());
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (AppraisalOpinion opinion : opinions) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("round", opinion.getAppraisalRound());
                item.put("conclusion", opinion.getConclusion());
                item.put("status", opinion.getStatus());
                item.put("publishedAt", opinion.getPublishedAt());
                summaries.add(item);
            }
            view.put("opinions", summaries);
            List<Map<String, Object>> caseSummaries = new ArrayList<>();
            for (AppraisalArbitration arbitration : cases) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("status", arbitration.getStatus());
                item.put("finalConclusion", arbitration.getFinalConclusion());
                caseSummaries.add(item);
            }
            view.put("arbitrations", caseSummaries);
        }
        return view;
    }

    /**
     * 下载详细报告：仅鉴定专家/负责人；每次下载（含被拒绝的尝试）都写审计流水。
     */
    public Map<String, Object> downloadReport(Long relicId, String role, String operator) {
        Relic relic = relicService.requireRelic(relicId);
        if (!isPrivileged(role)) {
            auditService.appendIsolated(relicId, null, null,
                    ACTION_APPRAISAL_REJECT, operator, role,
                    "详细报告下载被拒绝：普通馆员仅可查看结论摘要，下载人 " + operator + " 未生效");
            throw new IllegalArgumentException("普通馆员仅可查看结论摘要，详细报告不开放下载");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportNo", "APR-" + relic.getRelicNo());
        report.put("relic", relic);
        report.put("generatedBy", operator);
        report.put("generatedAt", LocalDateTime.now());
        report.putAll(viewForRelic(relicId, role));

        auditService.appendIsolated(relicId, null, null,
                ACTION_APPRAISAL_DOWNLOAD, operator, role,
                "详细鉴定报告已下载，报告号 APR-" + relic.getRelicNo());
        return report;
    }

    // ------------------------------------------------------------------
    // 查询辅助
    // ------------------------------------------------------------------

    public AppraisalOpinion requireOpinion(Long opinionId) {
        AppraisalOpinion opinion = opinionMapper.selectById(opinionId);
        if (opinion == null) {
            throw new IllegalArgumentException("鉴定意见不存在: id=" + opinionId);
        }
        return opinion;
    }

    public AppraisalArbitration requireArbitration(Long caseId) {
        AppraisalArbitration arbitration = arbitrationMapper.selectById(caseId);
        if (arbitration == null) {
            throw new IllegalArgumentException("仲裁案件不存在: id=" + caseId);
        }
        return arbitration;
    }

    public List<AppraisalArbitration> listArbitrations(String status) {
        return arbitrationMapper.selectList(new LambdaQueryWrapper<AppraisalArbitration>()
                .eq(status != null && !status.isEmpty(), AppraisalArbitration::getStatus, status)
                .orderByDesc(AppraisalArbitration::getId));
    }

    /** 同一文物同一轮次的在途（SUBMITTED/PUBLISHED）意见，没有则为 null */
    private AppraisalOpinion findActive(Long relicId, String round) {
        return opinionMapper.selectOne(new LambdaQueryWrapper<AppraisalOpinion>()
                .eq(AppraisalOpinion::getActiveKey, activeKey(relicId, round))
                .last("LIMIT 1"));
    }

    private AppraisalArbitration findOpenArbitration(Long relicId) {
        return arbitrationMapper.selectOne(new LambdaQueryWrapper<AppraisalArbitration>()
                .eq(AppraisalArbitration::getOpenKey, String.valueOf(relicId))
                .last("LIMIT 1"));
    }

    /** 冲突开立仲裁：两条意见原样保留，案件固化双方意见 id 与结论 */
    private void openArbitration(Relic relic, AppraisalOpinion initial, AppraisalOpinion review,
                                 LocalDateTime now) {
        AppraisalArbitration arbitration = new AppraisalArbitration();
        arbitration.setCaseNo(String.format("AR-%06d", arbitrationMapper.nextArbitrationSeq()));
        arbitration.setRelicId(relic.getId());
        arbitration.setInitialOpinionId(initial.getId());
        arbitration.setReviewOpinionId(review.getId());
        arbitration.setStatus(ARB_ARBITRATING);
        arbitration.setOpenKey(String.valueOf(relic.getId()));
        arbitrationMapper.insert(arbitration);

        auditService.append(relic.getId(), null, arbitration.getId(),
                ACTION_ARBITRATION_OPEN, review.getExpertName(), ROLE_APPRAISAL_EXPERT,
                "初鉴（" + initial.getOpinionNo() + "，" + initial.getConclusion() + "）与复鉴（"
                        + review.getOpinionNo() + "，" + review.getConclusion()
                        + "）结论冲突，进入仲裁状态；两条意见原样保留，案件 "
                        + arbitration.getCaseNo());
    }

    /** 重复/并发提交拒绝：审计说明谁生效、谁被拒绝，返回待抛出的异常 */
    private IllegalArgumentException duplicateSubmit(Long relicId, AppraisalSubmitRequest req,
                                                     AppraisalOpinion winner) {
        String winnerText = winner == null ? "未知"
                : "专家 " + winner.getExpertName() + "（意见编号 " + winner.getOpinionNo()
                        + "，提交于 " + winner.getSubmittedAt() + "）";
        auditService.appendIsolated(relicId, winner == null ? null : winner.getId(), null,
                ACTION_APPRAISAL_REJECT, req.getExpertName(), ROLE_APPRAISAL_EXPERT,
                roundLabel(req.getRound()) + "提交被拒绝：" + winnerText + " 已生效，本次提交人 "
                        + req.getExpertName() + " 未生效");
        return new IllegalArgumentException("该文物" + roundLabel(req.getRound()) + "已由"
                + winnerText + " 提交生效，本次提交被拒绝并已留审计");
    }

    private void requireLeader(String role, String action) {
        if (!ROLE_APPRAISAL_LEADER.equals(role)) {
            throw new IllegalArgumentException("仅鉴定负责人可" + action + "，当前岗位: "
                    + (role == null || role.isEmpty() ? "未提供" : role));
        }
    }

    private boolean isPrivileged(String role) {
        return role != null && PRIVILEGED_ROLES.contains(role);
    }

    private String activeKey(Long relicId, String round) {
        return relicId + "-" + round;
    }

    private String nextOpinionNo() {
        return String.format("AP-%06d", opinionMapper.nextOpinionSeq());
    }

    private String roundLabel(String round) {
        return ROUND_REVIEW.equals(round) ? "复鉴" : "初鉴";
    }

    private String statusLabel(AppraisalOpinion opinion) {
        if (OPINION_PUBLISHED.equals(opinion.getStatus())) {
            return "已由 " + opinion.getPublishedBy() + " 于 " + opinion.getPublishedAt() + " 发布";
        }
        if (OPINION_WITHDRAWN.equals(opinion.getStatus())) {
            return "已由 " + opinion.getWithdrawnBy() + " 于 " + opinion.getWithdrawnAt() + " 撤回";
        }
        return "未发布";
    }
}
