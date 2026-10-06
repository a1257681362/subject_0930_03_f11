package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class AppraisalAttachmentDto {

    @NotBlank(message = "附件文件名不能为空")
    private String fileName;

    @NotBlank(message = "附件存储路径不能为空")
    private String storagePath;

    private String contentType;

    private Long fileSize;
}
