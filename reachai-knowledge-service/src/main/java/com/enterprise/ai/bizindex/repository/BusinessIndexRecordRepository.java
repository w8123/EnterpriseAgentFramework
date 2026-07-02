package com.enterprise.ai.bizindex.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndexRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BusinessIndexRecordRepository extends BaseMapper<BusinessIndexRecord> {
}
