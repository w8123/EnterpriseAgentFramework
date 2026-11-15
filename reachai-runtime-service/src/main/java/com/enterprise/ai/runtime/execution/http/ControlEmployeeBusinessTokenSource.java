package com.enterprise.ai.runtime.execution.http;

import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.EmployeeBusinessTokenSubject;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Asks Control, which holds the tokens employees got when they signed in with their business account.
 * Every dispatch asks again: caching this answer would also cache the administrator's authorization policy.
 */
@Slf4j
@Component
public class ControlEmployeeBusinessTokenSource implements EmployeeBusinessTokenSource {

    private final RuntimeControlCatalogClient control;

    public ControlEmployeeBusinessTokenSource(RuntimeControlCatalogClient control) {
        this.control = control;
    }

    @Override
    public Resolution resolve(WorkflowExecutionIdentity employee) {
        try {
            var answer = control.resolveEmployeeBusinessToken(subject(employee));
            if (answer == null) throw new IllegalStateException("Missing employee credential policy");
            return new Resolution(answer.mode(), answer.origins(), answer.token());
        } catch (RuntimeException unavailable) {
            log.warn("[EmployeeToken] cannot ask Control which credential to use: {}",
                    unavailable.getClass().getSimpleName());
            // Not knowing must never quietly become "use the shared credential".
            throw new WorkflowHttpClient.WorkflowHttpExecutionException("RUNTIME_HTTP_EMPLOYEE_TOKEN_UNAVAILABLE",
                    "暂时确认不了该用哪个凭据调用业务系统，请稍后再试");
        }
    }

    @Override
    public void expired(WorkflowExecutionIdentity employee) {
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

}
