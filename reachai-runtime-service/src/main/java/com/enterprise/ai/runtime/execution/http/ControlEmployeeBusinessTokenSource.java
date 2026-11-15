package com.enterprise.ai.runtime.execution.http;

import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.EmployeeBusinessTokenSubject;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks Control, which holds the tokens employees got when they signed in with their business account.
 * Answers are remembered briefly in memory so a run of calls does not ask every time; nothing is stored.
 */
@Slf4j
@Component
public class ControlEmployeeBusinessTokenSource implements EmployeeBusinessTokenSource {

    private static final long REMEMBER_MS = 60_000L;
    /** "No token yet" is asked again quickly, so signing in takes effect almost at once. */
    private static final long REMEMBER_MISSING_MS = 5_000L;
    private static final int MAX_REMEMBERED = 20_000;

    private final RuntimeControlCatalogClient control;
    private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();

    public ControlEmployeeBusinessTokenSource(RuntimeControlCatalogClient control) {
        this.control = control;
    }

    @Override
    public Resolution resolve(WorkflowExecutionIdentity employee) {
        String key = key(employee);
        long now = System.currentTimeMillis();
        Remembered known = remembered.get(key);
        if (known != null && known.until() > now) return known.resolution();
        try {
            Resolution answer = control.resolveEmployeeBusinessToken(subject(employee));
            Resolution resolution = answer == null ? Resolution.NONE
                    : new Resolution(answer.mode(), answer.origins(), answer.token());
            boolean missing = !OFF.equals(resolution.mode()) && resolution.token() == null;
            if (remembered.size() >= MAX_REMEMBERED) remembered.clear();
            remembered.put(key, new Remembered(resolution, now + (missing ? REMEMBER_MISSING_MS : REMEMBER_MS)));
            return resolution;
        } catch (RuntimeException unavailable) {
            remembered.remove(key);
            log.warn("[EmployeeToken] cannot ask Control which credential to use: {}",
                    unavailable.getClass().getSimpleName());
            // Not knowing must never quietly become "use the shared credential".
            throw new WorkflowHttpClient.WorkflowHttpExecutionException("RUNTIME_HTTP_EMPLOYEE_TOKEN_UNAVAILABLE",
                    "暂时确认不了该用哪个凭据调用业务系统，请稍后再试");
        }
    }

    @Override
    public void expired(WorkflowExecutionIdentity employee) {
        remembered.remove(key(employee));
        try {
            control.employeeBusinessTokenExpired(subject(employee));
        } catch (RuntimeException unavailable) {
            log.warn("[EmployeeToken] cannot tell Control a token was refused: {}",
                    unavailable.getClass().getSimpleName());
        }
    }

    private static EmployeeBusinessTokenSubject subject(WorkflowExecutionIdentity employee) {
        return new EmployeeBusinessTokenSubject(employee.tenantId(), employee.projectCode(), employee.userId());
    }

    private static String key(WorkflowExecutionIdentity employee) {
        return employee.tenantId() + "\n" + employee.projectCode() + "\n" + employee.userId();
    }

    private record Remembered(Resolution resolution, long until) {
    }
}
