package com.enterprise.ai.agent.skill.slot.extractor.builtin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.skill.interactive.FieldSpec;
import com.enterprise.ai.agent.skill.slot.dict.SlotUserEntity;
import com.enterprise.ai.agent.skill.slot.dict.SlotUserMapper;
import com.enterprise.ai.agent.skill.slot.extractor.ExtractContext;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractResult;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 人员提取器：基于 {@code slot_dict_user}。
 * 同名人员消歧：若 ctx.userDeptId 不空，则同部门优先；否则按 employee_no 唯一匹配；
 * 仍无法消歧时返回低置信结果，由 LLM 兜底/用户确认。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSlotExtractor implements SlotExtractor {

    private final SlotUserMapper userMapper;

    @Override
    public String name() { return "user"; }

    @Override
    public String displayName() { return "人员"; }

    @Override
    public int priority() { return 50; }

    @Override
    public boolean accepts(FieldSpec field, ExtractContext ctx) {
        if (field == null) return false;
        String sig = sig(field);
        return contains(sig, "user", "person", "owner", "people", "员工", "人员", "申请人", "经办人", "审批人", "负责人");
    }

    @Override
    public Optional<SlotExtractResult> extract(String userText, FieldSpec field, ExtractContext ctx) {
        if (userText == null || userText.isBlank()) return Optional.empty();
        try {
            LambdaQueryWrapper<SlotUserEntity> w = new LambdaQueryWrapper<>();
            w.eq(SlotUserEntity::getEnabled, true).last("LIMIT 20000");
            List<SlotUserEntity> all = userMapper.selectList(w);
            if (all.isEmpty()) return Optional.empty();

            // 1) 工号优先
            for (SlotUserEntity u : all) {
                if (u.getEmployeeNo() != null && !u.getEmployeeNo().isBlank()
                        && userText.contains(u.getEmployeeNo())) {
                    return Optional.of(SlotExtractResult.high(String.valueOf(u.getId()),
                            "matched 工号: " + u.getEmployeeNo() + " -> " + u.getName()));
                }
            }

            // 2) name 包含
            List<SlotUserEntity> nameHits = new ArrayList<>();
            for (SlotUserEntity u : all) {
                if (u.getName() == null || u.getName().isBlank()) continue;
                if (userText.contains(u.getName())) nameHits.add(u);
            }
            if (nameHits.size() == 1) {
                SlotUserEntity u = nameHits.get(0);
                return Optional.of(SlotExtractResult.high(String.valueOf(u.getId()),
                        "matched 唯一人员: " + u.getName()));
            }
            if (nameHits.size() > 1) {
                Long ctxDept = parseDeptId(ctx);
                if (ctxDept != null) {
                    for (SlotUserEntity u : nameHits) {
                        if (ctxDept.equals(u.getDeptId())) {
                            return Optional.of(SlotExtractResult.of(String.valueOf(u.getId()), 0.85,
                                    "matched 同部门优先: " + u.getName() + " (deptId=" + ctxDept + ")"));
                        }
                    }
                }
                SlotUserEntity first = nameHits.get(0);
                return Optional.of(SlotExtractResult.of(String.valueOf(first.getId()), 0.55,
                        "matched 同名 " + nameHits.size() + " 人，取首个: " + first.getName()));
            }

            // 3) 别名
            for (SlotUserEntity u : all) {
                if (u.getAliases() == null || u.getAliases().isBlank()) continue;
                for (String a : u.getAliases().split(",")) {
                    String t = a.trim();
                    if (!t.isEmpty() && userText.contains(t)) {
                        return Optional.of(SlotExtractResult.of(String.valueOf(u.getId()), 0.8,
                                "matched 别名: " + t + " -> " + u.getName()));
                    }
                }
            }

            return Optional.empty();
        } catch (Exception ex) {
            log.warn("[UserSlotExtractor] 字典查询异常: {}", ex.toString());
            return Optional.empty();
        }
    }

    private static Long parseDeptId(ExtractContext ctx) {
        if (ctx == null || ctx.userDeptId() == null || ctx.userDeptId().isBlank()) return null;
        try {
            return Long.parseLong(ctx.userDeptId().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of("dictTable", "slot_dict_user", "disambiguate", "工号 > name > 同部门优先 > 别名");
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
