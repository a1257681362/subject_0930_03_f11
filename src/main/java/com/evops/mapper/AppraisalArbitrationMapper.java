package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.AppraisalArbitration;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AppraisalArbitrationMapper extends BaseMapper<AppraisalArbitration> {

    /** 案件编号取号（H2 序列），插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_appraisal_arbitration_no")
    Long nextArbitrationSeq();

    /**
     * 裁决的原子守卫：仅 ARBITRATING 可裁决。并发裁决只有第一个生效，
     * 返回 0 表示已被他人抢先裁决（或已关闭）。
     */
    @Update("UPDATE t_appraisal_arbitration SET status = 'RESOLVED', open_key = NULL, "
            + "final_conclusion = #{finalConclusion}, ruling_note = #{rulingNote}, "
            + "arbitrator = #{arbitrator}, resolved_at = #{now}, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'ARBITRATING'")
    int resolveIfArbitrating(@Param("id") Long id, @Param("finalConclusion") String finalConclusion,
                             @Param("rulingNote") String rulingNote,
                             @Param("arbitrator") String arbitrator, @Param("now") LocalDateTime now);

    /**
     * 复鉴意见撤回、冲突消失时自动关闭仲裁案件（非裁决闭环）。
     */
    @Update("UPDATE t_appraisal_arbitration SET status = 'CLOSED', open_key = NULL, "
            + "ruling_note = #{note}, resolved_at = #{now}, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'ARBITRATING'")
    int closeIfArbitrating(@Param("id") Long id, @Param("note") String note,
                           @Param("now") LocalDateTime now);
}
