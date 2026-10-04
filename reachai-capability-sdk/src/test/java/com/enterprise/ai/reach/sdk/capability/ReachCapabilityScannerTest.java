package com.enterprise.ai.reach.sdk.capability;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachCapabilityScannerTest {

    static class ContractApi {
        @ReachCapability(
                name = "contract.query",
                title = "查询合同",
                description = "根据合同编号查询合同详情",
                domain = "contract",
                module = "review",
                sideEffect = ReachSideEffectLevel.READ,
                tags = {"contract", "review"},
                requiredRoles = {"contract_reader"})
        ContractDetail query(@ReachParam(description = "请求体", required = true) QueryContractRequest request) {
            return new ContractDetail();
        }

        String internalOnly(String contractNo) {
            return contractNo;
        }
    }

    static class QueryContractRequest {
        @ReachParam(description = "合同编号", required = true, example = "HT-2026-0001")
        private String contractNo;

        @ReachParam(description = "是否包含附件")
        private Boolean includeAttachments;
    }

    static class ContractDetail {
        private String contractNo;
    }

    @Test
    void scansReachCapabilityMethodsAndRequestFields() {
        List<ReachCapabilityDescriptor> descriptors = ReachCapabilityScanner.scanClasses(ContractApi.class);

        assertEquals(1, descriptors.size());
        ReachCapabilityDescriptor descriptor = descriptors.get(0);
        assertEquals("contract.query", descriptor.getName());
        assertEquals("查询合同", descriptor.getTitle());
        assertEquals("根据合同编号查询合同详情", descriptor.getDescription());
        assertEquals("contract", descriptor.getDomain());
        assertEquals("review", descriptor.getModule());
        assertEquals(ReachCapabilityAssetType.BUSINESS_METHOD, descriptor.getAssetType());
        assertEquals(ReachSideEffectLevel.READ, descriptor.getSideEffect());
        assertEquals("contract_reader", descriptor.getRequiredRoles().get(0));
        assertEquals("contract", descriptor.getTags().get(0));

        assertEquals(3, descriptor.getParameters().size());
        ReachCapabilityParameter body = descriptor.getParameters().get(0);
        assertEquals("request", body.getName());
        assertEquals("object", body.getType());
        assertTrue(body.isRequired());
        assertEquals("请求体", body.getDescription());

        ReachCapabilityParameter contractNo = descriptor.getParameters().get(1);
        assertEquals("request.contractNo", contractNo.getName());
        assertEquals("string", contractNo.getType());
        assertTrue(contractNo.isRequired());
        assertEquals("HT-2026-0001", contractNo.getExample());

        ReachCapabilityParameter includeAttachments = descriptor.getParameters().get(2);
        assertEquals("request.includeAttachments", includeAttachments.getName());
        assertEquals("boolean", includeAttachments.getType());
        assertFalse(includeAttachments.isRequired());

        assertEquals(ContractApi.class.getName(), descriptor.getClassName());
        assertEquals("query", descriptor.getMethodName());
        assertEquals(ContractDetail.class.getName(), descriptor.getReturnType());
    }

    static class NoAnnotationApi {
        String plain() {
            return "plain";
        }
    }

    static class MetadataApi {
        @ReachCapability(name = "   ", title = "  标题  ", description = "   ")
        String doQuery() {
            return "";
        }

        @ReachCapability(name = "  trimmed.name  ")
        String doOther() {
            return "";
        }
    }

    static class ExecutionApi {
        @ReachCapability(
                name = "exec.op",
                sideEffect = ReachSideEffectLevel.IRREVERSIBLE,
                tags = {"ta", "tb"},
                requiredRoles = {"ra", "rb"},
                timeoutMs = 123,
                retryLimit = 5)
        String execute() {
            return "";
        }
    }

    static class TypeMappingApi {
        @ReachCapability(name = "type.map")
        String map(
                @ReachParam(name = "  userName  ", required = true) String name,
                @ReachParam(name = " ") boolean flag,
                int count,
                Character initial,
                Object extra) {
            return "";
        }
    }

    static class NestedChild {
        @ReachParam(description = "child field")
        private String childField;
    }

    static class ExpandRequest {
        @ReachParam(
                name = "ignored",
                required = true,
                description = "  字段描述  ",
                example = "  ex ",
                sourceHint = "  ",
                dictType = "  dict  ",
                sensitive = true)
        private String fieldOne;

        @ReachParam(description = "nested")
        private NestedChild nested;

        private String noAnnotation;
    }

    static class ExpandApi {
        @ReachCapability(name = "expand.one")
        String expand(@ReachParam(description = "parent body") ExpandRequest body) {
            return "";
        }
    }

    enum SimpleStatus {
        ACTIVE("active"),
        INACTIVE("inactive");

        @ReachParam
        private final String code;

        SimpleStatus(String code) {
            this.code = code;
        }
    }

    static class SimpleTypesApi {
        @ReachCapability(name = "simple.types")
        String simple(int count, SimpleStatus status, String text, BigDecimal amount, LocalDate date, Date created) {
            return "";
        }
    }

    static class ContainerTypesApi {
        @ReachCapability(name = "container.types")
        String invoke(@ReachParam(name = "quantities") List<Integer> quantities,
                      @ReachParam(name = "labels") String[] labels,
                      @ReachParam(name = "attributes") Map<String, Object> attributes) {
            return "";
        }
    }

    @Test
    void returnsEmptyForNullVarargsAndSkipsNullEntries() {
        List<ReachCapabilityDescriptor> nullVarargs = ReachCapabilityScanner.scanClasses((Class<?>[]) null);
        assertEquals(0, nullVarargs.size());

        List<ReachCapabilityDescriptor> nullEntries = ReachCapabilityScanner.scanClasses(
                new Class<?>[]{null, ExecutionApi.class});
        assertEquals(1, nullEntries.size());
        assertEquals("exec.op", nullEntries.get(0).getName());
    }

    @Test
    void ignoresClassesWithoutAnnotatedMethods() {
        List<ReachCapabilityDescriptor> descriptors = ReachCapabilityScanner.scanClasses(NoAnnotationApi.class);
        assertEquals(0, descriptors.size());
    }

    @Test
    void fallsBackToMethodNameAndNormalizesOptionalMetadata() {
        List<ReachCapabilityDescriptor> descriptors = ReachCapabilityScanner.scanClasses(MetadataApi.class);

        ReachCapabilityDescriptor doQuery = findByMethodName(descriptors, "doQuery");
        assertEquals("doQuery", doQuery.getName());
        assertEquals("标题", doQuery.getTitle());
        assertNull(doQuery.getDescription());
        assertNull(doQuery.getDomain());
        assertNull(doQuery.getModule());

        ReachCapabilityDescriptor doOther = findByMethodName(descriptors, "doOther");
        assertEquals("trimmed.name", doOther.getName());
    }

    @Test
    void copiesCapabilityExecutionAndIdentityMetadata() {
        List<ReachCapabilityDescriptor> descriptors = ReachCapabilityScanner.scanClasses(ExecutionApi.class);
        assertEquals(1, descriptors.size());

        ReachCapabilityDescriptor descriptor = descriptors.get(0);
        assertEquals("exec.op", descriptor.getName());
        assertEquals(ReachSideEffectLevel.IRREVERSIBLE, descriptor.getSideEffect());
        assertEquals(Arrays.asList("ta", "tb"), descriptor.getTags());
        assertEquals(Arrays.asList("ra", "rb"), descriptor.getRequiredRoles());
        assertEquals(123, descriptor.getTimeoutMs());
        assertEquals(5, descriptor.getRetryLimit());
        assertEquals(ExecutionApi.class.getName(), descriptor.getClassName());
        assertEquals("execute", descriptor.getMethodName());
        assertEquals(String.class.getName(), descriptor.getReturnType());
    }

    @Test
    void usesTrimmedExplicitParameterNameAndMapsTypes() {
        List<ReachCapabilityDescriptor> descriptors = ReachCapabilityScanner.scanClasses(TypeMappingApi.class);
        assertEquals(1, descriptors.size());

        List<ReachCapabilityParameter> params = descriptors.get(0).getParameters();
        assertEquals(5, params.size());

        ReachCapabilityParameter name = paramByName(params, "userName");
        assertEquals("string", name.getType());
        assertTrue(name.isRequired());

        assertEquals("boolean", paramByName(params, "flag").getType());
        assertEquals("number", paramByName(params, "count").getType());
        assertEquals("string", paramByName(params, "initial").getType());
        assertEquals("object", paramByName(params, "extra").getType());
    }

    @Test
    void expandsAnnotatedFieldsOneLevelAndPropagatesMetadata() {
        List<ReachCapabilityDescriptor> descriptors = ReachCapabilityScanner.scanClasses(ExpandApi.class);
        assertEquals(1, descriptors.size());

        List<ReachCapabilityParameter> params = descriptors.get(0).getParameters();
        assertEquals(3, params.size());

        ReachCapabilityParameter parent = paramByName(params, "body");
        assertEquals("object", parent.getType());
        assertFalse(parent.isRequired());
        assertEquals("parent body", parent.getDescription());

        ReachCapabilityParameter field = paramByName(params, "body.fieldOne");
        assertEquals("string", field.getType());
        assertTrue(field.isRequired());
        assertEquals("字段描述", field.getDescription());
        assertEquals("ex", field.getExample());
        assertNull(field.getSourceHint());
        assertEquals("dict", field.getDictType());
        assertTrue(field.isSensitive());

        ReachCapabilityParameter nested = paramByName(params, "body.nested");
        assertEquals("object", nested.getType());
        assertEquals("nested", nested.getDescription());

        assertNull(paramByName(params, "body.nested.childField"));
        assertNull(paramByName(params, "body.noAnnotation"));
    }

    @Test
    void doesNotExpandSimpleTypes() {
        List<ReachCapabilityDescriptor> descriptors = ReachCapabilityScanner.scanClasses(SimpleTypesApi.class);
        assertEquals(1, descriptors.size());

        List<ReachCapabilityParameter> params = descriptors.get(0).getParameters();
        assertEquals(6, params.size());
        assertEquals("number", paramByName(params, "count").getType());
        assertEquals("object", paramByName(params, "status").getType());
        assertEquals("string", paramByName(params, "text").getType());
        assertEquals("number", paramByName(params, "amount").getType());
        assertEquals("object", paramByName(params, "date").getType());
        assertEquals("object", paramByName(params, "created").getType());
        assertNull(paramByName(params, "status.code"));
    }

    @Test
    void declaresCollectionElementAndOpenMapShapeWithoutExpandingThemAsDtos() {
        List<ReachCapabilityParameter> params = ReachCapabilityScanner.scanClasses(ContainerTypesApi.class)
                .get(0).getParameters();

        ReachCapabilityParameter quantities = paramByName(params, "quantities");
        assertEquals("array", quantities.getType());
        assertEquals("number", quantities.getItemsType());

        ReachCapabilityParameter labels = paramByName(params, "labels");
        assertEquals("array", labels.getType());
        assertEquals("string", labels.getItemsType());

        ReachCapabilityParameter attributes = paramByName(params, "attributes");
        assertEquals("object", attributes.getType());
        assertTrue(attributes.isOpenObject());
        assertNull(paramByName(params, "attributes.value"));
    }

    private static ReachCapabilityDescriptor findByMethodName(
            List<ReachCapabilityDescriptor> descriptors, String methodName) {
        for (ReachCapabilityDescriptor descriptor : descriptors) {
            if (descriptor.getMethodName().equals(methodName)) {
                return descriptor;
            }
        }
        return null;
    }

    private static ReachCapabilityParameter paramByName(
            List<ReachCapabilityParameter> parameters, String name) {
        for (ReachCapabilityParameter parameter : parameters) {
            if (parameter.getName().equals(name)) {
                return parameter;
            }
        }
        return null;
    }
}
