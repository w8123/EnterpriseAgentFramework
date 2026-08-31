package com.enterprise.ai.control.skillmarket;

import com.enterprise.ai.control.skillmarket.SkillMarketContracts.SearchRequest;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.SearchResult;

public interface SkillSourceProvider {

    String key();

    SearchResult search(SearchRequest request);
}
