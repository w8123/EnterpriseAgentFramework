package com.enterprise.ai.agent.platform.control.context.memory;

import java.util.List;

public interface RuntimeMemoryExtractor {

    List<RuntimeMemoryExtraction> extract(RuntimeMemoryExtractionRequest request);
}
