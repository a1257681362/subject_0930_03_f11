package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文物鉴定意见：初鉴（INITIAL）/复鉴（REVIEW）分别落表，
 * 专家、鉴定依据、置信说明随意见固化，提交后不改写。
 * 同一文物同一轮次只允许一条在途（SUBMITTED/PUBLISHED）意见，
 * 由 activeKey 稀疏唯一约束兜底；撤回只置状态，记录保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_appraisal_opinion")
public class AppraisalOpinion extends BaseEntity {

    /** 意见编号，全局唯一 */
    private String opinionNo;

    private Long relicId;

    /** INITIAL 初鉴 / REVIEW 复鉴 */
    private String appraisalRound;

    /** 鉴定专家 */
    private String expertName;

    /** 结论：AUTHENTIC 真品 / FAKE 伪品 / UNCERTAIN 存疑 */
    private String conclusion;

    /** 鉴定依据 */
    private String basis;

    /** 置信说明 */
    private String confidenceNote;

    /** SUBMITTED 已提交未发布 / PUBLISHED 已发布 / WITHDRAWN 已撤回 */
    private String status;

    /** 在途占用键 = relicId-轮次；撤回时置 NULL 释放槽位 */
    private String activeKey;

    private String submittedBy;

    private LocalDateTime submittedAt;

    private String publishedBy;

    private LocalDateTime publishedAt;

    private String withdrawnBy;

    private LocalDateTime withdrawnAt;

    private String withdrawReason;
}
