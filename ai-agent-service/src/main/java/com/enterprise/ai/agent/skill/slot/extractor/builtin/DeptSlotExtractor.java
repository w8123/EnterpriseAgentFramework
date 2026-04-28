package com.enterprise.ai.agent.skill.slot.extractor.builtin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.skill.interactive.FieldSpec;
import com.enterprise.ai.agent.skill.slot.dict.SlotDeptEntity;
import com.enterprise.ai.agent.skill.slot.dict.SlotDeptMapper;
import com.enterprise.ai.agent.skill.slot.extractor.ExtractContext;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractResult;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 部门提取器：基于 {@code slot_dict_dept} 字典 + 别名 + 拼音模糊匹配。
 * 优先级：name 完全包含 > 别名包含 > 拼音 contains。匹配出的 value 是部门 ID 字符串。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeptSlotExtractor implements SlotExtractor {

    private final SlotDeptMapper deptMapper;

    @Override
    public String name() { return "dept"; }

    @Override
    public String displayName() { return "部门"; }

    @Override
    public int priority() { return 40; }

    @Override
    public boolean accepts(FieldSpec field, ExtractContext ctx) {
        if (field == null) return false;
        String sig = sig(field);
        return contains(sig, "dept", "department", "部门", "科室", "team", "组织");
    }

    @Override
    public Optional<SlotExtractResult> extract(String userText, FieldSpec field, ExtractContext ctx) {
        if (userText == null || userText.isBlank()) return Optional.empty();
        try {
            LambdaQueryWrapper<SlotDeptEntity> w = new LambdaQueryWrapper<>();
            w.eq(SlotDeptEntity::getEnabled, true).last("LIMIT 5000");
            List<SlotDeptEntity> all = deptMapper.selectList(w);
            if (all.isEmpty()) return Optional.empty();

            String asciiUser = PinyinUtils.fallback(userText);

            // 1) name 包含（最长优先）
            SlotDeptEntity nameHit = null;
            for (SlotDeptEntity d : all) {
                if (d.getName() == null || d.getName().isBlank()) continue;
                if (userText.contains(d.getName())) {
                    if (nameHit == null || d.getName().length() > nameHit.getName().length()) {
                        nameHit = d;
                    }
                }
            }
            if (nameHit != null) {
                return Optional.of(SlotExtractResult.high(String.valueOf(nameHit.getId()),
                        "matched 部门名: " + nameHit.getName()));
            }

            // 2) 别名包含
            for (SlotDeptEntity d : all) {
                if (d.getAliases() == null || d.getAliases().isBlank()) continue;
                for (String a : d.getAliases().split(",")) {
                    String t = a.trim();
                    if (!t.isEmpty() && userText.contains(t)) {
                        return Optional.of(SlotExtractResult.of(String.valueOf(d.getId()), 0.85,
                                "matched 部门别名: " + t + " -> " + d.getName()));
                    }
                }
            }

            // 3) 拼音 contains
            if (!asciiUser.isBlank()) {
                for (SlotDeptEntity d : all) {
                    if (d.getPinyin() == null || d.getPinyin().isBlank()) continue;
                    if (PinyinUtils.pinyinContains(d.getPinyin(), asciiUser)) {
                        return Optional.of(SlotExtractResult.of(String.valueOf(d.getId()), 0.65,
                                "matched 部门拼音: " + d.getPinyin() + " -> " + d.getName()));
                    }
                }
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("[DeptSlotExtractor] 字典查询异常，跳过: {}", ex.toString());
            return Optional.empty();
        }
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of("dictTable", "slot_dict_dept", "matchOrder", "name 包含 > 别名 > 拼音");
    }

    private static String sig(FieldSpec f) {
        return ((f.getKey() == null ? "" : f.getKey()) + " "
                + (f.getLabel() == null ? "" : f.getLabel()) + " "
                + (f.getLlmExtractHint() == null ? "" : f.getLlmExtractHint()))
                .toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String t, String... needles) {
        for (String n : needles) if (t.contains(n)) return true;
        return false;
    }
}
