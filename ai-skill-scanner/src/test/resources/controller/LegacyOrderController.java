package com.demo.legacy;

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

    /**
     * 创建订单
     */
    @PostMapping
    public ApiResult<CreateOrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        return null;
    }
}
