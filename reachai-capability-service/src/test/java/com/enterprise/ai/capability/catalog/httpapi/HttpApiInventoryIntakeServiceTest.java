package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HttpApiInventoryIntakeServiceTest {
    @BeforeAll
    static void mappingMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "inventory-test"),
                HttpApiSourceBindingEntity.class);
    }

    private final HttpApiAssetService assets = mock(HttpApiAssetService.class);
    private final HttpApiSourceBindingMapper bindings = mock(HttpApiSourceBindingMapper.class);
    private final HttpApiInventoryStateService inventories = mock(HttpApiInventoryStateService.class);
    private final HttpApiInventoryIntakeService intake = new HttpApiInventoryIntakeService(assets, bindings, inventories);
    private final HttpApiServiceScope scope = new HttpApiServiceScope(7L, "orders", "dev");

    @Test
    void missingInventoryRecordsUnknownWithoutReadingOrRemovingSources() {
        var summary = intake.observe(scope, HttpApiSourceKind.STARTER_MVC, false, false, List.of(), "未确认");
        assertFalse(summary.supported());
        assertFalse(summary.complete());
        assertEquals(0, summary.removed());
        verifyNoInteractions(assets, bindings);
        verify(inventories).record(scope, HttpApiSourceKind.STARTER_MVC, false, false, "未确认", List.of());
    }

    @Test
    void partialInventoryConfirmsReportedMembershipWithoutRemovingUnreportedBindings() {
        var request = request(scope, HttpApiSourceKind.CONTROLLER_SCAN, "reported");
        var binding = new HttpApiSourceBindingEntity();
        binding.setId(19L);
        when(assets.observe(request)).thenReturn(new HttpApiAssetService.Observation(null, binding, false));

        var summary = intake.observe(scope, HttpApiSourceKind.CONTROLLER_SCAN, true, false,
                List.of(request), "清单不完整");

        assertEquals(1, summary.observed());
        assertEquals(0, summary.removed());
        verifyNoInteractions(bindings);
        verify(assets, never()).remove(any());
        verify(inventories).record(scope, HttpApiSourceKind.CONTROLLER_SCAN, true, false,
                "清单不完整", List.of(19L));
    }

    @ParameterizedTest
    @EnumSource(value = HttpApiSourceKind.class, names = {"STARTER_MVC", "CONTROLLER_SCAN", "OPENAPI_SCAN"})
    void completeEmptyInventoryUsesExactProjectEnvironmentAndSourceKind(HttpApiSourceKind kind) {
        var prior = new HttpApiSourceBindingEntity();
        prior.setSourceKey("prior");
        when(bindings.selectList(any())).thenReturn(List.of(prior));
        var removal = new HttpApiAssetService.RemoveRequest(scope, kind, "prior");
        when(assets.remove(removal)).thenReturn(new HttpApiAssetService.Removal(null, true));

        var summary = intake.observe(scope, kind, true, true, List.of(), null);

        assertEquals(1, summary.removed());
        ArgumentCaptor<Wrapper<HttpApiSourceBindingEntity>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(bindings).selectList(query.capture());
        var wrapper = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HttpApiSourceBindingEntity>) query.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("project_id") && sql.contains("project_code") && sql.contains("environment")
                && sql.contains("source_kind") && sql.contains("status"));
        assertTrue(wrapper.getParamNameValuePairs().values().containsAll(List.of(7L, "orders", "dev", kind.name(), "REMOVED")));
        verify(assets).remove(removal);
        verify(inventories).record(scope, kind, true, true, null, List.of());
    }

    @Test
    void mixedScopeIsRejectedBeforeTheFirstObservationOrLedgerWrite() {
        var valid = request(scope, HttpApiSourceKind.OPENAPI_SCAN, "first");
        var foreign = request(new HttpApiServiceScope(8L, "other", "dev"), HttpApiSourceKind.OPENAPI_SCAN, "second");
        assertThrows(IllegalArgumentException.class, () -> intake.observe(scope, HttpApiSourceKind.OPENAPI_SCAN,
                true, true, List.of(valid, foreign), null));
        assertThrows(IllegalArgumentException.class, () -> intake.observe(scope, HttpApiSourceKind.OPENAPI_SCAN,
                true, true, List.of(request(scope, HttpApiSourceKind.STARTER_MVC, "wrong-kind")), null));
        verifyNoInteractions(assets, bindings, inventories);
    }

    @Test
    void oversizedInventoryIsRejectedBeforeDecodingOrSourceValidation() {
        assertThrows(IllegalArgumentException.class, () -> intake.prepare(scope, HttpApiSourceKind.OPENAPI_SCAN,
                Collections.nCopies(5001, "item"), "清单", item -> {
                    fail("Oversized inventories must not be decoded");
                    return null;
                }));
        verifyNoInteractions(assets, bindings, inventories);
    }

    @Test
    void sharedObservationRequiresTheExistingOuterTransaction() throws Exception {
        Transactional boundary = HttpApiInventoryIntakeService.class.getMethod("observe",
                HttpApiServiceScope.class, HttpApiSourceKind.class, boolean.class, boolean.class,
                List.class, String.class).getAnnotation(Transactional.class);
        assertNotNull(boundary);
        assertEquals(Propagation.MANDATORY, boundary.propagation());
    }

    private HttpApiAssetService.ObserveRequest request(HttpApiServiceScope sourceScope, HttpApiSourceKind kind, String key) {
        return new HttpApiAssetService.ObserveRequest(sourceScope, kind, key, "source", "revision", null);
    }
}
