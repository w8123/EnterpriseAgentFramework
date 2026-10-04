package com.enterprise.ai.runtime.workflow.bmapi;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** An ordinary annotated business bean: each accepted write appends, not an idempotent fake return. */
final class StatefulWriteBusinessFixture {
    static final String NAME = "appendOrderNote";
    static final String STORAGE_NAME = "bmapi2d_" + NAME;
    static final String QUALIFIED_NAME = "bmapi2d:" + NAME;
    final AtomicInteger methodHits = new AtomicInteger();
    final AtomicInteger identityMethodHits = new AtomicInteger();
    private final List<String> notes = new ArrayList<>();

    @ReachCapability(name = NAME, title = "追加隔离订单备注",
            description = "向隔离订单追加一条备注，备注数量增加 1；重复调用会再次追加。不会修改真实订单。",
            sideEffect = ReachSideEffectLevel.WRITE, retryLimit = 0)
    public synchronized Map<String, Object> appendOrderNote(
            @ReachParam(name = "orderNo", description = "隔离订单编号", required = true, example = "ORD-2E") String orderNo,
            @ReachParam(name = "note", description = "要追加的备注", required = true, example = "浏览器写入验收") String note,
            @ReachParam(name = "apiKey", description = "隔离敏感输入，不作为调用身份", sensitive = true) String apiKey) {
        methodHits.incrementAndGet();
        notes.add(note);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", orderNo);
        result.put("noteCount", notes.size());
        result.put("lastNote", note);
        result.put("apiKey", apiKey);
        result.put("echo", apiKey);
        return result;
    }

    @ReachCapability(name = "identityOnly", title = "业务用户身份校验样例",
            sideEffect = ReachSideEffectLevel.READ, requiredRoles = {"ORDER_OPERATOR"})
    public String identityOnly() {
        identityMethodHits.incrementAndGet();
        return "must-not-be-called-by-console";
    }

    synchronized int noteCount() { return notes.size(); }

    /** A genuine scanner source update, not an injected tool/catalog row. */
    static final class Drift {
        @ReachCapability(name = NAME, title = "追加隔离订单备注",
                description = "来源已变化：每次追加备注并触发额外归档，必须重新评审。",
                sideEffect = ReachSideEffectLevel.WRITE, retryLimit = 0)
        public Map<String, Object> appendOrderNote(
                @ReachParam(name = "orderNo", required = true) String orderNo,
                @ReachParam(name = "note", required = true) String note,
                @ReachParam(name = "apiKey", sensitive = true) String apiKey) {
            throw new AssertionError("source declaration is not an execution endpoint");
        }
    }
}
