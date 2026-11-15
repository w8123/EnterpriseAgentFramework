package com.enterprise.ai.control.activity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic first pass of 主动感知: turns raw events into candidate 常用流程 (frequent operation sequences)
 * and 易错步骤 (operations that often fail). The model only names and explains these; it never invents them.
 */
public final class ActivityInsightMiner {

    static final Duration SESSION_GAP = Duration.ofMinutes(30);
    static final int MAX_FLOWS = 8;
    static final int MAX_PITFALLS = 8;

    private ActivityInsightMiner() {
    }

    public static Result mine(List<ActivityEventEntity> events, int minSupport) {
        Map<String, String> names = new HashMap<>();
        List<List<ActivityEventEntity>> sessions = sessions(events, names);
        return new Result(flows(sessions, names, Math.max(2, minSupport)), pitfalls(sessions, names), sessions.size());
    }

    /** User ids may be tenant-local, so one employee is the tenant plus the user id. */
    static String who(ActivityEventEntity event) {
        return (event.getTenantId() == null ? "" : event.getTenantId()) + "\u0000"
                + (event.getUserId() == null ? "" : event.getUserId());
    }

    static List<List<ActivityEventEntity>> sessions(List<ActivityEventEntity> events, Map<String, String> names) {
        List<ActivityEventEntity> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(ActivityInsightMiner::who)
                .thenComparing(ActivityEventEntity::getOccurredAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        List<List<ActivityEventEntity>> sessions = new ArrayList<>();
        List<ActivityEventEntity> current = null;
        String user = null;
        LocalDateTime last = null;
        for (ActivityEventEntity event : sorted) {
            if (event.getOperationKey() == null || event.getOccurredAt() == null) continue;
            if (event.getOperationName() != null) names.putIfAbsent(event.getOperationKey(), event.getOperationName());
            boolean split = current == null || !who(event).equals(user)
                    || Duration.between(last, event.getOccurredAt()).compareTo(SESSION_GAP) > 0;
            if (split) {
                current = new ArrayList<>();
                sessions.add(current);
            }
            current.add(event);
            user = who(event);
            last = event.getOccurredAt();
        }
        return sessions;
    }

    static List<Candidate> flows(List<List<ActivityEventEntity>> sessions, Map<String, String> names, int minSupport) {
        Map<List<String>, Set<Integer>> supportSessions = new HashMap<>();
        Map<List<String>, Set<String>> supportUsers = new HashMap<>();
        Map<List<String>, Map<String, Integer>> pages = new HashMap<>();
        for (int s = 0; s < sessions.size(); s++) {
            List<ActivityEventEntity> steps = collapse(sessions.get(s));
            Set<List<String>> seen = new HashSet<>();
            for (int n = 2; n <= 4; n++) {
                for (int i = 0; i + n <= steps.size(); i++) {
                    List<String> gram = new ArrayList<>();
                    for (int k = i; k < i + n; k++) gram.add(steps.get(k).getOperationKey());
                    if (new HashSet<>(gram).size() < 2 || !seen.add(gram)) continue;
                    supportSessions.computeIfAbsent(gram, key -> new HashSet<>()).add(s);
                    supportUsers.computeIfAbsent(gram, key -> new HashSet<>()).add(who(steps.get(i)));
                    String page = steps.get(i).getPagePath();
                    if (page != null) pages.computeIfAbsent(gram, key -> new HashMap<>()).merge(page, 1, Integer::sum);
                }
            }
        }
        List<List<String>> frequent = supportSessions.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= minSupport)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        // Drop a sequence when a longer one containing it happens almost as often: the longer one says more.
        List<List<String>> kept = new ArrayList<>();
        for (List<String> gram : frequent) {
            int support = supportSessions.get(gram).size();
            boolean subsumed = frequent.stream().anyMatch(other -> other.size() > gram.size()
                    && Collections2.containsRun(other, gram)
                    && supportSessions.get(other).size() * 10 >= support * 8);
            if (!subsumed) kept.add(gram);
        }
        kept.sort(Comparator.comparingInt((List<String> gram) -> supportSessions.get(gram).size() * gram.size())
                .reversed().thenComparing(Object::toString));
        List<Candidate> result = new ArrayList<>();
        for (List<String> gram : kept) {
            if (result.size() >= MAX_FLOWS) break;
            List<Step> steps = gram.stream().map(key -> new Step(key, names.get(key))).collect(Collectors.toList());
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("sessions", supportSessions.get(gram).size());
            evidence.put("people", supportUsers.get(gram).size());
            result.add(new Candidate(ActivityInsightEntity.FLOW, String.join("\n", gram), steps,
                    topPages(pages.get(gram)), evidence, List.of()));
        }
        return result;
    }

