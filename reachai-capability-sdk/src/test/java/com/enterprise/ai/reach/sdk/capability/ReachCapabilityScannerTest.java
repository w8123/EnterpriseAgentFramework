package com.enterprise.ai.reach.sdk.capability;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
