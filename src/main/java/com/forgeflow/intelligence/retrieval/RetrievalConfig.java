package com.forgeflow.intelligence.retrieval;

import com.forgeflow.shared.llm.Embedder;
import com.forgeflow.shared.llm.GeminiEmbedder;
import com.forgeflow.shared.llm.HashingEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetrievalConfig {

    private static final Logger log = LoggerFactory.getLogger(RetrievalConfig.class);

    /** gemini (default) needs GOOGLE_API_KEY; without one, the offline hashing embedder is used and the log says so. */
    @Bean
    Embedder embedder(@Value("${forgeflow.retrieval.embedder:gemini}") String kind,
                      @Value("${forgeflow.llm.api-key:}") String apiKey,
                      @Value("${forgeflow.retrieval.embedding-model:gemini-embedding-001}") String model) {
        if ("gemini".equals(kind) && !apiKey.isBlank()) {
            return new GeminiEmbedder(apiKey, model);
        }
        if ("gemini".equals(kind)) {
            log.warn("No GOOGLE_API_KEY - code search uses the offline hashing embedder (keyword-level matching only)");
        }
        return new HashingEmbedder();
    }
}
