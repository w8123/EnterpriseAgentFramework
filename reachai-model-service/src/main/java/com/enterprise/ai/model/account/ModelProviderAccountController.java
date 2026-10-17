package com.enterprise.ai.model.account;

import com.enterprise.ai.common.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 挂在 /model/instances 下，沿用现有 Nginx 与网关对模型实例接口的路由。 */
@RestController
@RequestMapping("/model/instances/provider-accounts")
@RequiredArgsConstructor
public class ModelProviderAccountController {

    private final ModelProviderAccountService service;

    @GetMapping
    public ApiResult<List<ModelProviderAccountResponse>> list(@RequestParam(required = false) String provider) {
        return ApiResult.ok(service.list(provider));
    }

    @GetMapping("/{id}")
    public ApiResult<ModelProviderAccountResponse> get(@PathVariable("id") String id) {
        return ApiResult.ok(service.get(id));
    }

    @PostMapping
    public ApiResult<ModelProviderAccountResponse> create(@RequestBody ModelProviderAccountRequest request) {
        return ApiResult.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResult<ModelProviderAccountResponse> update(@PathVariable("id") String id,
                                                          @RequestBody ModelProviderAccountRequest request) {
        return ApiResult.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable("id") String id) {
        service.delete(id);
        return ApiResult.ok(null);
    }

    /** 不保存，用填写的地址和 Key 试取模型列表。 */
    @PostMapping("/discover")
    public ApiResult<ProviderModelsResponse> discoverDraft(@RequestBody ModelProviderAccountRequest request) {
        return ApiResult.ok(service.discoverDraft(request));
    }

    @PostMapping("/{id}/models")
    public ApiResult<ProviderModelsResponse> discover(@PathVariable("id") String id) {
        return ApiResult.ok(service.discoverSaved(id));
    }

    @PostMapping("/{id}/instances")
    public ApiResult<ProviderAccountInstancesResponse> createInstances(@PathVariable("id") String id,
                                                                       @RequestBody ProviderAccountInstancesRequest request) {
        return ApiResult.ok(service.createInstances(id, request));
    }
}
