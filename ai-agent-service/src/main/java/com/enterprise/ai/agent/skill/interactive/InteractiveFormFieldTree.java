package com.enterprise.ai.agent.skill.interactive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * InteractiveFormSpec.fields 的树形展开：叶子槽位 key 仍为短名；提交 targetTool 时按 path 组装嵌套 Map。
 */
public final class InteractiveFormFieldTree {

    private InteractiveFormFieldTree() {
    }

    public record LeafBinding(FieldSpec field, List<String> pathPrefix) {
    }

    public static boolean hasNonEmptyChildren(FieldSpec f) {
        return f != null && f.getChildren() != null && !f.getChildren().isEmpty();
    }

    /**
     * 深度优先列出所有叶子；pathPrefix 为从根到该叶子父分组的路径（不含叶子自身 key）。
     */
    public static List<LeafBinding> flattenLeaves(List<FieldSpec> fields, List<String> parentPath) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        List<LeafBinding> out = new ArrayList<>();
        for (FieldSpec f : fields) {
            if (f == null) {
                continue;
            }
            if (hasNonEmptyChildren(f)) {
                List<String> next = new ArrayList<>(parentPath == null ? List.of() : parentPath);
                if (f.getKey() != null && !f.getKey().isBlank()) {
                    next.add(f.getKey());
                }
                out.addAll(flattenLeaves(f.getChildren(), next));
            } else {
                out.add(new LeafBinding(f, parentPath == null ? List.of() : List.copyOf(parentPath)));
            }
        }
        return out;
    }

    /**
     * 将扁平 slots（key 为各叶子 FieldSpec.key）按 pathPrefix 还原为嵌套参数 Map。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> nestArgs(Map<String, Object> flatSlots, List<LeafBinding> leaves) {
        Map<String, Object> root = new LinkedHashMap<>();
        if (flatSlots == null || leaves == null) {
            return root;
        }
        for (LeafBinding lb : leaves) {
            FieldSpec leaf = lb.field();
            if (leaf == null || leaf.getKey() == null || leaf.getKey().isBlank()) {
                continue;
            }
            String lk = leaf.getKey();
            if (!flatSlots.containsKey(lk)) {
                continue;
            }
            Object val = flatSlots.get(lk);
            Map<String, Object> cursor = root;
            List<String> prefix = lb.pathPrefix() == null ? List.of() : lb.pathPrefix();
            for (String segment : prefix) {
                if (segment == null || segment.isBlank()) {
                    continue;
                }
                Object next = cursor.get(segment);
                if (!(next instanceof Map<?, ?>)) {
                    Map<String, Object> nested = new LinkedHashMap<>();
                    cursor.put(segment, nested);
                    cursor = nested;
                } else {
                    cursor = (Map<String, Object>) next;
                }
            }
            cursor.put(lk, val);
        }
        return root;
    }

    public static FieldSpec findLeafByKey(List<FieldSpec> roots, String key) {
        if (key == null || roots == null) {
            return null;
        }
        for (LeafBinding lb : flattenLeaves(roots, List.of())) {
            if (key.equals(lb.field().getKey())) {
                return lb.field();
            }
        }
        return null;
    }
}
