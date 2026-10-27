package com.enterprise.ai.agent.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphSpecLoopConfigTest {

    @Test
    void preservesFlatConfigurationDefaultsAndLegacyAliases() {
        GraphSpecLoopConfig config = GraphSpecLoopConfig.from(node(Map.of(
                "itemExpression", " var.items ",
                "loopKey", " results ",
                "bodyEntry", " body ")));

        assertEquals("FOREACH", config.mode());
        assertEquals("var.items", config.collection());
        assertEquals("item", config.itemAlias());
        assertEquals("index", config.indexAlias());
        assertEquals("results", config.outputAlias());
        assertEquals("lastOutput", config.bodyOutput());
        assertEquals("body", config.bodyExit());
        assertEquals(List.of("body"), new ArrayList<>(config.bodyNodeIds()));
        assertEquals(100, config.maxIterations());
    }

    @Test
    void nestedValuesWinAndMissingValuesFallBackToTheOuterConfiguration() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("collection", "var.nested");
        nested.put("outputAlias", null);
        nested.put("bodyEntry", "nested-body");
        nested.put("maxIterations", 2);
        GraphSpecLoopConfig config = GraphSpecLoopConfig.from(node(Map.of(
                "loopConfig", nested,
                "collection", "var.outer",
                "outputAlias", "results",
                "bodyEntry", "outer-body",
                "bodyOutput", "nodeOutput.nested-body",
                "maxIterations", 1)));

        assertEquals("var.nested", config.collection());
        assertEquals("nested-body", config.bodyEntry());
        assertEquals("results", config.outputAlias());
        assertEquals("nodeOutput.nested-body", config.bodyOutput());
        assertEquals(2, config.maxIterations());
        assertNull(nested.get("outputAlias"));
    }

    @Test
    void snapshotsAnOrderedBodyWithoutMutatingTheSource() {
        List<Object> rawIds = new ArrayList<>(List.of(" second ", "first", "second", " "));
        rawIds.add(null);
        GraphSpecLoopConfig config = GraphSpecLoopConfig.from(node(Map.of(
                "bodyNodeIds", rawIds, "bodyEntry", "first", "bodyExit", "last")));

        assertEquals(List.of("second", "first", "last"), new ArrayList<>(config.bodyNodeIds()));
        rawIds.clear();
        assertEquals(3, config.bodyNodeIds().size());
        assertThrows(UnsupportedOperationException.class, () -> config.bodyNodeIds().add("extra"));
    }

    @ParameterizedTest
    @MethodSource("iterationLimits")
    void preservesIterationLimitParsing(Object input, Integer expected) {
        assertEquals(expected, GraphSpecLoopConfig.parseMaxIterations(input));
    }

    static Stream<Arguments> iterationLimits() {
        return Stream.of(
                Arguments.of(null, 100),
                Arguments.of(" \t ", 100),
                Arguments.of(1, 1),
                Arguments.of(1000L, 1000),
                Arguments.of(" 12 ", 12),
                Arguments.of(12.0D, 12),
                Arguments.of(0, null),
                Arguments.of(1001, null),
                Arguments.of(1.5D, null),
                Arguments.of(4294967297L, null),
                Arguments.of(new BigInteger("18446744073709551617"), null),
                Arguments.of(new BigDecimal("1.9"), null),
                Arguments.of(Double.NaN, null),
                Arguments.of(Double.POSITIVE_INFINITY, null),
                Arguments.of("1.0", null),
                Arguments.of("not-a-number", null));
    }

    private GraphSpec.Node node(Map<String, Object> config) {
        return GraphSpec.Node.builder().id("loop").type("LOOP").config(config).build();
    }
}
