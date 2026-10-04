package com.enterprise.ai.runtime.runops.consolehttpapi;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

/** The source scanned for BMAPI-3B2 is also the method dispatched by the isolated HTTP upstream. */
@RestController
@RequestMapping("/orders")
public class ControllerOrderFixtureController {
    private final AtomicInteger methodCalls = new AtomicInteger();
    private volatile boolean detailLevelRequired;

    @GetMapping(path = "/{orderId}", produces = "application/json")
    public OrderView findOrder(@PathVariable("orderId") String orderId,
                               @RequestParam(value = "detailLevel", required = false) String detailLevel) {
        if (detailLevelRequired && detailLevel == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "detailLevel is required in the revised fixture contract");
        methodCalls.incrementAndGet();
        return new OrderView(orderId, detailLevel, "PAID", "synthetic-controller-value");
    }

    public int methodCalls() {
        return methodCalls.get();
    }

    public void requireDetailLevel(boolean required) { detailLevelRequired = required; }

    public static final class OrderView {
        private final String orderId;
        private final String detailLevel;
        private final String state;
        private final String apiKey;

        public OrderView(String orderId, String detailLevel, String state, String apiKey) {
            this.orderId = orderId;
            this.detailLevel = detailLevel;
            this.state = state;
            this.apiKey = apiKey;
        }

        public String getOrderId() { return orderId; }
        public String getDetailLevel() { return detailLevel; }
        public String getState() { return state; }
        public String getApiKey() { return apiKey; }
    }
}
