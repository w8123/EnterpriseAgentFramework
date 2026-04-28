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
            /* 显式 children=[]：嵌套对象无需表单项，提交时注入空 Map */
            if (f.getChildren() != null && f.getChildren().isEmpty()) {
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

    /**
     * 将树中 {@code children} 为非 null 且为空的节点在嵌套参数中写成空 Map，便于仅含 body_json 占位且 DTO 无字段的 HTTP 调用。
     */
    public static void mergeEmptyGroupDefaults(Map<String, Object> root, List<FieldSpec> fields, List<String> parentPath) {
        if (root == null || fields == null) {
            return;
        }
        List<String> base = parentPath == null ? List.of() : parentPath;
        for (FieldSpec f : fields) {
            if (f == null || f.getKey() == null || f.getKey().isBlank()) {
                continue;
            }
            if (f.getChildren() != null && f.getChildren().isEmpty()) {
                navigateToParentMap(root, base).putIfAbsent(f.getKey(), new LinkedHashMap<>());
            } else if (hasNonEmptyChildren(f)) {
                List<String> nextPath = new ArrayList<>(base);
                nextPath.add(f.getKey());
                getOrCreateNestedMap(root, base, f.getKey());
                mergeEmptyGroupDefaults(root, f.getChildren(), nextPath);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> navigateToParentMap(Map<String, Object> root, List<String> path) {
        Map<String, Object> cursor = root;
        if (path != null) {
            for (String segment : path) {
                if (segment == null || segment.isBlank()) {
                    continue;
                }
                Object next = cursor.get(segment);
                if (!(next instanceof Map<?, ?>)) {
                    Map<String, Object> nm = new LinkedHashMap<>();
                    cursor.put(segment, nm);
                    cursor = nm;
                } else {
                    cursor = (Map<String, Object>) next;
                }
            }
        }
        return cursor;
    }

    @SuppressWarnings("unchecked")
    private static void getOrCreateNestedMap(Map<String, Object> root, List<String> parentPath, String key) {
        Map<String, Object> parent = navigateToParentMap(root, parentPath);
        Object existing = parent.get(key);
        if (!(existing instanceof Map<?, ?>)) {
            parent.put(key, new LinkedHashMap<>());
        }
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
