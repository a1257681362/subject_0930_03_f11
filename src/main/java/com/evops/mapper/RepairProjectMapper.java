package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.RepairProject;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface RepairProjectMapper extends BaseMapper<RepairProject> {

    /**
     * 开工的原子守卫：仅 PLANNED 可置 REPAIRING。同一操作并发发起时，
     * 只有第一个到达的更新生效（返回 1），后到者返回 0 由调用方拒绝并留审计。
     */
    @Update("UPDATE t_repair_project SET status = 'REPAIRING', "
            + "start_date = COALESCE(start_date, #{today}), update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'PLANNED'")
    int startIfPlanned(@Param("id") Long id, @Param("today") LocalDate today);

    /** 修复完成的原子守卫：仅 REPAIRING 可置 REPAIRED */
    @Update("UPDATE t_repair_project SET status = 'REPAIRED', finish_date = #{today}, "
            + "update_time = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'REPAIRING'")
    int finishIfRepairing(@Param("id") Long id, @Param("today") LocalDate today);

    /** 验收的原子守卫：仅 REPAIRED 可置 ACCEPTED */
    @Update("UPDATE t_repair_project SET status = 'ACCEPTED', update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'REPAIRED'")
    int acceptIfRepaired(@Param("id") Long id);

    /** 作废的原子守卫：仅 PLANNED/REPAIRING 可置 CANCELLED */
    @Update("UPDATE t_repair_project SET status = 'CANCELLED', cancel_reason = #{reason}, "
            + "cancelled_at = #{now}, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status IN ('PLANNED', 'REPAIRING')")
    int cancelIfOpen(@Param("id") Long id, @Param("reason") String reason,
                     @Param("now") LocalDateTime now);
}
