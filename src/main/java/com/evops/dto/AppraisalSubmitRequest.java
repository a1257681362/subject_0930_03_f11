package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
public class AppraisalSubmitRequest {

    @NotNull(message = "文物ID不能为空")
    private Long relicId;

    /** INITIAL 初鉴 / REVIEW 复鉴 */
    @NotBlank(message = "鉴定轮次不能为空")
    private String round;

    /** 鉴定专家 */
    @NotBlank(message = "鉴定专家不能为空")
    private String expertName;

    /** 结论：AUTHENTIC 真品 / FAKE 伪品 / UNCERTAIN 存疑 */
    @NotBlank(message = "鉴定结论不能为空")
    private String conclusion;

    /** 鉴定依据 */
    @NotBlank(message = "鉴定依据不能为空")
    private String basis;

    /** 置信说明 */
    @NotBlank(message = "置信说明不能为空")
    private String confidenceNote;

    /** 附件（可选，随意见一并固化） */
    @Valid
    private List<AppraisalAttachmentDto> attachments;
}
