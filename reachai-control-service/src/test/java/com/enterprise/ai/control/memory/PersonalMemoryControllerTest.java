package com.enterprise.ai.control.memory;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonalMemoryControllerTest {

    @Test
    void exposesAddEditDeleteAndGroupsButNoAuditOrCandidates() throws Exception {
        Class<PersonalMemoryController> type = PersonalMemoryController.class;
        assertArrayEquals(new String[]{"/api/me/memories"}, type.getAnnotation(RequestMapping.class).value());

        Method tree = type.getDeclaredMethod("tree", HttpServletRequest.class);
        Method list = type.getDeclaredMethod("list", HttpServletRequest.class,
                String.class, Long.class, String.class, String.class, Integer.class, Integer.class);
        Method remember = type.getDeclaredMethod("remember", HttpServletRequest.class,
                PersonalMemoryService.RememberCommand.class);
        Method update = type.getDeclaredMethod("update", HttpServletRequest.class,
                Long.class, PersonalMemoryService.UpdateCommand.class);
        Method forget = type.getDeclaredMethod("forget", HttpServletRequest.class, Long.class);
        Method createGroup = type.getDeclaredMethod("createGroup", HttpServletRequest.class,
                PersonalMemoryGroupService.CreateGroupCommand.class);
        Method updateGroup = type.getDeclaredMethod("updateGroup", HttpServletRequest.class,
                Long.class, PersonalMemoryGroupService.UpdateGroupCommand.class);
        Method deleteGroup = type.getDeclaredMethod("deleteGroup", HttpServletRequest.class, Long.class);
        Method addProject = type.getDeclaredMethod("addProject", HttpServletRequest.class,
                PersonalMemoryGroupService.AddProjectCommand.class);

        assertArrayEquals(new String[]{"/tree"}, tree.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{}, remember.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/{id}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[]{"/{id}"}, forget.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[]{"/groups"}, createGroup.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/groups/{id}"}, updateGroup.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[]{"/groups/{id}"}, deleteGroup.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[]{"/projects"}, addProject.getAnnotation(PostMapping.class).value());
        assertTrue(Arrays.stream(type.getDeclaredMethods()).map(Method::getName)
                .noneMatch(name -> name.equals("audit") || name.contains("andidate")
                        || name.equals("approve") || name.equals("reject")));
    }

    @Test
    void manualRememberIsAlwaysRecordedAsManualAndKeepsTheChosenGroup() {
        PersonalMemoryIdentityResolver resolver = mock(PersonalMemoryIdentityResolver.class);
        PersonalMemoryService service = mock(PersonalMemoryService.class);
        PersonalMemoryController controller = new PersonalMemoryController(resolver, service,
                mock(PersonalMemoryGroupService.class));
        HttpServletRequest request = mock(HttpServletRequest.class);
        var principal = new PersonalMemoryIdentityResolver.PersonalMemoryPrincipal(
                "default", "user-1", 1L, "user", "platform-session");
        var command = new PersonalMemoryService.RememberCommand("FACT", null, null, "content", null,
                null, null, "CONVERSATION", "s1", "t1", "a1", "yudao-erp", 21L);
        var view = new PersonalMemoryService.MemoryView(1L, "NOTE", null, "content", null,
                "PROJECT", "project:yudao-erp", "yudao-erp", "芋道 ERP", 21L, "备注",
                "MANUAL", null, null, LocalDateTime.now(), LocalDateTime.now());
        when(resolver.resolve(request, null)).thenReturn(principal);
        when(service.remember(eq(principal), any())).thenAnswer(invocation -> {
            PersonalMemoryService.RememberCommand manual = invocation.getArgument(1);
            assertEquals(PersonalMemoryService.SOURCE_MANUAL, manual.source());
            assertEquals(null, manual.agentId());
            assertEquals("yudao-erp", manual.projectCode());
            assertEquals(21L, manual.groupId());
            return new PersonalMemoryService.RememberResult(view, true, false, false);
        });

        ResponseEntity<PersonalMemoryService.RememberResult> response = controller.remember(request, command);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(1L, response.getBody().memory().id());
    }
}
