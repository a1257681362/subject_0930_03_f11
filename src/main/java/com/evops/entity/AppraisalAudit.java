package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 鉴定审计流水：只追加。
 * 提交/撤回/发布/更正/仲裁开立与裁决/报告下载/并发与冲突拒绝全部留痕；
 * 拒绝记录说明谁生效、谁被拒绝。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_appraisal_audit")
public class AppraisalAudit extends BaseEntity {

    private Long relicId;

    private Long opinionId;

    private Long arbitrationId;

    /** APPRAISAL_SUBMIT / WITHDRAW / PUBLISH / CORRECT / ARBITRATION_OPEN /
     *  ARBITRATION_RESOLVE / ARBITRATION_CLOSE / APPRAISAL_DOWNLOAD / APPRAISAL_REJECT */
    private String action;

    /** 操作人（被拒绝时为本次发起者） */
    private String actor;

    /** 操作人岗位 */
    private String actorRole;

    private String detail;

    private LocalDateTime occurredAt;
}
