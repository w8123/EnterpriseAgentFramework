package com.enterprise.ai.capability.catalog.semantic;

import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilitySemanticCatalogControllerTest {

    @Test
    void keepsSemanticReadAndModuleManagementRouteShapeOnCapabilityService() throws Exception {
        RequestMapping controllerMapping = CapabilitySemanticCatalogController.class.getAnnotation(RequestMapping.class);
        Method query = CapabilitySemanticCatalogController.class.getDeclaredMethod(
                "query", String.class, Long.class, Long.class, String.class, Long.class);
        Method listProjectDocs = CapabilitySemanticCatalogController.class.getDeclaredMethod("listProjectDocs", Long.class);
        Method listModules = CapabilitySemanticCatalogController.class.getDeclaredMethod("listModules", Long.class);
        Method batchGenerate = CapabilitySemanticCatalogController.class.getDeclaredMethod(
                "batchGenerate", Long.class, boolean.class, String.class);
        Method batchStatus = CapabilitySemanticCatalogController.class.getDeclaredMethod(
                "batchStatus", Long.class, String.class);
        Method generateProject = CapabilitySemanticCatalogController.class.getDeclaredMethod(
                "generateProject", Long.class, boolean.class, String.class);
        Method generateModule = CapabilitySemanticCatalogController.class.getDeclaredMethod(
                "generateModule", Long.class, boolean.class, String.class);
        Method generateTool = CapabilitySemanticCatalogController.class.getDeclaredMethod(
                "generateTool", String.class, boolean.class, String.class);
        Method generateScanTool = CapabilitySemanticCatalogController.class.getDeclaredMethod(
                "generateScanTool", Long.class, Long.class, boolean.class, String.class);
        Method edit = CapabilitySemanticCatalogController.class.getDeclaredMethod(
                "edit", Long.class, CapabilitySemanticCatalogController.EditRequest.class);
        Method rename = CapabilitySemanticCatalogController.class.getDeclaredMethod(
                "rename", Long.class, CapabilitySemanticCatalogController.ModuleRenameRequest.class);
        Method merge = CapabilitySemanticCatalogController.class.getDeclaredMethod(
                "merge", CapabilitySemanticCatalogController.ModuleMergeRequest.class);

        assertArrayEquals(new String[] {"/api"}, controllerMapping.value());
        assertArrayEquals(new String[] {"/semantic-docs"}, query.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/scan-projects/{id}/semantic-docs"}, listProjectDocs.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/scan-projects/{id}/semantic/generate"}, batchGenerate.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/scan-projects/{id}/semantic/status"}, batchStatus.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/scan-projects/{id}/semantic/generate-project"}, generateProject.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/scan-modules/{id}/semantic/generate"}, generateModule.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/tools/{name}/semantic/generate"}, generateTool.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/scan-projects/{projectId}/scan-tools/{scanToolId}/semantic/generate"}, generateScanTool.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/semantic-docs/{id}"}, edit.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/scan-projects/{id}/modules"}, listModules.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/scan-modules/{id}"}, rename.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/scan-modules/merge"}, merge.getAnnotation(PostMapping.class).value());
    }

    @Test
    void queryReturnsSemanticDoc() {
        CapabilitySemanticCatalogService service = mock(CapabilitySemanticCatalogService.class);
        CapabilitySemanticCatalogController controller = new CapabilitySemanticCatalogController(service);
        SemanticDocEntity doc = doc("tool", 7L, 3L, 21L);
        when(service.findDoc("tool", 7L, 3L, "orders_create", null)).thenReturn(java.util.Optional.of(doc));

        ResponseEntity<?> response = controller.query("tool", 7L, 3L, "orders_create", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapabilitySemanticCatalogController.SemanticDocDTO body =
                (CapabilitySemanticCatalogController.SemanticDocDTO) response.getBody();
        assertEquals("# Orders", body.contentMd());
    }

    @Test
    void listModulesReturnsScanModuleDtos() {
        CapabilitySemanticCatalogService service = mock(CapabilitySemanticCatalogService.class);
        CapabilitySemanticCatalogController controller = new CapabilitySemanticCatalogController(service);
        ScanModuleEntity module = module(3L, 7L, "OrderController");
        when(service.listModules(7L)).thenReturn(List.of(module));
        when(service.parseClasses(module.getSourceClasses())).thenReturn(List.of("OrderController"));

        ResponseEntity<List<CapabilitySemanticCatalogController.ScanModuleDTO>> response = controller.listModules(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("OrderController", response.getBody().get(0).displayName());
    }

    @Test
    void generatesProjectDoc() {
        CapabilitySemanticCatalogService service = mock(CapabilitySemanticCatalogService.class);
        CapabilitySemanticCatalogController controller = new CapabilitySemanticCatalogController(service);
        SemanticDocEntity doc = doc("project", 7L, null, null);
        when(service.generateProjectDoc(7L, true, "model-main")).thenReturn(doc);

        ResponseEntity<?> response = controller.generateProject(7L, true, "model-main");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(service).generateProjectDoc(7L, true, "model-main");
    }

    @Test
    void startsBatchGeneration() {
        CapabilitySemanticCatalogService service = mock(CapabilitySemanticCatalogService.class);
        CapabilitySemanticCatalogController controller = new CapabilitySemanticCatalogController(service);
        when(service.startProjectBatch(7L, false, "model-main")).thenReturn("task-1");

        ResponseEntity<?> response = controller.batchGenerate(7L, false, "model-main");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        CapabilitySemanticCatalogController.BatchStartResponse body =
                (CapabilitySemanticCatalogController.BatchStartResponse) response.getBody();
        assertEquals("task-1", body.taskId());
    }

    @Test
    void editsSemanticDoc() {
        CapabilitySemanticCatalogService service = mock(CapabilitySemanticCatalogService.class);
        CapabilitySemanticCatalogController controller = new CapabilitySemanticCatalogController(service);
        SemanticDocEntity doc = doc("tool", 7L, 3L, 21L);
        doc.setContentMd("# Edited");
        doc.setStatus("edited");
        when(service.editDoc(11L, "# Edited")).thenReturn(doc);

        ResponseEntity<?> response = controller.edit(11L, new CapabilitySemanticCatalogController.EditRequest("# Edited"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapabilitySemanticCatalogController.SemanticDocDTO body =
                (CapabilitySemanticCatalogController.SemanticDocDTO) response.getBody();
        assertEquals("# Edited", body.contentMd());
        assertEquals("edited", body.status());
    }

    @Test
    void renameDelegatesToService() {
        CapabilitySemanticCatalogService service = mock(CapabilitySemanticCatalogService.class);
        CapabilitySemanticCatalogController controller = new CapabilitySemanticCatalogController(service);
        ScanModuleEntity module = module(3L, 7L, "OrderController");
        module.setDisplayName("Orders");
        when(service.renameModule(3L, "Orders")).thenReturn(module);
        when(service.parseClasses(module.getSourceClasses())).thenReturn(List.of("OrderController"));

        ResponseEntity<?> response =
                controller.rename(3L, new CapabilitySemanticCatalogController.ModuleRenameRequest("Orders"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(service).renameModule(3L, "Orders");
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
}
