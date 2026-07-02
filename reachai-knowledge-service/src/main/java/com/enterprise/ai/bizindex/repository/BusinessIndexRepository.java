package com.enterprise.ai.bizindex.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndex;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BusinessIndexRepository extends BaseMapper<BusinessIndex> {
}
