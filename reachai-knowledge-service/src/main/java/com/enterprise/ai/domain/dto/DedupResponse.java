package com.enterprise.ai.domain.dto;

import com.enterprise.ai.domain.vo.SimilarItem;
import lombok.Data;

import java.util.List;

@Data
public class DedupResponse {

    /** 是否存在重复 */
    private boolean duplicated;

    /** 相似项列表 */
    private List<SimilarItem> items;

    /** 最高相似度分数 */
    private float maxScore;

    public static DedupResponse of(List<SimilarItem> items) {
        DedupResponse resp = new DedupResponse();
        resp.setItems(items);
        resp.setDuplicated(items != null && !items.isEmpty());
        resp.setMaxScore(items != null && !items.isEmpty()
                ? items.stream().map(SimilarItem::getScore).max(Float::compareTo).orElse(0f)
                : 0f);
        return resp;
    }
}
