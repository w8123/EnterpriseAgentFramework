package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MybatisMcpPublicationRepositoryTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "mcp-publication-page-test"),
                McpPublicationEntity.class);
    }

    @Test
    void findPageAcceptsMissingAndBlankFilters() {
        McpPublicationMapper publicationMapper = mock(McpPublicationMapper.class);
        when(publicationMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(publicationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        MybatisMcpPublicationRepository repository = new MybatisMcpPublicationRepository(
                publicationMapper,
                mock(McpPublicationItemMapper.class),
                mock(McpPublicationRevisionMapper.class),
                new ObjectMapper());

        McpPublicationRepository.Page missingFilters = assertDoesNotThrow(
                () -> repository.findPage(null, null, 20, 0));
        McpPublicationRepository.Page blankFilters = assertDoesNotThrow(
                () -> repository.findPage("  ", "  ", 20, 0));

        assertEquals(0, missingFilters.total());
        assertEquals(List.of(), missingFilters.items());
        assertEquals(0, blankFilters.total());
        assertEquals(List.of(), blankFilters.items());
    }
}
