package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
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
        return invokeWithContext(context, capabilityName, arguments);
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
            return invoker.invoke(capabilityName, arguments);
        }
        ReachAiInvocationContextHolder.set(context);
        try {
            ReachBusinessMemoryResolverGuard.InvocationScope memoryScope = null;
            if (invoker.requiresBusinessMemoryGuard(capabilityName)) {
                memoryScope = ReachBusinessMemoryResolverGuard.requireInvocation(
                        context, capabilityName, arguments);
            }
            Object result = invokeBridge(0, context, capabilityName, arguments);
            if (memoryScope != null) {
                return ReachBusinessMemoryResolverGuard.validateResult(memoryScope, result);
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
}
