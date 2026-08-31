package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RuntimeEvalDatasetMapper extends BaseMapper<RuntimeEvalDatasetEntity> {

    @Select("SELECT * FROM runtime_eval_dataset WHERE id = #{id} FOR UPDATE")
    RuntimeEvalDatasetEntity selectByIdForUpdate(@Param("id") Long id);
}
