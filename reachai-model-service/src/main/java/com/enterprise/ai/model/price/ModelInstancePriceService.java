package com.enterprise.ai.model.price;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ModelInstanceEntity;
import com.enterprise.ai.model.instance.ModelInstanceMapper;
import com.enterprise.ai.model.instance.ModelInstanceStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Model prices for cost estimates. A model without a price still shows its tokens, just no money. */
@Service
public class ModelInstancePriceService {

    static final Set<String> CURRENCIES = Set.of("CNY", "USD");
    static final BigDecimal MAX_PRICE = new BigDecimal("100000");

    private final ModelInstancePriceMapper priceMapper;
    private final ModelInstanceMapper instanceMapper;

    public ModelInstancePriceService(ModelInstancePriceMapper priceMapper, ModelInstanceMapper instanceMapper) {
        this.priceMapper = priceMapper;
        this.instanceMapper = instanceMapper;
    }

    /** Every non-archived model (plus archived ones that already have a price, so old usage keeps its cost). */
    public List<ModelInstancePriceView> list() {
        Map<String, ModelInstancePriceEntity> prices = new HashMap<>();
        for (ModelInstancePriceEntity price : priceMapper.selectList(null)) {
            prices.put(price.getModelInstanceId(), price);
        }
        return instanceMapper.selectList(null).stream()
                .filter(model -> !ModelInstanceStatus.ARCHIVED.name().equals(model.getStatus())
                        || prices.containsKey(model.getId()))
                .sorted(Comparator.comparing(ModelInstanceEntity::getName, Comparator.nullsLast(String::compareTo)))
                .map(model -> view(model, prices.get(model.getId())))
                .toList();
    }

    public ModelInstancePriceView get(String modelInstanceId) {
        ModelInstanceEntity model = StringUtils.hasText(modelInstanceId) ? instanceMapper.selectById(modelInstanceId) : null;
        if (model == null) {
            throw new BizException(404, "模型不存在");
        }
        return view(model, priceMapper.selectById(model.getId()));
    }

    public ModelInstancePriceView save(String modelInstanceId, ModelInstancePriceRequest request) {
        ModelInstanceEntity model = StringUtils.hasText(modelInstanceId) ? instanceMapper.selectById(modelInstanceId) : null;
        if (model == null) {
            throw new BizException(404, "模型不存在");
        }
        BigDecimal input = checkedPrice(request == null ? null : request.inputPricePerMillion(), "输入价格");
        BigDecimal output = checkedPrice(request == null ? null : request.outputPricePerMillion(), "输出价格");
        String currency = request == null || !StringUtils.hasText(request.currency())
                ? "CNY" : request.currency().trim().toUpperCase(Locale.ROOT);
        if (!CURRENCIES.contains(currency)) {
            throw new BizException(400, "币种只支持人民币（CNY）或美元（USD）");
        }
        ModelInstancePriceEntity entity = priceMapper.selectById(model.getId());
        boolean create = entity == null;
        if (create) {
            entity = new ModelInstancePriceEntity();
            entity.setModelInstanceId(model.getId());
        }
        entity.setInputPricePerMillion(input);
        entity.setOutputPricePerMillion(output);
        entity.setCurrency(currency);
        entity.setUpdatedAt(LocalDateTime.now());
        if (create) {
            priceMapper.insert(entity);
        } else {
            priceMapper.updateById(entity);
        }
        return view(model, entity);
    }

    static BigDecimal checkedPrice(BigDecimal value, String label) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0 || value.compareTo(MAX_PRICE) > 0) {
            throw new BizException(400, label + "要在 0 到 100000 之间（每百万 Token）");
        }
        return value.setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private static ModelInstancePriceView view(ModelInstanceEntity model, ModelInstancePriceEntity price) {
        return new ModelInstancePriceView(model.getId(), model.getName(), model.getModelName(), model.getProvider(),
                model.getModelType(), model.getStatus(),
                price == null ? null : price.getInputPricePerMillion(),
                price == null ? null : price.getOutputPricePerMillion(),
                price == null ? "CNY" : price.getCurrency(),
                price == null ? null : price.getUpdatedAt());
    }
}
