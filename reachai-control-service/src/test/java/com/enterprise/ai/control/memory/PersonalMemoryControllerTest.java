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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonalMemoryControllerTest {

    @Test
    void exposesSelfServicePersonalMemoryContract() throws Exception {
        Class<PersonalMemoryController> type = PersonalMemoryController.class;
        assertArrayEquals(new String[]{"/api/me/memories"}, type.getAnnotation(RequestMapping.class).value());

        Method list = type.getDeclaredMethod("list", HttpServletRequest.class,
                String.class, String.class, String.class, String.class, Integer.class, Integer.class);
        Method remember = type.getDeclaredMethod("remember", HttpServletRequest.class,
                String.class, PersonalMemoryService.RememberCommand.class);
        Method get = type.getDeclaredMethod("get", HttpServletRequest.class, String.class, Long.class);
        Method update = type.getDeclaredMethod("update", HttpServletRequest.class,
                String.class, Long.class, PersonalMemoryService.UpdateCommand.class);
        Method forget = type.getDeclaredMethod("forget", HttpServletRequest.class, String.class, Long.class);
        Method eraseAll = type.getDeclaredMethod("eraseAll", HttpServletRequest.class,
                String.class, PersonalMemoryService.EraseAllCommand.class);
        Method audit = type.getDeclaredMethod("audit", HttpServletRequest.class, String.class, Integer.class);
        Method export = type.getDeclaredMethod("export", HttpServletRequest.class, String.class);
        Method candidates = type.getDeclaredMethod("candidates", HttpServletRequest.class,
                String.class, String.class, Integer.class, Integer.class);
        Method approve = type.getDeclaredMethod("approve", HttpServletRequest.class,
                String.class, Long.class, PersonalMemoryCandidateService.ReviewCommand.class);
        Method reject = type.getDeclaredMethod("reject", HttpServletRequest.class, String.class, Long.class);

        assertArrayEquals(new String[]{}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{}, remember.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/{id}"}, get.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/{id}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[]{"/{id}"}, forget.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[]{"/erase-all"}, eraseAll.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/audit"}, audit.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/export"}, export.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/candidates"}, candidates.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/candidates/{id}/approve"}, approve.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/candidates/{id}/reject"}, reject.getAnnotation(PostMapping.class).value());
    }

    @Test
    void manualRememberIsAlwaysRecordedAsManualAndReturnsCreated() {
        PersonalMemoryIdentityResolver resolver = mock(PersonalMemoryIdentityResolver.class);
        PersonalMemoryService service = mock(PersonalMemoryService.class);
        PersonalMemoryController controller = new PersonalMemoryController(resolver, service,
                mock(PersonalMemoryCandidateService.class));
        HttpServletRequest request = mock(HttpServletRequest.class);
        var principal = new PersonalMemoryIdentityResolver.PersonalMemoryPrincipal(
                "default", "user-1", 1L, "user", "platform-session");
        var command = new PersonalMemoryService.RememberCommand("FACT", null, null, "content", null,
                null, null, "CONVERSATION", "s1", "t1", "a1");
        var view = new PersonalMemoryService.MemoryView(1L, "FACT", null, "content", null,
                "MANUAL", null, null, LocalDateTime.now(), LocalDateTime.now());
        when(resolver.resolve(request, null)).thenReturn(principal);
        when(service.remember(eq(principal), any())).thenAnswer(invocation -> {
            PersonalMemoryService.RememberCommand manual = invocation.getArgument(1);
            assertEquals(PersonalMemoryService.SOURCE_MANUAL, manual.source());
            assertEquals(null, manual.agentId());
            return new PersonalMemoryService.RememberResult(view, true, false, false);
        });

        ResponseEntity<PersonalMemoryService.RememberResult> response = controller.remember(request, null, command);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(1L, response.getBody().memory().id());
    }
}
