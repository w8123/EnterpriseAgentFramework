package com.enterprise.ai.capability.catalog.enrichment;

import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogClassificationServiceTest {
    private final CatalogSubjectResolver subjects = mock(CatalogSubjectResolver.class);
    private final CapabilityCatalogGroupingService grouping = mock(CapabilityCatalogGroupingService.class);
    private final CatalogGroupMemberMapper members = mock(CatalogGroupMemberMapper.class);
    private final CatalogClassificationService classification = new CatalogClassificationService(subjects, grouping, members);

    @Test void onlyPersistentFormalMembershipCountsRegardlessOfSourceObservations() {
        when(grouping.list(7L)).thenReturn(List.of(group(4L, 7L), group(5L, 7L), group(6L, 7L), group(99L, 8L)));
        var first = source(91L, 7L, 4L); var second = source(92L, 7L, null);
        var dangling = source(93L, 7L, 4L); var foreign = source(94L, 8L, 4L);
        when(subjects.operations(7L, false, "BUSINESS_METHOD")).thenReturn(List.of(subject("BUSINESS_METHOD", 11L, 7L, List.of(first, foreign)),
                subject("BUSINESS_METHOD", 12L, 7L, List.of(second)), subject("BUSINESS_METHOD", 13L, 7L, List.of()),
                subject("HTTP_API", 11L, 7L, List.of()), subject("SOURCE_OBSERVATION", 93L, 7L, List.of(dangling)),
                subject("BUSINESS_METHOD", 90L, 8L, List.of())));
        when(members.selectList(any())).thenReturn(List.of(member(7L, 4L, "BUSINESS_METHOD", 11L),
                member(7L, 5L, "BUSINESS_METHOD", 11L), member(7L, 5L, "BUSINESS_METHOD", 11L),
                member(7L, 5L, "SOURCE_OBSERVATION", 92L), member(7L, 4L, "HTTP_API", 11L),
                member(7L, 4L, "SOURCE_OBSERVATION", 93L), member(8L, 4L, "BUSINESS_METHOD", 13L),
                member(7L, 99L, "BUSINESS_METHOD", 13L)));
        var index = classification.index(7L, "BUSINESS_METHOD");
        assertEquals(7L, index.projectId()); assertEquals("BUSINESS_METHOD", index.subjectType());
        assertEquals(List.of(11L, 12L, 13L), index.memberships().stream().map(CatalogClassificationService.Membership::subjectId).toList());
        assertEquals(List.of(4L, 5L), index.memberships().get(0).groupIds());
        assertEquals(List.of(), index.memberships().get(1).groupIds());
        assertEquals(List.of(), index.memberships().get(2).groupIds());
        assertEquals(List.of(1L, 1L, 0L), index.groups().stream().map(CatalogClassificationService.Group::memberCount).toList());
        assertEquals(2L, index.ungroupedCount());
        verify(subjects, times(1)).operations(7L, false, "BUSINESS_METHOD"); verify(grouping, times(1)).list(7L);
        verify(members, times(1)).selectList(any()); verify(grouping, never()).groups(any());
        verify(subjects, never()).resolve(any(), any(), any());
    }

    @Test void matchingNumericIdsOfAnotherOwnerTypeNeverAssignTheApi() {
        when(grouping.list(7L)).thenReturn(List.of(group(4L, 7L)));
        when(subjects.operations(7L, false, "HTTP_API")).thenReturn(List.of(subject("HTTP_API", 11L, 7L, List.of()), subject("HTTP_API", 12L, 7L, List.of(source(92L, 7L, 4L)))));
        when(members.selectList(any())).thenReturn(List.of(member(7L, 4L, "BUSINESS_METHOD", 11L)));
        var index = classification.index(7L, "HTTP_API");
        assertEquals(List.of(), index.memberships().get(0).groupIds());
        assertEquals(List.of(), index.memberships().get(1).groupIds());
        assertEquals(0L, index.groups().get(0).memberCount()); assertEquals(2L, index.ungroupedCount());
    }

    @Test void pageLabelsReadOnlyRequestedOwnersAndKeepMultipleAssignmentsInIndexOrder() {
        var unnamed = group(5L, 7L); unnamed.setDisplayName(null);
        when(grouping.list(7L)).thenReturn(List.of(group(4L, 7L), unnamed, group(99L, 8L)));
        when(members.selectList(any())).thenReturn(List.of(member(7L, 5L, "BUSINESS_METHOD", 11L),
                member(7L, 4L, "BUSINESS_METHOD", 11L), member(7L, 4L, "BUSINESS_METHOD", 11L),
                member(7L, 4L, "HTTP_API", 12L), member(7L, 4L, "BUSINESS_METHOD", 90L),
                member(8L, 4L, "BUSINESS_METHOD", 12L), member(7L, 99L, "BUSINESS_METHOD", 12L)));
        var labels = classification.labels(7L, "BUSINESS_METHOD", List.of(11L, 12L));
        assertEquals(List.of(new CatalogClassificationService.Label(4L, "Group 4"),
                new CatalogClassificationService.Label(5L, "source-5")), labels.get(11L));
        assertEquals(List.of(), labels.get(12L));
        assertEquals(2, labels.size());
        verify(grouping).list(7L);
        verify(members).selectList(any());
        verifyNoInteractions(subjects);
    }

    @Test void emptyPageDoesNotQueryGroupsOrMembers() {
        assertEquals(java.util.Map.of(), classification.labels(7L, "BUSINESS_METHOD", List.of()));
        verifyNoInteractions(grouping, members, subjects);
    }

    @Test void emptyProjectReturnsCompleteEmptyIndexAndUnknownProjectOrTypeIsRejected() {
        when(subjects.operations(7L, false, "HTTP_API")).thenReturn(List.of()); when(grouping.list(7L)).thenReturn(List.of()); when(members.selectList(any())).thenReturn(List.of());
        assertEquals(0, classification.index(7L, "HTTP_API").ungroupedCount());
        assertThrows(IllegalArgumentException.class, () -> classification.index(null, "HTTP_API"));
        assertThrows(IllegalArgumentException.class, () -> classification.index(0L, "BUSINESS_METHOD"));
        assertThrows(IllegalArgumentException.class, () -> classification.index(7L, null));
        for (String type : List.of("GROUP", "SOURCE_OBSERVATION", "http_api", " HTTP_API"))
            assertThrows(IllegalArgumentException.class, () -> classification.index(7L, type));
        when(subjects.operations(99L, false, "HTTP_API")).thenThrow(new IllegalArgumentException("missing project"));
        assertThrows(IllegalArgumentException.class, () -> classification.index(99L, "HTTP_API"));
    }

    private ScanModuleEntity group(Long id, Long projectId) { var value = new ScanModuleEntity(); value.setId(id); value.setProjectId(projectId); value.setName("source-" + id); value.setDisplayName("Group " + id); return value; }
    private ScanProjectToolEntity source(Long id, Long projectId, Long moduleId) { var value = new ScanProjectToolEntity(); value.setId(id); value.setProjectId(projectId); value.setModuleId(moduleId); return value; }
    private CatalogSubject subject(String type, Long id, Long projectId, List<ScanProjectToolEntity> sources) { return new CatalogSubject(type, id, projectId, "key:" + id, "title", "", "hash", true, "GET", "/items", List.of(), sources); }
    private CatalogGroupMemberEntity member(Long projectId, Long groupId, String type, Long id) { var value = new CatalogGroupMemberEntity(); value.setProjectId(projectId); value.setGroupId(groupId); value.setSubjectType(type); value.setSubjectId(id); return value; }
}
