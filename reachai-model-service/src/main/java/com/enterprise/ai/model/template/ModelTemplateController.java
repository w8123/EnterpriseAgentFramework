package com.enterprise.ai.model.template;

import com.enterprise.ai.common.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/model/templates")
@RequiredArgsConstructor
public class ModelTemplateController {

    private final ModelTemplateService service;

    @GetMapping
    public ApiResult<List<ModelTemplateResponse>> list(@RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String provider,
                                                       @RequestParam(required = false) String modelType,
                                                       @RequestParam(required = false) Boolean enabled) {
        return ApiResult.ok(service.list(keyword, provider, modelType, enabled));
    }

    @GetMapping("/{id}")
    public ApiResult<ModelTemplateResponse> get(@PathVariable("id") String id) {
        return ApiResult.ok(service.get(id));
    }
}
