package com.evops.service;

import com.evops.entity.LedgerEntry;
import com.evops.mapper.LedgerEntryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 台账流水：只追加。借出、归还、修复、验收、入藏、撤销的每一步都在此留痕。
 */
@Service
public class LedgerService {

    private final LedgerEntryMapper ledgerMapper;

    public LedgerService(LedgerEntryMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public void append(Long relicId, Long projectId, Long loanId,
                       String action, String bizNo, String summary, LocalDateTime occurredAt) {
        LedgerEntry entry = new LedgerEntry();
        entry.setRelicId(relicId);
        entry.setProjectId(projectId);
        entry.setLoanId(loanId);
        entry.setAction(action);
        entry.setBizNo(bizNo);
        entry.setSummary(summary);
        entry.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        ledgerMapper.insert(entry);
    }

    /**
     * 独立事务立即提交（拒绝路径专用）：调用方随后抛异常回滚业务写入，
     * 但"谁生效、谁被拒绝"的台账必须留下。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendIsolated(Long relicId, Long projectId, Long loanId,
                               String action, String bizNo, String summary, LocalDateTime occurredAt) {
        append(relicId, projectId, loanId, action, bizNo, summary, occurredAt);
    }

    /** 项目某动作最新一条台账（用于并发拒绝时指认先生效的操作） */
    public LedgerEntry findLatestByProjectAction(Long projectId, String action) {
        return ledgerMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LedgerEntry>()
                        .eq(LedgerEntry::getProjectId, projectId)
                        .eq(LedgerEntry::getAction, action)
                        .orderByDesc(LedgerEntry::getId)
                        .last("LIMIT 1"));
    }

    public List<LedgerEntry> listByRelic(Long relicId) {
        return ledgerMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LedgerEntry>()
                        .eq(LedgerEntry::getRelicId, relicId)
                        .orderByAsc(LedgerEntry::getOccurredAt)
                        .orderByAsc(LedgerEntry::getId));
    }
}
