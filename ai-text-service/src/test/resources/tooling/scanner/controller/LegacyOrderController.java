package com.demo.order;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class LegacyOrderController {

    /**
     * 查询订单详情
     */
    @GetMapping("/{orderId}")
    public ApiResult<OrderDetailResponse> getOrder(
            @PathVariable("orderId") String orderId,
            @RequestParam(value = "detailLevel", required = false) String detailLevel) {
        return null;
    }

    @PostMapping
    public ApiResult<String> createOrder(@RequestBody CreateOrderRequest request) {
        return null;
    }

    public static class ApiResult<T> {
        private final T data;

        public ApiResult(T data) {
            this.data = data;
        }

        public T getData() {
            return data;
        }
    }

    public static class OrderDetailResponse {
        private final String orderId;

        public OrderDetailResponse(String orderId) {
            this.orderId = orderId;
        }

        public String getOrderId() {
            return orderId;
        }
    }

    public static class CreateOrderRequest {
        private final String productCode;
        private final int quantity;

        public CreateOrderRequest(String productCode, int quantity) {
            this.productCode = productCode;
            this.quantity = quantity;
        }

        public String getProductCode() {
            return productCode;
        }

        public int getQuantity() {
            return quantity;
        }
    }
}
