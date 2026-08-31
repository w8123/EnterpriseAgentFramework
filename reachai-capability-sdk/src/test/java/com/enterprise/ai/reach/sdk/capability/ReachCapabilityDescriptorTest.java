package com.enterprise.ai.reach.sdk.capability;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReachCapabilityDescriptorTest {

    @Test
    void tagsAreDefensivelyCopiedAndUnmodifiable() {
        ReachCapabilityDescriptor descriptor = new ReachCapabilityDescriptor();

        List<String> original = new ArrayList<String>(Arrays.asList("a", "b"));
        descriptor.setTags(original);
        original.add("c");

        assertEquals(Arrays.asList("a", "b"), descriptor.getTags());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.getTags().add("d"));

        descriptor.setTags(null);
        assertNotNull(descriptor.getTags());
        assertEquals(0, descriptor.getTags().size());
    }

    @Test
    void requiredRolesAreDefensivelyCopiedAndUnmodifiable() {
        ReachCapabilityDescriptor descriptor = new ReachCapabilityDescriptor();

        List<String> original = new ArrayList<String>(Arrays.asList("admin", "operator"));
        descriptor.setRequiredRoles(original);
        original.remove(0);

        assertEquals(Arrays.asList("admin", "operator"), descriptor.getRequiredRoles());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.getRequiredRoles().add("guest"));

        descriptor.setRequiredRoles(null);
        assertNotNull(descriptor.getRequiredRoles());
        assertEquals(0, descriptor.getRequiredRoles().size());
    }

    @Test
    void parametersAreDefensivelyCopiedAndUnmodifiable() {
        ReachCapabilityDescriptor descriptor = new ReachCapabilityDescriptor();

        List<ReachCapabilityParameter> original = new ArrayList<ReachCapabilityParameter>();
        ReachCapabilityParameter parameter = new ReachCapabilityParameter();
        parameter.setName("request");
        original.add(parameter);
        descriptor.setParameters(original);
        original.remove(0);

        assertEquals(1, descriptor.getParameters().size());
        assertEquals("request", descriptor.getParameters().get(0).getName());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.getParameters().add(new ReachCapabilityParameter()));

        descriptor.setParameters(null);
        assertNotNull(descriptor.getParameters());
        assertEquals(0, descriptor.getParameters().size());
    }
}
