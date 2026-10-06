package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.AppraisalOpinion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AppraisalOpinionMapper extends BaseMapper<AppraisalOpinion> {

    /** 意见编号取号（H2 序列），插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_appraisal_opinion_no")
    Long nextOpinionSeq();

    /**
     * 撤回的原子守卫：仅当意见仍处于 SUBMITTED（未发布）时置 WITHDRAWN 并释放
     * 在途占用键。并发下只有第一个到达的撤回/发布生效，返回 0 表示已被他人抢先。
     */
    @Update("UPDATE t_appraisal_opinion SET status = 'WITHDRAWN', active_key = NULL, "
            + "withdrawn_by = #{operator}, withdrawn_at = #{now}, withdraw_reason = #{reason}, "
            + "update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'SUBMITTED'")
    int withdrawIfSubmitted(@Param("id") Long id, @Param("operator") String operator,
                            @Param("reason") String reason, @Param("now") LocalDateTime now);

    /**
     * 发布的原子守卫：仅 SUBMITTED 可发布。并发发布/撤回只有先生效者成功。
     */
    @Update("UPDATE t_appraisal_opinion SET status = 'PUBLISHED', "
            + "published_by = #{operator}, published_at = #{now}, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'SUBMITTED'")
    int publishIfSubmitted(@Param("id") Long id, @Param("operator") String operator,
                           @Param("now") LocalDateTime now);
}
