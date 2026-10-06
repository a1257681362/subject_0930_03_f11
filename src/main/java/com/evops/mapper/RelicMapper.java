package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.Relic;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface RelicMapper extends BaseMapper<Relic> {

    /**
     * 文物行锁：对文物行执行一次 UPDATE 占位，H2 行级排他锁持有到当前事务
     * 提交——同一文物的并发鉴定提交/撤回在此串行化，后到者在锁内复查时
     * 能看到先生效的结果。必须在事务内调用。
     * （不用 SELECT ... FOR UPDATE：@Select 映射结束即关闭 ResultSet，行锁随之释放。）
     */
    @Update("UPDATE t_relic SET update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int lockRow(@Param("id") Long id);
}
