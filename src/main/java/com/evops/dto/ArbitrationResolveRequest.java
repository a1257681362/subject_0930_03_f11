package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class ArbitrationResolveRequest {

    /** 裁决结论：AUTHENTIC / FAKE / UNCERTAIN */
    @NotBlank(message = "裁决结论不能为空")
    private String finalConclusion;

    /** 裁决说明 */
    @NotBlank(message = "裁决说明不能为空")
    private String rulingNote;

    /** 仲裁人 */
    @NotBlank(message = "仲裁人不能为空")
    private String arbitrator;
}
