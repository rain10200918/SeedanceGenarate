package org.example.seedancegenarate.agent.skill;

import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SkillRegistry {
    private final Map<String, CreativeSkill> skills;

    public SkillRegistry(List<CreativeSkill> implementations) {
        Map<String, CreativeSkill> found = new LinkedHashMap<>();
        for (var skill : implementations) {
            if (skill instanceof WebSearchSkill search && !search.available()) continue;
            if (found.putIfAbsent(skill.descriptor().id(), skill) != null) {
                throw new IllegalStateException("Duplicate creative skill: " + skill.descriptor().id());
            }
        }
        this.skills = Map.copyOf(found);
    }

    public List<SkillDescriptor> descriptors() {
        return skills.values().stream().map(CreativeSkill::descriptor)
                .sorted(java.util.Comparator.comparing(SkillDescriptor::id)).toList();
    }

    public CreativeSkill get(String id) {
        CreativeSkill skill = id == null ? null : skills.get(id);
        if (skill == null) throw BusinessException.badRequest("请求的创作技能不可用");
        return skill;
    }
}
