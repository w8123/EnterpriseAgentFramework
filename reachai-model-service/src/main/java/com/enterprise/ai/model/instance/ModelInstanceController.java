package com.enterprise.ai.model.instance;

import com.enterprise.ai.common.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/model/instances")
@RequiredArgsConstructor
public class ModelInstanceController {

    private final ModelInstanceService service;
    private final ModelInstanceTestService testService;

    @GetMapping
    public ApiResult<List<ModelInstanceResponse>> list(@RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String provider,
                                                       @RequestParam(required = false) String modelType,
                                                       @RequestParam(required = false) String projectCode,
                                                       @RequestParam(required = false, defaultValue = "false") boolean includeArchived) {
        return ApiResult.ok(service.list(projectCode, modelType, provider, keyword, includeArchived));
    }

    @GetMapping("/{id}")
    public ApiResult<ModelInstanceResponse> get(@PathVariable("id") String id) {
        return ApiResult.ok(service.get(id));
    }

    @PostMapping
    public ApiResult<ModelInstanceResponse> create(@RequestBody ModelInstanceRequest request) {
        return ApiResult.ok(service.create(request));
    }

    @PostMapping("/from-template/{templateId}")
    public ApiResult<ModelInstanceResponse> createFromTemplate(@PathVariable("templateId") String templateId,
                                                               @RequestBody ModelInstanceRequest request) {
        return ApiResult.ok(service.createFromTemplate(templateId, request));
    }

    @PutMapping("/{id}")
    public ApiResult<ModelInstanceResponse> update(@PathVariable("id") String id,
                                                   @RequestBody ModelInstanceRequest request) {
        request.setId(id);
        return ApiResult.ok(service.update(id, request));
    }

    @PostMapping("/test-draft")
    public ApiResult<ModelInstanceTestResponse> testDraft(@RequestBody ModelInstanceRequest request) {
        return ApiResult.ok(testService.testDraft(request));
    }

    @PostMapping("/{id}/test")
    public ApiResult<ModelInstanceTestResponse> test(@PathVariable("id") String id) {
        return ApiResult.ok(testService.testSaved(id));
    }

    @PostMapping("/{id}/archive")
    public ApiResult<ModelInstanceResponse> archive(@PathVariable("id") String id) {
        return ApiResult.ok(service.archive(id));
    }
}
