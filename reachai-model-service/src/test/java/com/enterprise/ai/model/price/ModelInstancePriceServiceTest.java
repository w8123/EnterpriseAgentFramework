package com.enterprise.ai.model.price;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ModelInstanceEntity;
import com.enterprise.ai.model.instance.ModelInstanceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelInstancePriceServiceTest {

    private final ModelInstancePriceMapper prices = mock(ModelInstancePriceMapper.class);
    private final ModelInstanceMapper models = mock(ModelInstanceMapper.class);
    private final ModelInstancePriceService service = new ModelInstancePriceService(prices, models);

    private static ModelInstanceEntity model(String id, String status) {
        ModelInstanceEntity model = new ModelInstanceEntity();
        model.setId(id);
        model.setName(id);
        model.setStatus(status);
        model.setModelType("LLM");
        return model;
    }

    @Test
    void savesNewPriceRoundedWithDefaultCurrency() {
        when(models.selectById("mi-1")).thenReturn(model("mi-1", "ACTIVE"));

        ModelInstancePriceView view = service.save("mi-1",
                new ModelInstancePriceRequest(new BigDecimal("1"), new BigDecimal("2.123456"), null));

        ArgumentCaptor<ModelInstancePriceEntity> row = ArgumentCaptor.forClass(ModelInstancePriceEntity.class);
        verify(prices).insert(row.capture());
        assertEquals("CNY", row.getValue().getCurrency());
        assertEquals(new BigDecimal("2.1235"), view.outputPricePerMillion());
    }

    @Test
    void rejectsNegativePriceUnknownCurrencyAndMissingModel() {
        when(models.selectById("mi-1")).thenReturn(model("mi-1", "ACTIVE"));
        assertThrows(BizException.class, () -> service.save("mi-1",
                new ModelInstancePriceRequest(new BigDecimal("-1"), null, "CNY")));
        assertThrows(BizException.class, () -> service.save("mi-1",
                new ModelInstancePriceRequest(BigDecimal.ONE, null, "EUR")));
        assertThrows(BizException.class, () -> service.save("nope", new ModelInstancePriceRequest(null, null, null)));
        verify(prices, never()).insert(any(ModelInstancePriceEntity.class));
    }

    @Test
    void listHidesArchivedModelsWithoutPrice() {
        ModelInstancePriceEntity priced = new ModelInstancePriceEntity();
        priced.setModelInstanceId("old");
        priced.setInputPricePerMillion(BigDecimal.ONE);
        priced.setCurrency("CNY");
        when(prices.selectList(null)).thenReturn(List.of(priced));
        when(models.selectList(null)).thenReturn(List.of(model("a", "ACTIVE"), model("old", "ARCHIVED"),
                model("gone", "ARCHIVED")));

        List<ModelInstancePriceView> list = service.list();

        assertEquals(List.of("a", "old"), list.stream().map(ModelInstancePriceView::modelInstanceId).toList());
        assertNull(list.get(0).inputPricePerMillion());
    }
}
