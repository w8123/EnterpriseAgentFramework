package com.enterprise.ai.agent.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MCP 暴露白名单：默认所有 Tool/Skill 都不暴露，必须在管理端勾选后才允许通过 MCP 协议访问。
 */
@Service
@RequiredArgsConstructor
public class McpVisibilityService {

    private final McpVisibilityMapper mapper;

    public Set<String> exposedToolNames() {
        Set<String> out = new HashSet<>();
        LambdaQueryWrapper<McpVisibilityEntity> w = new LambdaQueryWrapper<>();
        w.eq(McpVisibilityEntity::getExposed, true);
        for (McpVisibilityEntity e : mapper.selectList(w)) {
            out.add(e.getTargetName());
        }
        return out;
    }

    public boolean isExposed(String toolName) {
        if (toolName == null) return false;
        LambdaQueryWrapper<McpVisibilityEntity> w = new LambdaQueryWrapper<>();
        w.eq(McpVisibilityEntity::getTargetName, toolName).last("LIMIT 1");
        McpVisibilityEntity row = mapper.selectOne(w);
        return row != null && Boolean.TRUE.equals(row.getExposed());
    }

    public List<McpVisibilityEntity> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<McpVisibilityEntity>()
                .orderByAsc(McpVisibilityEntity::getTargetKind)
                .orderByAsc(McpVisibilityEntity::getTargetName));
    }

    public McpVisibilityEntity setExposed(String kind, String name, boolean exposed, String note) {
        LambdaQueryWrapper<McpVisibilityEntity> w = new LambdaQueryWrapper<>();
        w.eq(McpVisibilityEntity::getTargetKind, kind)
                .eq(McpVisibilityEntity::getTargetName, name).last("LIMIT 1");
        McpVisibilityEntity exist = mapper.selectOne(w);
        if (exist != null) {
            exist.setExposed(exposed);
            if (note != null) exist.setNote(note);
            mapper.updateById(exist);
            return exist;
        }
        McpVisibilityEntity row = new McpVisibilityEntity();
        row.setTargetKind(kind);
        row.setTargetName(name);
        row.setExposed(exposed);
        row.setNote(note);
        mapper.insert(row);
        return row;
    }
}
