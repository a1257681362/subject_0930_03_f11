package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class AppraisalWithdrawRequest {

    /** 撤回人：必须是提交该意见的本专家 */
    @NotBlank(message = "撤回人不能为空")
    private String expertName;

    @NotBlank(message = "撤回原因不能为空")
    private String reason;
}
