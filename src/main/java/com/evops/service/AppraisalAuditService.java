package com.evops.service;

import com.evops.entity.AppraisalAudit;
import com.evops.mapper.AppraisalAuditMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 鉴定审计流水：只追加。
 *
 * <p>两条写入通道：</p>
 * <ul>
 *   <li>{@link #append}：随调用方事务提交，用于成功路径（提交/撤回/发布/
 *       更正/仲裁/下载），与业务写入同生共死。</li>
 *   <li>{@link #appendIsolated}：独立事务立即提交，用于拒绝路径——调用方
 *       随后抛异常回滚业务写入，但"谁生效、谁被拒绝"的审计必须留下。</li>
 * </ul>
 */
@Service
public class AppraisalAuditService {

    private final AppraisalAuditMapper auditMapper;

    public AppraisalAuditService(AppraisalAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    /** 随当前事务提交（成功路径） */
    public void append(Long relicId, Long opinionId, Long arbitrationId,
                       String action, String actor, String actorRole, String detail) {
        AppraisalAudit audit = new AppraisalAudit();
        audit.setRelicId(relicId);
        audit.setOpinionId(opinionId);
        audit.setArbitrationId(arbitrationId);
        audit.setAction(action);
        audit.setActor(actor);
        audit.setActorRole(actorRole);
        audit.setDetail(detail);
        audit.setOccurredAt(LocalDateTime.now());
        auditMapper.insert(audit);
    }

    /** 独立事务立即提交（拒绝路径：调用方随后回滚，审计仍保留） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendIsolated(Long relicId, Long opinionId, Long arbitrationId,
                               String action, String actor, String actorRole, String detail) {
        append(relicId, opinionId, arbitrationId, action, actor, actorRole, detail);
    }

    public List<AppraisalAudit> listByRelic(Long relicId) {
        return auditMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AppraisalAudit>()
                        .eq(AppraisalAudit::getRelicId, relicId)
                        .orderByAsc(AppraisalAudit::getOccurredAt)
                        .orderByAsc(AppraisalAudit::getId));
    }
}
