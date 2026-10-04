package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleBusinessMethodInputValidatorTest {

    @Test
    void parallelSdkDtoDeclarationUsesTheSameLogicalShapeForFlatAndWrappedInput() {
        ToolDefinitionEntity tool = tool("["
                + "{\"name\":\"request\",\"type\":\"object\",\"required\":true},"
                + "{\"name\":\"request.orderNo\",\"type\":\"string\",\"required\":true},"
                + "{\"name\":\"request.phone\",\"type\":\"string\",\"required\":false,"
                + "\"metadata\":{\"sensitive\":true,\"example\":\"should-not-be-displayed\"}}]");

        assertDoesNotThrow(() -> ConsoleBusinessMethodInputValidator.validate(tool,
                Map.of("orderNo", "O-42", "phone", "sentinel-phone")));
        assertDoesNotThrow(() -> ConsoleBusinessMethodInputValidator.validate(tool,
                Map.of("request", Map.of("orderNo", "O-42", "phone", "sentinel-phone"))));

        ConsoleBusinessMethodInputValidator.InvalidInputException invalid = assertThrows(
                ConsoleBusinessMethodInputValidator.InvalidInputException.class,
                () -> ConsoleBusinessMethodInputValidator.validate(tool, Map.of("phone", "sentinel-phone")));
        assertEquals("orderNo", invalid.diagnostics().get(0).path());
        assertEquals("REQUIRED", invalid.diagnostics().get(0).reason());
        assertFalse(invalid.safeMetadata().toString().contains("sentinel-phone"));
    }

    @Test
    void mapAndArrayShapesAreValidatedWithoutPretendingUnknownShapeIsClosed() {
        ToolDefinitionEntity map = tool("[{\"name\":\"attributes\",\"type\":\"object\",\"required\":true,"
                + "\"metadata\":{\"openObject\":true}}]");
        assertDoesNotThrow(() -> ConsoleBusinessMethodInputValidator.validate(map,
                Map.of("attributes", Map.of("dynamic", Map.of("nested", 1)))));

        ToolDefinitionEntity primitiveArray = tool("[{\"name\":\"quantities\",\"type\":\"array\",\"required\":true,"
                + "\"metadata\":{\"itemsType\":\"integer\",\"itemsMetadata\":{\"minimum\":1}}}]");
        assertDoesNotThrow(() -> ConsoleBusinessMethodInputValidator.validate(primitiveArray, Map.of("quantities", List.of(1, 2))));
        ConsoleBusinessMethodInputValidator.InvalidInputException invalid = assertThrows(
                ConsoleBusinessMethodInputValidator.InvalidInputException.class,
                () -> ConsoleBusinessMethodInputValidator.validate(primitiveArray, Map.of("quantities", List.of("wrong"))));
        assertEquals("quantities[0]", invalid.diagnostics().get(0).path());
        assertEquals("TYPE_INTEGER", invalid.diagnostics().get(0).reason());

        ToolDefinitionEntity unknownArray = tool("[{\"name\":\"legacy\",\"type\":\"array\",\"required\":true}]");
        ConsoleBusinessMethodInputValidator.ValidationResult result = ConsoleBusinessMethodInputValidator.validate(
                unknownArray, Map.of("legacy", List.of(Map.of("anything", "allowed"))));
        assertEquals(List.of(Map.of("path", "legacy[]", "reason", "ARRAY_ELEMENT_SHAPE_UNSPECIFIED")),
                result.safeMetadata().get("inputDiagnostics"));
    }

    @Test
    void unsupportedAndShapeLessDeclarationsFailClosedBeforeOutboundDispatch() {
        ToolDefinitionEntity unsupported = tool("[{\"name\":\"request\",\"type\":\"mystery\",\"required\":true,"
                + "\"children\":[{\"name\":\"orderNo\",\"type\":\"string\",\"required\":true}]}]");
        ConsoleBusinessMethodInputValidator.InvalidInputException unsupportedFailure = assertThrows(
                ConsoleBusinessMethodInputValidator.InvalidInputException.class,
                () -> ConsoleBusinessMethodInputValidator.validate(unsupported, Map.of("request", Map.of("orderNo", "O-1"))));
        assertEquals("UNSUPPORTED_DECLARATION_TYPE", unsupportedFailure.diagnostics().get(0).reason());

        ConsoleBusinessMethodInputValidator.InvalidInputException noShape = assertThrows(
                ConsoleBusinessMethodInputValidator.InvalidInputException.class,
                () -> ConsoleBusinessMethodInputValidator.validate(tool("[]"), Map.of("unknown", "value")));
        assertEquals("DECLARATION_SHAPE_UNAVAILABLE", noShape.diagnostics().get(0).reason());
        assertTrue(noShape.safeMetadata().toString().contains("DECLARATION_SHAPE_UNAVAILABLE"));
    }

    private ToolDefinitionEntity tool(String parametersJson) {
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setParametersJson(parametersJson);
        return tool;
    }
}
