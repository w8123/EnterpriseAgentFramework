package com.enterprise.ai.control.a2a.domain.remoteagent;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

import java.util.List;

public record A2aRemoteProtocolSkill(
        String id,
        String name,
        String description,
        List<String> tags,
        List<String> examples,
        List<String> inputModes,
        List<String> outputModes) {

    public A2aRemoteProtocolSkill {
        id = A2aDomainText.requireText(id, "skill.id");
        name = A2aDomainText.requireText(name, "skill.name");
        description = A2aDomainText.requireText(description, "skill.description");
        tags = tags == null ? List.of() : List.copyOf(tags);
        examples = examples == null ? List.of() : List.copyOf(examples);
        inputModes = inputModes == null ? List.of() : List.copyOf(inputModes);
        outputModes = outputModes == null ? List.of() : List.copyOf(outputModes);
    }
}
