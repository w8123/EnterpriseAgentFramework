package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skills an employee may install into their own AI tool: PROJECT Skills of the business systems
 * the employee can connect, with a PUBLISHED default version. Nothing else is reachable here.
 */
@Service
public class AgentSkillEmployeeCatalog {

    private final AgentSkillCatalogService catalogService;

    public AgentSkillEmployeeCatalog(AgentSkillCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** @param systems project code → name the employee sees */
    public List<EmployeeSkill> list(Map<String, String> systems) {
        List<EmployeeSkill> skills = new ArrayList<>();
        for (SkillSummary skill : catalogService.list(null, AgentSkillCatalogService.STATUS_PUBLISHED)) {
            if (!installable(skill, systems)) continue;
            VersionView version = catalogService.version(skill.id(), installVersionId(skill));
            if (!AgentSkillCatalogService.STATUS_PUBLISHED.equals(version.status())) continue;
            skills.add(new EmployeeSkill(skill.id(), skill.name(), skill.displayName(), skill.description(),
                    skill.projectCode(), systems.get(skill.projectCode()), version.version(), skill.updatedAt()));
        }
        return skills;
    }

    public Package packageFor(Long skillId, Map<String, String> systems) {
        SkillSummary skill = catalogService.detail(skillId).skill();
        if (!installable(skill, systems)) {
            throw AgentSkillException.notFound("Skill not available to this employee");
        }
        VersionView version = catalogService.version(skillId, installVersionId(skill));
        if (!AgentSkillCatalogService.STATUS_PUBLISHED.equals(version.status())) {
            throw AgentSkillException.notFound("Skill not available to this employee");
        }
        return new Package(skill.name() + "-" + version.version() + ".zip", version.sourceSha256(),
                catalogService.packageBytes(skillId, version.id()));
    }

    /** The newest published version, so a re-captured Skill reaches employees without a default switch. */
    private static Long installVersionId(SkillSummary skill) {
        return skill.latestVersionId() != null ? skill.latestVersionId() : skill.defaultVersionId();
    }

    private static boolean installable(SkillSummary skill, Map<String, String> systems) {
        return skill != null
                && "ACTIVE".equalsIgnoreCase(skill.status())
                && "PROJECT".equalsIgnoreCase(skill.visibility())
                && skill.projectCode() != null
                && systems.containsKey(skill.projectCode())
                && installVersionId(skill) != null;
    }

    public record EmployeeSkill(Long skillId, String name, String displayName, String description,
                                String projectCode, String systemName, String version, LocalDateTime updatedAt) {
    }

    public record Package(String filename, String sha256, byte[] body) {
    }
}
