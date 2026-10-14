package com.enterprise.ai.capability.catalog.enrichment;

import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import com.enterprise.ai.capability.catalog.semantic.CapabilitySemanticCatalogService;
import com.enterprise.ai.capability.catalog.graph.CapabilityApiGraphOperationsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapabilityCatalogEnrichmentControllerTest {
    @Test void standaloneClassificationCreationValidatesProjectAndReturnsTheExistingGroupShape() throws Exception {
        var subjects = mock(CatalogSubjectResolver.class); var grouping = mock(CapabilityCatalogGroupingService.class);
        var group = new com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity();
        group.setId(4L); group.setProjectId(7L); group.setName("category:fixture"); group.setDisplayName("字典查询与统计"); group.setSourceClasses("[]");
        when(grouping.create(7L, "字典查询与统计")).thenReturn(group);
        when(grouping.parseClasses("[]")).thenReturn(List.of());
        var controller = new CapabilityCatalogEnrichmentController(subjects, grouping,
                mock(CapabilitySemanticCatalogService.class), mockGraph(), new ObjectMapper(),
                mock(CatalogClassificationService.class), mock(CatalogClassificationEditorService.class));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/capability/business-capabilities/projects/7/groups")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).accept(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"字典查询与统计\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").value(4))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.projectId").value(7))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.displayName").value("字典查询与统计"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sourceClasses").isEmpty());
        var order = inOrder(subjects, grouping); order.verify(subjects).requireProject(7L); order.verify(grouping).create(7L, "字典查询与统计");

        when(subjects.requireProject(99L)).thenThrow(new IllegalArgumentException("项目不存在"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/capability/business-capabilities/projects/99/groups")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).accept(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"字典查询与统计\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        verify(grouping, never()).create(eq(99L), any());
    }

    @Test void removedClassificationMergeCannotReachGroupingOrChangeProjectData() throws Exception {
        var subjects = mock(CatalogSubjectResolver.class); var grouping = mock(CapabilityCatalogGroupingService.class);
        var semantic = mock(CapabilitySemanticCatalogService.class); var graph = mockGraph();
        var classification = mock(CatalogClassificationService.class);
        var controller = new CapabilityCatalogEnrichmentController(subjects, grouping, semantic, graph, new ObjectMapper(), classification, org.mockito.Mockito.mock(com.enterprise.ai.capability.catalog.enrichment.CatalogClassificationEditorService.class));
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/capability/business-capabilities/projects/7/groups/merge")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":4,\"sourceIds\":[5],\"displayName\":\"Combined\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isMethodNotAllowed());
        verifyNoInteractions(subjects, grouping, semantic, graph, classification);
    }

    @Test void classificationRouteSerializesTheCompleteTypedIndexAndRejectsInvalidSubject() throws Exception {
        var classification = mock(CatalogClassificationService.class);
        var controller = new CapabilityCatalogEnrichmentController(mock(CatalogSubjectResolver.class), mock(CapabilityCatalogGroupingService.class),
                mock(CapabilitySemanticCatalogService.class), mockGraph(), new ObjectMapper(), classification, org.mockito.Mockito.mock(com.enterprise.ai.capability.catalog.enrichment.CatalogClassificationEditorService.class));
        when(classification.index(7L, "HTTP_API")).thenReturn(new CatalogClassificationService.ClassificationIndex(7L, "HTTP_API",
                List.of(new CatalogClassificationService.Group(4L, 7L, "source", "Orders", List.of("Controller"), 1)),
                List.of(new CatalogClassificationService.Membership(21L, List.of(4L)), new CatalogClassificationService.Membership(22L, List.of())), 1));
        when(classification.index(7L, "GROUP")).thenThrow(new IllegalArgumentException("invalid type"));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/capability/business-capabilities/projects/7/classification/HTTP_API")
                        .accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.groups[0].memberCount").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.memberships[1].groupIds").isEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.ungroupedCount").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/capability/business-capabilities/projects/7/classification/GROUP")
                        .accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        verify(classification).index(7L, "HTTP_API");
    }
    @Test void documentListResolvesCurrentAggregateNamesAndKeepsMissingGroupProseReadable() {
        var subjects = mock(CatalogSubjectResolver.class); var semantic = mock(CapabilitySemanticCatalogService.class);
        var controller = new CapabilityCatalogEnrichmentController(subjects, mock(CapabilityCatalogGroupingService.class), semantic,
                mockGraph(), new ObjectMapper(), mock(CatalogClassificationService.class), org.mockito.Mockito.mock(com.enterprise.ai.capability.catalog.enrichment.CatalogClassificationEditorService.class));
        var api = new CatalogSubject("HTTP_API", 21L, 7L, "http-api:orders:default:abc", "GET /orders", "", "hash", true, "GET", "/orders", List.of(), List.of());
        when(subjects.documentReferences(eq(7L), anyList())).thenReturn(java.util.Map.of(
                new CatalogSubjectResolver.DocumentReference("HTTP_API", 21L), api,
                new CatalogSubjectResolver.DocumentReference("PROJECT", 7L), aggregate("PROJECT", 7L, "订单公开实验"),
                new CatalogSubjectResolver.DocumentReference("GROUP", 21L), aggregate("GROUP", 21L, "字典查询与统计")));
        when(semantic.listProjectDocs(7L)).thenReturn(List.of(
                prose("PROJECT", 7L, "project:orders"), prose("GROUP", 21L, "group:21"),
                prose("HTTP_API", 21L, api.subjectKey()), prose("GROUP", 99L, "group:99")));

        var listed = controller.docs(7L);
        assertEquals(List.of("订单公开实验", "字典查询与统计", "GET /orders", "group:99"), listed.stream().map(CapabilityCatalogEnrichmentController.SemanticDocIndex::title).toList());
        assertFalse(new ObjectMapper().valueToTree(listed.get(3)).has("contentMd"));
        assertEquals("GROUP", listed.get(1).subjectType());
        assertEquals("HTTP_API", listed.get(2).subjectType(), "matching IDs must still resolve by typed owner");
        verify(subjects, never()).operations(7L);
        verify(subjects, never()).resolve(any(), any(), any());

        when(subjects.documentReferences(eq(7L), anyList())).thenReturn(java.util.Map.of(
                new CatalogSubjectResolver.DocumentReference("HTTP_API", 21L), api,
                new CatalogSubjectResolver.DocumentReference("PROJECT", 7L), aggregate("PROJECT", 7L, "订单公开实验"),
                new CatalogSubjectResolver.DocumentReference("GROUP", 21L), aggregate("GROUP", 21L, "重命名后的分组")));
        assertEquals("重命名后的分组", controller.docs(7L).get(1).title());
    }

    @Test void emptyDocumentListSkipsAllCatalogContractAndSourceReads() {
        var subjects = mock(CatalogSubjectResolver.class); var semantic = mock(CapabilitySemanticCatalogService.class);
        var controller = new CapabilityCatalogEnrichmentController(subjects, mock(CapabilityCatalogGroupingService.class), semantic,
                mockGraph(), new ObjectMapper(), mock(CatalogClassificationService.class), mock(CatalogClassificationEditorService.class));
        when(semantic.listProjectDocs(7L)).thenReturn(List.of());
        assertTrue(controller.docs(7L).isEmpty());
        verifyNoInteractions(subjects);
    }

    @Test void semanticBodyUsesTheUniqueFormalReferenceAndDoesNotLoadGraphOrClassification() throws Exception {
        var subjects = mock(CatalogSubjectResolver.class); var semantic = mock(CapabilitySemanticCatalogService.class);
        var graph = mockGraph(); var grouping = mock(CapabilityCatalogGroupingService.class);
        var owner = new CatalogSubject("HTTP_API", 21L, 7L, "http-api:orders:default:abc", "GET /orders", "", "", true,
                "GET", "/orders", List.of(), List.of());
        when(subjects.resolveDocumentReference(7L, "SOURCE_OBSERVATION", 41L)).thenReturn(owner);
        var document = prose("HTTP_API", 21L, owner.subjectKey());
        when(semantic.find(owner)).thenReturn(Optional.of(document));
        var controller = new CapabilityCatalogEnrichmentController(subjects, grouping, semantic, graph,
                new ObjectMapper(), mock(CatalogClassificationService.class), mock(CatalogClassificationEditorService.class));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/internal/capability/business-capabilities/projects/7/subjects/SOURCE_OBSERVATION/41/semantic")
                        .accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.subjectType").value("HTTP_API"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.subjectId").value(21))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contentMd").value("公开实验说明"));
        verifyNoInteractions(graph, grouping); verify(subjects, never()).resolve(any(), any(), any());
    }

    @Test void assignmentBatchReturnsEveryIndependentRevisionInOneTypedRequest() throws Exception {
        var editor = mock(CatalogClassificationEditorService.class);
        when(editor.readBatch(7L, "HTTP_API", List.of(21L, 22L))).thenReturn(List.of(
                new CatalogClassificationEditorService.Assignment(7L, "HTTP_API", 21L, 3L, List.of(4L)),
                new CatalogClassificationEditorService.Assignment(7L, "HTTP_API", 22L, 0L, List.of())));
        var controller = new CapabilityCatalogEnrichmentController(mock(CatalogSubjectResolver.class), mock(CapabilityCatalogGroupingService.class),
                mock(CapabilitySemanticCatalogService.class), mockGraph(), new ObjectMapper(), mock(CatalogClassificationService.class), editor);
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/internal/capability/business-capabilities/projects/7/classification/HTTP_API/assignments").param("subjectIds", "21,22")
                        .accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].revision").value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[1].revision").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[1].groupIds").isEmpty());
        verify(editor).readBatch(7L, "HTTP_API", List.of(21L, 22L)); verify(editor, never()).read(any(), any(), any());
    }

    private CapabilityApiGraphOperationsService mockGraph() {
        var graph = mock(CapabilityApiGraphOperationsService.class);
        when(graph.paramHints(any())).thenReturn(new CapabilityApiGraphOperationsService.ParamHints("CURRENT", List.of()));
        return graph;
    }

    private CatalogSubject aggregate(String type, Long id, String title) {
        return new CatalogSubject(type, id, 7L, type.toLowerCase() + ":" + id, title, "", "hash", false, null, null, List.of(), List.of());
    }

    private SemanticDocEntity prose(String type, Long id, String key) {
        var doc = new SemanticDocEntity(); doc.setProjectId(7L); doc.setSubjectType(type); doc.setSubjectId(id);
        doc.setSubjectKey(key); doc.setContentMd("公开实验说明"); return doc;
    }

    @Test void exposesOwnerProseWithContractStalenessAndNoInventedVerifiedState() {
        CatalogSubjectResolver subjects = mock(CatalogSubjectResolver.class);
        CapabilitySemanticCatalogService semantic = mock(CapabilitySemanticCatalogService.class);
        CapabilityCatalogGroupingService grouping = mock(CapabilityCatalogGroupingService.class);
        CatalogSubject subject = new CatalogSubject("HTTP_API", 21L, 7L, "http-api:orders:default:abc", "GET /orders", "", "current", true, "GET", "/orders", List.of(), List.of());
        when(subjects.resolve(7L, "HTTP_API", 21L)).thenReturn(subject);
        SemanticDocEntity doc = new SemanticDocEntity(); doc.setSubjectType("HTTP_API"); doc.setSubjectId(21L); doc.setBasisContractHash("old");
        when(semantic.find(subject)).thenReturn(Optional.of(doc));
        var controller = new CapabilityCatalogEnrichmentController(subjects, grouping, semantic, mockGraph(), new ObjectMapper(), mock(CatalogClassificationService.class), org.mockito.Mockito.mock(com.enterprise.ai.capability.catalog.enrichment.CatalogClassificationEditorService.class));
        var view = controller.enrichment(7L, "HTTP_API", 21L);
        assertTrue(view.semanticStale()); assertEquals(subject.subjectKey(), view.subjectKey()); assertEquals("HTTP_API", view.semanticDoc().subjectType());
    }

    @Test void groupDetailRemainsStaleAwareWithoutReadingGroupMembershipTwice() {
        var subjects = mock(CatalogSubjectResolver.class); var semantic = mock(CapabilitySemanticCatalogService.class);
        var grouping = mock(CapabilityCatalogGroupingService.class);
        var graph = mockGraph();
        var before = new CatalogSubject("GROUP", 4L, 7L, "group:4", "订单", "\n查询 [BUSINESS_METHOD]", "hash-a", false,
                "", "", List.of(), List.of());
        var after = new CatalogSubject("GROUP", 4L, 7L, "group:4", "订单", "\n查询 [BUSINESS_METHOD]\n统计 [BUSINESS_METHOD]", "hash-b", false,
                "", "", List.of(), List.of());
        when(subjects.resolve(7L, "GROUP", 4L)).thenReturn(before, after);
        var doc = new SemanticDocEntity(); doc.setProjectId(7L); doc.setSubjectType("GROUP"); doc.setSubjectId(4L);
        doc.setSubjectKey("group:4"); doc.setBasisContractHash("hash-a"); doc.setBasisAccepted(false);
        when(semantic.find(any())).thenReturn(Optional.of(doc));
        var controller = new CapabilityCatalogEnrichmentController(subjects, grouping, semantic, graph,
                new ObjectMapper(), mock(CatalogClassificationService.class), mock(CatalogClassificationEditorService.class));
        assertFalse(controller.enrichment(7L, "GROUP", 4L).semanticStale());
        var changed = controller.enrichment(7L, "GROUP", 4L);
        assertTrue(changed.semanticStale()); assertEquals("hash-b", changed.contractHash());
        assertEquals(List.of(), changed.groups());
        verify(grouping, never()).groups(any());
        verify(subjects, times(2)).resolve(7L, "GROUP", 4L);
    }

    @Test void projectAndGroupGenerationUseSameTypedOperationAndBatchReturnsRecoverableId() {
        CatalogSubjectResolver subjects = mock(CatalogSubjectResolver.class);
        CapabilitySemanticCatalogService semantic = mock(CapabilitySemanticCatalogService.class);
        var controller = new CapabilityCatalogEnrichmentController(subjects, mock(CapabilityCatalogGroupingService.class), semantic, mockGraph(), new ObjectMapper(), mock(CatalogClassificationService.class), org.mockito.Mockito.mock(com.enterprise.ai.capability.catalog.enrichment.CatalogClassificationEditorService.class));
        when(semantic.startProjectBatch(7L, false, "model")).thenReturn("task-1");
        assertEquals("task-1", controller.batch(7L, false, "model").getBody().get("taskId"));
        verify(semantic).startProjectBatch(7L, false, "model");
    }

    @Test void acceptedContractWithSameHashStillFlagsCandidateExplanationAsStale() {
        var subjects = mock(CatalogSubjectResolver.class); var semantic = mock(CapabilitySemanticCatalogService.class);
        var subject = new CatalogSubject("HTTP_API", 21L, 7L, "http-api:orders:default:abc", "GET /orders", "", "same", true, "GET", "/orders", List.of(), List.of());
        when(subjects.resolve(7L, "HTTP_API", 21L)).thenReturn(subject);
        var doc = new SemanticDocEntity(); doc.setBasisContractHash("same"); doc.setBasisAccepted(false); when(semantic.find(subject)).thenReturn(Optional.of(doc));
        var controller = new CapabilityCatalogEnrichmentController(subjects, mock(CapabilityCatalogGroupingService.class), semantic, mockGraph(), new ObjectMapper(), mock(CatalogClassificationService.class), org.mockito.Mockito.mock(com.enterprise.ai.capability.catalog.enrichment.CatalogClassificationEditorService.class));
        assertTrue(controller.enrichment(7L, "HTTP_API", 21L).semanticStale());
    }
}
