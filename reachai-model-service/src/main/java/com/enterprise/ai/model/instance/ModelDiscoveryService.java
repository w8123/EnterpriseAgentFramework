package com.enterprise.ai.model.instance;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.runtime.OpenAiCompatibleRuntimeClient;
import com.enterprise.ai.model.runtime.UpstreamModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用草稿或已保存实例的连接配置，实时向供应商查询当前凭证可用的模型。
 * 这是模型目录之外的第二条“跟上最新模型”路径：不依赖平台模板是否更新。
 */
@Service
@RequiredArgsConstructor
public class ModelDiscoveryService {

    /** 草稿还没填模型名时，仅用于通过运行时构建校验；不会发给供应商。 */
    static final String DISCOVERY_PLACEHOLDER_MODEL = "model-discovery";

    private final ModelInstanceService modelInstanceService;
    private final OpenAiCompatibleRuntimeClient runtimeClient;

    public ModelDiscoveryResponse discover(ModelInstanceRequest request) {
        if (request != null && !StringUtils.hasText(request.getModelName())) {
            request.setModelName(DISCOVERY_PLACEHOLDER_MODEL);
        }
        ModelInstanceRuntime runtime;
        try {
            runtime = modelInstanceService.buildRuntimeForDraft(request);
        } catch (IllegalArgumentException e) {
            return failure(request, e.getMessage());
        } catch (BizException e) {
            if (e.getCode() == 404 || e.getCode() >= 500) {
                throw e;
            }
            return failure(request, e.getMessage());
        }
        try {
            List<UpstreamModel> models = runtimeClient.listModels(runtime);
            return new ModelDiscoveryResponse(true, models.isEmpty() ? "供应商返回了空的模型列表" : "ok",
                    runtime.getProvider(), models.size(), models);
        } catch (Exception e) {
            String message = e instanceof BizException be ? be.getMessage() : e.getMessage();
            return new ModelDiscoveryResponse(false, friendlyMessage(e, ConnectionConfigSupport.sanitizeError(message)),
                    runtime.getProvider(), 0, List.of());
        }
    }

    static String friendlyMessage(Exception e, String detail) {
        int status = e instanceof BizException be ? be.getCode() : 0;
        return switch (status) {
            case 401, 403 -> "API Key 无效或没有权限（HTTP " + status + "）。供应商原文：" + detail;
            case 404 -> "该服务地址没有提供模型列表接口（HTTP 404），请直接填写模型名称。供应商原文：" + detail;
            default -> detail;
        };
    }

    private ModelDiscoveryResponse failure(ModelInstanceRequest request, String message) {
        return new ModelDiscoveryResponse(false, ConnectionConfigSupport.sanitizeError(message),
                request == null ? null : request.getProvider(), 0, List.of());
    }
}
