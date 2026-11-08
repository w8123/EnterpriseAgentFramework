package com.enterprise.ai.model.price;

import com.enterprise.ai.common.dto.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型价格：在模型中心填写，「AI 使用情况」按它估算费用。
 * 单个模型的读写走控制台已有的 /model/instances/{id}/... 代理（写需要该模型的 model:manage）；
 * 全量列表只给 Control 内部读取。
 */
@RestController
public class ModelInstancePriceController {

    private final ModelInstancePriceService service;

    public ModelInstancePriceController(ModelInstancePriceService service) {
        this.service = service;
    }

    @GetMapping("/internal/model/instance-prices")
    public ApiResult<List<ModelInstancePriceView>> list() {
        return ApiResult.ok(service.list());
    }

    @GetMapping("/model/instances/{modelInstanceId}/price")
    public ApiResult<ModelInstancePriceView> get(@PathVariable("modelInstanceId") String modelInstanceId) {
        return ApiResult.ok(service.get(modelInstanceId));
    }

    @PutMapping("/model/instances/{modelInstanceId}/price")
    public ApiResult<ModelInstancePriceView> save(@PathVariable("modelInstanceId") String modelInstanceId,
                                                  @RequestBody ModelInstancePriceRequest request) {
        return ApiResult.ok(service.save(modelInstanceId, request));
    }
}
