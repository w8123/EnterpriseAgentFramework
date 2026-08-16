package com.enterprise.ai.control.context;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class PersonalMemoryCandidateControllerTest {

    @Test
    void exposesSelfServiceCandidateReviewContract() throws Exception {
        Method list = PersonalMemoryCandidateController.class.getDeclaredMethod("list", HttpServletRequest.class,
                String.class, String.class, Integer.class, Integer.class);
        Method approve = PersonalMemoryCandidateController.class.getDeclaredMethod("approve", HttpServletRequest.class,
                String.class, Long.class, PersonalMemoryCandidateService.ReviewCommand.class);
        Method reject = PersonalMemoryCandidateController.class.getDeclaredMethod("reject", HttpServletRequest.class,
                String.class, Long.class, PersonalMemoryCandidateService.ReviewCommand.class);

        assertArrayEquals(new String[]{"/api/context/personal-memory-candidates"},
                list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/api/context/personal-memory-candidates/{id}/approve"},
                approve.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/context/personal-memory-candidates/{id}/reject"},
                reject.getAnnotation(PostMapping.class).value());
    }
}
