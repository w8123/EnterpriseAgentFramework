package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationToken;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/reachai/capabilities")
public class ReachCapabilityEndpoint {

    private final ReachCapabilityInvoker invoker;
    private final ReachCapabilityInvocationVerifier invocationVerifier;
    private final List<ReachAiSecurityContextBridge> securityContextBridges;

    public ReachCapabilityEndpoint(ReachCapabilityInvoker invoker) {
        this(invoker, null, Collections.<ReachAiSecurityContextBridge>emptyList());
    }

    public ReachCapabilityEndpoint(ReachCapabilityInvoker invoker,
                                   ReachCapabilityInvocationVerifier invocationVerifier,
                                   List<ReachAiSecurityContextBridge> securityContextBridges) {
        this.invoker = invoker;
        this.invocationVerifier = invocationVerifier;
        this.securityContextBridges = securityContextBridges == null
                ? Collections.<ReachAiSecurityContextBridge>emptyList()
                : securityContextBridges;
    }

    @PostMapping("/{capabilityName}/invoke")
    public Object invoke(@PathVariable String capabilityName,
                         @RequestHeader(value = ReachAiInvocationToken.HEADER_NAME, required = false) String invocationToken,
                         @RequestBody(required = false) Map<String, Object> arguments) {
        ReachAiInvocationContext context = verifyInvocation(capabilityName, invocationToken);
        try {
            return invokeWithContext(context, capabilityName, arguments);
        } catch (BusinessExecutionFailure executionFailure) {
            // Host applications often translate all exceptions into HTTP 200 responses using
            // their own envelope. Keep failures at this signed endpoint unambiguous without
            // exposing exception messages or changing the host application's other routes.
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("success", false);
            body.put("code", "CAPABILITY_EXECUTION_FAILED");
            body.put("message", "业务方法执行失败");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }

    public Object invoke(String capabilityName, Map<String, Object> arguments) {
        return invoker.invoke(capabilityName, arguments);
    }

    private ReachAiInvocationContext verifyInvocation(String capabilityName, String invocationToken) {
        if (invocationVerifier == null) {
            return null;
        }
        try {
            return invocationVerifier.verify(capabilityName, invocationToken);
        } catch (IllegalArgumentException ex) {
            throw new ReachAiInvocationUnauthorizedException(ex.getMessage(), ex);
        }
    }

    private Object invokeWithContext(ReachAiInvocationContext context,
                                     String capabilityName,
                                     Map<String, Object> arguments) {
        if (context == null) {
            return business(() -> invoker.invoke(capabilityName, arguments));
        }
        ReachAiInvocationContextHolder.set(context);
        try {
            ReachBusinessIndexResolverGuard.InvocationScope memoryScope = null;
            if (invoker.requiresBusinessIndexGuard(capabilityName)) {
                memoryScope = ReachBusinessIndexResolverGuard.requireInvocation(
                        context, capabilityName, arguments);
            }
            // Only the business call itself becomes the opaque 500 envelope; guard refusals
            // before and after it (tenant, signed user, row proof, input) keep their own exceptions.
            Object result = business(() -> invokeBridge(0, context, capabilityName, arguments));
            if (memoryScope != null) {
                return ReachBusinessIndexResolverGuard.validateResult(memoryScope, result);
            }
            return result;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("ReachAI capability security bridge failed", ex);
        } finally {
            ReachAiInvocationContextHolder.clear();
        }
    }

    private Object invokeBridge(int index,
                                ReachAiInvocationContext context,
                                String capabilityName,
                                Map<String, Object> arguments) throws Exception {
        if (index >= securityContextBridges.size()) {
            return invoker.invoke(capabilityName, arguments);
        }
        ReachAiSecurityContextBridge bridge = securityContextBridges.get(index);
        return bridge.runWith(context, new ReachAiSecurityContextBridge.Invocation() {
            @Override
            public Object proceed() throws Exception {
                return invokeBridge(index + 1, context, capabilityName, arguments);
            }
        });
    }

    private interface BusinessCall {
        Object call() throws Exception;
    }

    private static Object business(BusinessCall call) {
        try {
            return call.call();
        } catch (RuntimeException failure) {
            throw new BusinessExecutionFailure(failure);
        } catch (Exception failure) {
            throw new IllegalStateException("ReachAI capability security bridge failed", failure);
        }
    }

    /** Marks a failure raised by the business method, as opposed to a ReachAI guard refusal. */
    private static final class BusinessExecutionFailure extends RuntimeException {
        BusinessExecutionFailure(RuntimeException cause) {
            super(cause);
        }
    }
}