    static List<Candidate> pitfalls(List<List<ActivityEventEntity>> sessions, Map<String, String> names) {
        Map<String, int[]> counts = new HashMap<>(); // total, failed, retriedThenSucceeded, gaveUp
        Map<String, Set<String>> failedUsers = new HashMap<>();
        Map<String, Map<String, Integer>> errors = new HashMap<>();
        Map<String, Map<String, Integer>> pages = new HashMap<>();
        for (List<ActivityEventEntity> session : sessions) {
            for (int i = 0; i < session.size(); i++) {
                ActivityEventEntity event = session.get(i);
                int[] c = counts.computeIfAbsent(event.getOperationKey(), key -> new int[4]);
                c[0]++;
                if (!"FAILED".equals(event.getOutcome())) continue;
                c[1]++;
                failedUsers.computeIfAbsent(event.getOperationKey(), key -> new HashSet<>()).add(who(event));
                if (event.getErrorMessage() != null) {
                    errors.computeIfAbsent(event.getOperationKey(), key -> new HashMap<>())
                            .merge(event.getErrorMessage(), 1, Integer::sum);
                }
                if (event.getPagePath() != null) {
                    pages.computeIfAbsent(event.getOperationKey(), key -> new HashMap<>())
                            .merge(event.getPagePath(), 1, Integer::sum);
                }
                boolean recovered = false;
                for (int j = i + 1; j < session.size(); j++) {
                    ActivityEventEntity later = session.get(j);
                    if (later.getOperationKey().equals(event.getOperationKey())) {
                        if (!"FAILED".equals(later.getOutcome())) recovered = true;
                        break;
                    }
                }
                if (recovered) c[2]++;
                else c[3]++;
            }
        }
        List<Candidate> result = new ArrayList<>();
        counts.entrySet().stream()
                .filter(entry -> entry.getValue()[1] >= 2
                        && (entry.getValue()[1] >= 5 || entry.getValue()[1] * 10 >= entry.getValue()[0]))
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> entry) -> entry.getValue()[1]).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(MAX_PITFALLS)
                .forEach(entry -> {
                    int[] c = entry.getValue();
                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("total", c[0]);
                    evidence.put("failed", c[1]);
                    evidence.put("failRate", Math.round(c[1] * 1000.0 / c[0]) / 10.0);
                    evidence.put("people", failedUsers.getOrDefault(entry.getKey(), Set.of()).size());
                    evidence.put("retriedThenSucceeded", c[2]);
                    evidence.put("gaveUp", c[3]);
                    List<String> topErrors = errors.getOrDefault(entry.getKey(), Map.of()).entrySet().stream()
                            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                            .limit(3).map(Map.Entry::getKey).collect(Collectors.toList());
                    result.add(new Candidate(ActivityInsightEntity.PITFALL, entry.getKey(),
                            List.of(new Step(entry.getKey(), names.get(entry.getKey()))),
                            topPages(pages.get(entry.getKey())), evidence, topErrors));
                });
        return result;
    }

    /** Repeated clicks on the same operation are one step of a flow. */
    private static List<ActivityEventEntity> collapse(List<ActivityEventEntity> session) {
        List<ActivityEventEntity> steps = new ArrayList<>();
        for (ActivityEventEntity event : session) {
            if (!steps.isEmpty() && steps.get(steps.size() - 1).getOperationKey().equals(event.getOperationKey())) continue;
            steps.add(event);
        }
        return steps;
    }

    private static List<String> topPages(Map<String, Integer> pages) {
        if (pages == null) return List.of();
        return pages.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(3).map(Map.Entry::getKey).collect(Collectors.toCollection(ArrayList::new));
    }

    static final class Collections2 {
        private Collections2() {
        }

        static boolean containsRun(List<String> longer, List<String> shorter) {
            for (int i = 0; i + shorter.size() <= longer.size(); i++) {
                if (longer.subList(i, i + shorter.size()).equals(shorter)) return true;
            }
            return false;
        }
    }

    public static String label(Step step) {
        return step.name() != null ? step.name() : step.operationKey();
    }

    public record Step(String operationKey, String name) {
    }

    public record Candidate(String kind, String signature, List<Step> steps, List<String> pagePaths,
                            Map<String, Object> evidence, List<String> topErrors) {
        public Set<String> operationKeys() {
            return steps.stream().map(Step::operationKey).collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    public record Result(List<Candidate> flows, List<Candidate> pitfalls, int sessions) {
    }
}
