package com.enterprise.ai.control.a2a.domain.publication;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aDomainText;

import java.util.List;
import java.util.regex.Pattern;

/** A2A AgentSkill is a protocol description, not a ReachAI Skill asset. */
public record A2aProtocolSkill(
        String id,
        String name,
        String description,
        List<String> tags,
        List<String> examples,
        List<String> inputModes,
        List<String> outputModes) {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}");

    public A2aProtocolSkill {
        id = A2aDomainText.requireText(id, "skill.id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new A2aDomainException("A2A_SKILL_ID_INVALID", "skill.id is invalid");
        }
        name = A2aDomainText.requireText(name, "skill.name");
        description = A2aDomainText.requireText(description, "skill.description");
        tags = normalized(tags);
        examples = normalized(examples);
        inputModes = normalized(inputModes);
        outputModes = normalized(outputModes);
        if (tags.isEmpty()) {
            throw new A2aDomainException("A2A_SKILL_TAG_REQUIRED",
                    "each protocol skill requires at least one tag");
        }
        inputModes.forEach(mode -> requireMediaType(mode, "skill.inputModes"));
        outputModes.forEach(mode -> requireMediaType(mode, "skill.outputModes"));
    }

    private static List<String> normalized(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public static void requireMediaType(String value, String field) {
        if (value == null || value.isBlank() || !value.contains("/")
                || value.indexOf('/') == 0 || value.endsWith("/")) {
            throw new A2aDomainException("A2A_MEDIA_TYPE_INVALID", field + " contains an invalid media type");
        }
    }
}
