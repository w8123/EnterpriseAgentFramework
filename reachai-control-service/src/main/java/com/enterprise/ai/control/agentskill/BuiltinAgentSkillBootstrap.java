package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportCommand;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "reachai.skill", name = "builtin-bootstrap-enabled",
        havingValue = "true", matchIfMissing = true)
public class BuiltinAgentSkillBootstrap implements ApplicationRunner {

    private final BuiltinAgentSkillSource source;
    private final AgentSkillCatalogService catalogService;

    public BuiltinAgentSkillBootstrap(BuiltinAgentSkillSource source, AgentSkillCatalogService catalogService) {
        this.source = source;
        this.catalogService = catalogService;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (BuiltinAgentSkillSource.Descriptor descriptor : source.descriptors()) {
            catalogService.importTrustedBuiltin(
                    source.packageBytes(descriptor.name()),
                    new ImportCommand(
                            "reachai",
                            descriptor.version(),
                            descriptor.name(),
                            "PUBLIC",
                            "BUILTIN",
                            "classpath:ai-assist/skills/" + descriptor.name(),
                            "SYSTEM"));
        }
    }
}
