package com.enterprise.ai;

import com.enterprise.ai.pipeline.document.DoclingProperties;
import com.enterprise.ai.pipeline.document.DocumentParseProperties;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactProperties;
import com.enterprise.ai.pipeline.document.job.DocumentImportJobProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients
@EnableScheduling
@EnableConfigurationProperties({DocumentParseProperties.class, DoclingProperties.class,
        DocumentArtifactProperties.class, DocumentImportJobProperties.class})
public class ReachAiKnowledgeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReachAiKnowledgeServiceApplication.class, args);
    }
}
