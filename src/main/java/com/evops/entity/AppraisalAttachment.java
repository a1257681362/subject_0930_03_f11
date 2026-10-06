package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 鉴定附件：随意见一并固化，只追加不删除；
 * 意见撤回后附件仍留档可查。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_appraisal_attachment")
public class AppraisalAttachment extends BaseEntity {

    private Long opinionId;

    private String fileName;

    private String storagePath;

    private String contentType;

    private Long fileSize;

    private String uploadedBy;

    private LocalDateTime uploadedAt;
}
