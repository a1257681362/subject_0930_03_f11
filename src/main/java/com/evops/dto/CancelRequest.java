package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class CancelRequest {

    @NotBlank(message = "作废原因不能为空")
    private String reason;

    /** 操作人（可选，缺省记 SYSTEM）；并发作废时用于指认谁生效、谁被拒绝 */
    private String operator;
}
