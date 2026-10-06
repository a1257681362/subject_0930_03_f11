package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 鉴定更正：已发布（PUBLISHED）意见不可改写、不可撤回，
 * 只能逐条追加更正。本表只追加。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_appraisal_correction")
public class AppraisalCorrection extends BaseEntity {

    private Long opinionId;

    /** 更正内容 */
    private String content;

    private String correctedBy;

    private LocalDateTime correctedAt;
}
