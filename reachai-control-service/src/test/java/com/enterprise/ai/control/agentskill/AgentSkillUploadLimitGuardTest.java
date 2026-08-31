package com.enterprise.ai.control.agentskill;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSkillUploadLimitGuardTest {

    private static final long PACKAGE_BYTES = 20L * 1024L * 1024L;

    @Test
    void acceptsMultipartLimitsThatCanCarryTheAdvertisedPackage() {
        MultipartProperties multipart = multipart(PACKAGE_BYTES, PACKAGE_BYTES + 1024L * 1024L);

        assertDoesNotThrow(() -> guard(multipart).validate());
    }

    @Test
    void rejectsFileTransportLimitBelowTheAdvertisedPackageLimit() {
        MultipartProperties multipart = multipart(PACKAGE_BYTES - 1L, PACKAGE_BYTES + 1024L * 1024L);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> guard(multipart).validate());

        assertTrue(failure.getMessage().contains("max-file-size"));
    }

    @Test
    void rejectsRequestTransportLimitWithoutMultipartOverheadCapacity() {
        MultipartProperties multipart = multipart(PACKAGE_BYTES, PACKAGE_BYTES);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> guard(multipart).validate());

        assertTrue(failure.getMessage().contains("max-request-size"));
    }

    @Test
    void rejectsDisabledMultipartTransport() {
        MultipartProperties multipart = multipart(PACKAGE_BYTES, PACKAGE_BYTES + 1024L * 1024L);
        multipart.setEnabled(false);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> guard(multipart).validate());

        assertTrue(failure.getMessage().contains("must be enabled"));
    }

    private AgentSkillUploadLimitGuard guard(MultipartProperties multipart) {
        return new AgentSkillUploadLimitGuard(
                new AgentSkillPackageInspector(PACKAGE_BYTES, 100L * 1024L * 1024L,
                        PACKAGE_BYTES, 1024),
                multipart);
    }

    private MultipartProperties multipart(long maxFileBytes, long maxRequestBytes) {
        MultipartProperties value = new MultipartProperties();
        value.setEnabled(true);
        value.setMaxFileSize(DataSize.ofBytes(maxFileBytes));
        value.setMaxRequestSize(DataSize.ofBytes(maxRequestBytes));
        return value;
    }
}
