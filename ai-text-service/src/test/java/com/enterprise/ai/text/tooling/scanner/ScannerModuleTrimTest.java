package com.enterprise.ai.text.tooling.scanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScannerModuleTrimTest {

    @Test
    void scannerClassesNowLiveUnderAiTextService() {
        assertDoesNotThrow(() -> Class.forName("com.enterprise.ai.text.tooling.scanner.openapi.OpenApiToolManifestScanner"));
        assertDoesNotThrow(() -> Class.forName("com.enterprise.ai.text.tooling.scanner.controller.ControllerAnnotationToolManifestScanner"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.enterprise.ai.skill.scanner.openapi.OpenApiToolManifestScanner"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.enterprise.ai.skill.scanner.controller.ControllerAnnotationToolManifestScanner"));
    }

    @Test
    void noLongerShipsCliOrCodeGenerator() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.enterprise.ai.skill.scanner.cli.SkillScannerCli"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.enterprise.ai.skill.scanner.generator.SkillServiceProjectGenerator"));
    }
}
