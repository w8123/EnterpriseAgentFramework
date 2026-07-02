package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.domain.entity.FileInfo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileInfoRepository extends BaseMapper<FileInfo> {
}
