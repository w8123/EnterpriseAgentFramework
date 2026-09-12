package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.domain.entity.UserFilePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;
import com.enterprise.ai.security.FileAccessSnapshot;
import com.enterprise.ai.security.AuthorizedKnowledgeChunk;

import java.util.List;

@Mapper
public interface UserFilePermissionRepository extends BaseMapper<UserFilePermission> {

    @Select("SELECT file_id FROM knowledge_user_file_permission WHERE user_id = #{userId}")
    List<String> selectFileIdsByUserId(@Param("userId") String userId);

    @Select("""
            SELECT p.id AS grant_id,p.record_generation AS grant_generation,f.id AS file_record_id,
                   f.record_generation AS file_generation,f.knowledge_base_id,f.file_id,k.vector_collection_name AS collection_name
              FROM knowledge_user_file_permission p
              JOIN knowledge_file_info f ON f.file_id=p.file_id
              JOIN knowledge_base k ON k.id=f.knowledge_base_id
             WHERE p.user_id=#{userId} AND p.permission_type IN ('read','write','admin') AND k.status=1
               AND k.vector_collection_name IS NOT NULL AND k.vector_collection_name<>''
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<FileAccessSnapshot.Grant> selectFileGrantsByUserId(@Param("userId") String userId);

    @Select("""
            <script>
            SELECT c.id AS chunk_id,p.id AS grant_id,p.record_generation AS grant_generation,f.id AS file_record_id,
                   f.record_generation AS file_generation,f.knowledge_base_id,f.file_id,
                   k.vector_collection_name AS collection_name,k.code AS knowledge_base_code,c.vector_id,c.content,f.file_name
              FROM knowledge_chunk c
              JOIN knowledge_file_info f ON f.file_id=c.file_id AND f.knowledge_base_id=c.knowledge_base_id
              JOIN knowledge_base k ON k.id=f.knowledge_base_id
              JOIN knowledge_user_file_permission p ON p.file_id=f.file_id AND p.user_id=#{userId}
             WHERE p.permission_type IN ('read','write','admin') AND k.status=1 AND (c.enabled IS NULL OR c.enabled!=0)
               AND c.id IN <foreach collection="chunkIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<AuthorizedKnowledgeChunk> selectAuthorizedChunks(@Param("userId") String userId, @Param("chunkIds") List<Long> chunkIds);
}
