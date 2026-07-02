package com.enterprise.ai.capability.catalog.semantic;

import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.capability.catalog.scan.CapabilityModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilitySemanticCatalogServiceTest {

    private final SemanticDocMapper semanticDocMapper = mock(SemanticDocMapper.class);
    private final ScanProjectMapper scanProjectMapper = mock(ScanProjectMapper.class);
    private final ScanModuleMapper scanModuleMapper = mock(ScanModuleMapper.class);
    private final ScanProjectToolMapper scanProjectToolMapper = mock(ScanProjectToolMapper.class);
    private final ToolDefinitionMapper toolDefinitionMapper = mock(ToolDefinitionMapper.class);
    private final CapabilityModelClient modelClient = mock(CapabilityModelClient.class);
    private final CapabilitySemanticCatalogService service = new CapabilitySemanticCatalogService(
            semanticDocMapper,
            scanProjectMapper,
            scanModuleMapper,
            scanProjectToolMapper,
            toolDefinitionMapper,
            modelClient,
            new ObjectMapper()
    );

    @Test
    void findsToolSemanticDocByToolName() {
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setId(21L);
        tool.setName("orders_create");
        SemanticDocEntity doc = doc("tool", 7L, 3L, 21L);
        when(toolDefinitionMapper.selectOne(any())).thenReturn(tool);
        when(semanticDocMapper.selectOne(any())).thenReturn(doc);

        SemanticDocEntity result = service.findDoc("tool", 7L, 3L, "orders_create", null).orElseThrow();

        assertEquals(21L, result.getToolId());
    }

    @Test
    void requiresScanToolIdForScanToolLevel() {
        assertThrows(IllegalArgumentException.class,
                () -> service.findDoc("scan_tool", 7L, null, null, null));
    }

    @Test
    void listsProjectDocs() {
        when(semanticDocMapper.selectList(any())).thenReturn(List.of(doc("project", 7L, null, null)));

        List<SemanticDocEntity> docs = service.listProjectDocs(7L);

        assertEquals(1, docs.size());
        verify(semanticDocMapper).selectList(any());
    }

    @Test
    void renamesScanModule() {
        ScanModuleEntity module = module(3L, 7L, "OrderController");
        when(scanModuleMapper.selectById(3L)).thenReturn(module);

        ScanModuleEntity result = service.renameModule(3L, "Orders");

        assertEquals("Orders", result.getDisplayName());
        verify(scanModuleMapper).updateById(module);
    }

    @Test
    void mergesScanModulesAndMovesTools() {
        ScanModuleEntity target = module(3L, 7L, "OrderController");
        target.setSourceClasses("[\"OrderController\"]");
        ScanModuleEntity source = module(4L, 7L, "LegacyOrderController");
        source.setSourceClasses("[\"LegacyOrderController\"]");
        ScanProjectToolEntity sourceTool = new ScanProjectToolEntity();
        sourceTool.setId(31L);
        sourceTool.setModuleId(4L);
        when(scanModuleMapper.selectById(3L)).thenReturn(target);
        when(scanModuleMapper.selectById(4L)).thenReturn(source);
        when(scanProjectToolMapper.selectList(any())).thenReturn(List.of(sourceTool));
        AtomicReference<ScanModuleEntity> updatedTarget = new AtomicReference<>();
        when(scanModuleMapper.updateById(any())).thenAnswer(invocation -> {
            updatedTarget.set(invocation.getArgument(0));
            return 1;
        });

        ScanModuleEntity result = service.mergeModules(3L, List.of(4L), "Merged Orders");

        assertEquals("Merged Orders", result.getDisplayName());
        assertEquals(3L, sourceTool.getModuleId());
        assertEquals("[\"LegacyOrderController\",\"OrderController\"]", updatedTarget.get().getSourceClasses());
        verify(scanProjectToolMapper).updateById(sourceTool);
        verify(scanModuleMapper).deleteById(4L);
    }

    @Test
    void generatesProjectDocThroughModelServiceAndPersistsSemanticDoc() {
        ScanProjectEntity project = project(7L, "orders");
        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(modelClient.chat(any())).thenReturn(com.enterprise.ai.common.dto.ApiResult.ok(
                new CapabilityModelClient.ChatResponse(
                        "# Orders\n\nGenerated summary",
                        "qwen-plus",
                        null,
                        new CapabilityModelClient.Usage(10, 7, 17))));

        SemanticDocEntity result = service.generateProjectDoc(7L, true, "model-main");

        assertEquals("project", result.getLevel());
        assertEquals(7L, result.getProjectId());
        assertEquals("# Orders\n\nGenerated summary", result.getContentMd());
        assertEquals("qwen-plus", result.getModelName());
        assertEquals(17, result.getTokenUsage());
        assertEquals("generated", result.getStatus());
        verify(semanticDocMapper).insert(any(SemanticDocEntity.class));
    }

    @Test
    void editsSemanticDocAndMarksEdited() {
        SemanticDocEntity existing = doc("tool", 7L, 3L, 21L);
        when(semanticDocMapper.selectById(11L)).thenReturn(existing);

        SemanticDocEntity edited = service.editDoc(11L, "# Edited");

        assertEquals("# Edited", edited.getContentMd());
        assertEquals("edited", edited.getStatus());
        verify(semanticDocMapper).updateById(existing);
    }

    private SemanticDocEntity doc(String level, Long projectId, Long moduleId, Long toolId) {
        SemanticDocEntity doc = new SemanticDocEntity();
        doc.setId(11L);
        doc.setLevel(level);
        doc.setProjectId(projectId);
        doc.setModuleId(moduleId);
        doc.setToolId(toolId);
        doc.setContentMd("# Orders");
        doc.setPromptVersion("v1");
        doc.setModelName("gpt");
        doc.setTokenUsage(42);
        doc.setStatus("generated");
        return doc;
    }

    private ScanModuleEntity module(Long id, Long projectId, String name) {
        ScanModuleEntity module = new ScanModuleEntity();
        module.setId(id);
        module.setProjectId(projectId);
        module.setName(name);
        module.setDisplayName(name);
        module.setSourceClasses("[\"" + name + "\"]");
        return module;
    }

    private ScanProjectEntity project(Long id, String name) {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(id);
        project.setName(name);
        project.setProjectCode(name);
        project.setEnvironment("dev");
        project.setBaseUrl("http://localhost");
        return project;
    }
}
