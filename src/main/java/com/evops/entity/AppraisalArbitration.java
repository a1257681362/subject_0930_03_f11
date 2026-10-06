package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 鉴定仲裁案件：初鉴与复鉴结论冲突时开立。
 * 两条意见原样保留，绝不取最后一条覆盖前文；
 * 裁决结论只落在案件上，不改写任何一条意见原文。
 * 同一文物同时只有一个仲裁中案件（openKey 稀疏唯一约束兜底）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_appraisal_arbitration")
public class AppraisalArbitration extends BaseEntity {

    /** 案件编号，全局唯一 */
    private String caseNo;

    private Long relicId;

    private Long initialOpinionId;

    private Long reviewOpinionId;

    /** ARBITRATING 仲裁中 / RESOLVED 已裁决 / CLOSED 已关闭（复鉴撤回、冲突消失） */
    private String status;

    /** 在途占用键 = relicId；裁决/关闭时置 NULL */
    private String openKey;

    /** 裁决结论：AUTHENTIC / FAKE / UNCERTAIN */
    private String finalConclusion;

    /** 裁决说明 */
    private String rulingNote;

    /** 仲裁人 */
    private String arbitrator;

    private LocalDateTime resolvedAt;
}
