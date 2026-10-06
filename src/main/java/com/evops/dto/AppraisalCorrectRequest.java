package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class AppraisalCorrectRequest {

    /** 更正内容（追加到已发布意见，不改写原文） */
    @NotBlank(message = "更正内容不能为空")
    private String content;

    @NotBlank(message = "更正人不能为空")
    private String correctedBy;
}
