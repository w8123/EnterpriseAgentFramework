package com.enterprise.ai.control.context;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonalMemoryControllerTest {

    @Test
    void exposesSelfServicePersonalMemoryContract() throws Exception {
        Method list = PersonalMemoryController.class.getDeclaredMethod("list", HttpServletRequest.class,
                String.class, String.class, String.class, String.class, Integer.class, Integer.class);
        Method remember = PersonalMemoryController.class.getDeclaredMethod("remember", HttpServletRequest.class,
                String.class, PersonalMemoryService.RememberCommand.class);
        Method get = PersonalMemoryController.class.getDeclaredMethod("get", HttpServletRequest.class,
                String.class, Long.class);
        Method update = PersonalMemoryController.class.getDeclaredMethod("update", HttpServletRequest.class,
                String.class, Long.class, PersonalMemoryService.UpdateCommand.class);
        Method forget = PersonalMemoryController.class.getDeclaredMethod("forget", HttpServletRequest.class,
                String.class, Long.class, PersonalMemoryService.ForgetCommand.class);
        Method eraseAll = PersonalMemoryController.class.getDeclaredMethod("eraseAll", HttpServletRequest.class,
                String.class, PersonalMemoryService.EraseAllCommand.class);
        Method query = PersonalMemoryController.class.getDeclaredMethod("query", HttpServletRequest.class,
                String.class, PersonalMemoryService.QueryCommand.class);
        Method audit = PersonalMemoryController.class.getDeclaredMethod("audit", HttpServletRequest.class,
                String.class, Integer.class);
        Method export = PersonalMemoryController.class.getDeclaredMethod("export", HttpServletRequest.class,
                String.class);

        assertArrayEquals(new String[]{"/api/context/personal-memories"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/api/context/personal-memories"}, remember.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/context/personal-memories/{id}"}, get.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/api/context/personal-memories/{id}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[]{"/api/context/personal-memories/{id}"}, forget.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[]{"/api/context/personal-memories/erase-all"}, eraseAll.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/context/personal-memories/query"}, query.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/context/personal-memories/audit"}, audit.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/api/context/personal-memories/export"}, export.getAnnotation(GetMapping.class).value());
    }

    @Test
    void rememberUsesResolvedPrincipalAndReturnsCreatedStatus() {
        PersonalMemoryIdentityResolver resolver = mock(PersonalMemoryIdentityResolver.class);
        PersonalMemoryService service = mock(PersonalMemoryService.class);
        PersonalMemoryController controller = new PersonalMemoryController(resolver, service);
        HttpServletRequest request = mock(HttpServletRequest.class);
        var principal = new PersonalMemoryIdentityResolver.PersonalMemoryPrincipal(
                "default", "user-1", 1L, "user", "platform-session");
        var command = new PersonalMemoryService.RememberCommand("FACT", null, null, "content", null,
                null, null, null, null, null, null);
        var view = new PersonalMemoryService.MemoryView(1L, "key", "FACT", null, "content", null,
                "VERIFIED", "ACTIVE", null, null, LocalDateTime.now(), LocalDateTime.now());
        when(resolver.resolve(request, null)).thenReturn(principal);
        when(service.remember(principal, command)).thenReturn(
                new PersonalMemoryService.RememberResult(view, true, false, false));

        ResponseEntity<PersonalMemoryService.RememberResult> response = controller.remember(request, null, command);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(1L, response.getBody().memory().id());
    }
}
