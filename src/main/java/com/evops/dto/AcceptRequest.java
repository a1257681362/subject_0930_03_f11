package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class AcceptRequest {

    @NotBlank(message = "验收人不能为空")
    private String acceptedBy;

    /** PASS / CONDITIONAL，默认 PASS */
    private String result;

    private String opinion;

    /**
     * 附条件通过（CONDITIONAL，部分成功）时登记的验收后保护等级 ONE/TWO/THREE。
     * CONDITIONAL 必填：立即落到文物档案，并开立保护等级补偿（museum-10）。
     */
    private String gradeAfter;
}
