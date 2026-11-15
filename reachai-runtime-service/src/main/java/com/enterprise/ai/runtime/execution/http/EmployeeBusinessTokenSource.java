package com.enterprise.ai.runtime.execution.http;

import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;

import java.util.List;

/**
 * 「用员工自己的令牌调用」: where the HTTP client learns whether a call made for an employee should carry the
 * employee's own business-system token instead of the project's shared credential.
 */
public interface EmployeeBusinessTokenSource {

    String OFF = "OFF";
    /** Own token while there is a working one, otherwise the shared credential. */
    String PREFERRED = "PREFERRED";
    /** Never the shared credential: without a working own token the call is refused. */
    String REQUIRED = "REQUIRED";

    /** Never null. Throws {@link WorkflowHttpClient.WorkflowHttpExecutionException} when it cannot be known right now. */
    Resolution resolve(WorkflowExecutionIdentity employee);

    /** The business system refused the employee's token. */
    void expired(WorkflowExecutionIdentity employee);

    /**
     * @param origins {@code scheme://host:port} the token may be sent to; a call to any other address keeps
     *                using the shared credential, so the token never reaches a third party
     */
    record Resolution(String mode, List<String> origins, String token) {
        public static final Resolution NONE = new Resolution(OFF, List.of(), null);

        public Resolution {
            mode = REQUIRED.equals(mode) ? REQUIRED : PREFERRED.equals(mode) ? PREFERRED : OFF;
            origins = origins == null ? List.of() : List.copyOf(origins);
            token = token == null || token.isBlank() ? null : token;
        }

        @Override
        public String toString() {
            return "Resolution[mode=" + mode + ", origins=" + origins + ", token=" + (token == null ? "none" : "***") + "]";
        }
    }
}
