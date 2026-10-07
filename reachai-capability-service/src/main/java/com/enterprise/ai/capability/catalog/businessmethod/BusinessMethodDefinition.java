package com.enterprise.ai.capability.catalog.businessmethod;

import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;

/** An owner read, including its immutable accepted source and current source availability. */
public record BusinessMethodDefinition(BusinessMethodAssetEntity asset,
                                       BusinessMethodRevisionEntity revision,
                                       CapabilityRegistration declaration,
                                       String projectName,
                                       String sourceAvailability) {
}
