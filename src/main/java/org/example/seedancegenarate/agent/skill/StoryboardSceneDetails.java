package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.seedancegenarate.agent.model.AgentContext;
import java.util.*;
import static org.example.seedancegenarate.agent.skill.StructuredSkillSupport.*;

/** Optional production notes, not new media inputs or execution authority. */
public final class StoryboardSceneDetails {
    private StoryboardSceneDetails() {}
    public static void validate(JsonNode scene) {
        if(scene.has("characters")) {
            var characters=scene.get("characters");
            if(!characters.isArray()||characters.size()>8)throw field("characters 必须为最多8项数组");
            var names=new HashSet<String>();
            for(var character:characters) {
                object(character,Set.of("name","appearance","wardrobe","referenceImage"));
                if(!names.add(text(character,"name",80)))throw field("characters.name 不能重复");
                optionalText(character,"appearance",400);optionalText(character,"wardrobe",300);
                if(character.has("referenceImage")) {
                    object(character.get("referenceImage"),Set.of("artifactId","version"));
                    reference(character.get("referenceImage"));
                }
            }
        }
        if(scene.has("shot")) {
            var shot=scene.get("shot");object(shot,Set.of("action","framing","cameraMovement","startState","endState"));
            for(String field:List.of("action","framing","cameraMovement","startState","endState"))optionalText(shot,field,400);
        }
        if(scene.has("sound")) {
            var sound=scene.get("sound");object(sound,Set.of("dialogue","narration","ambience"));
            optionalText(sound,"ambience",400);
            if(sound.has("narration")) {
                if(!sound.get("narration").isTextual()||sound.get("narration").asText().length()>400
                        ||!sound.get("narration").asText().equals(scene.path("narration").asText()))throw field("sound.narration 必须与narration一致且不超过400字符");
            }
            if(sound.has("dialogue")) {
                var lines=sound.get("dialogue");if(!lines.isArray()||lines.size()>12)throw field("sound.dialogue 必须为最多12项数组");
                for(var line:lines) {object(line,Set.of("speaker","text"));text(line,"speaker",80);text(line,"text",400);}
            }
        }
    }
    public static void validateReferences(JsonNode scene,AgentContext context) {
        for(var character:scene.path("characters"))if(character.has("referenceImage")) {
            var ref=reference(character.get("referenceImage"));
            boolean known=context.artifacts().stream().anyMatch(a->"IMAGE".equals(a.type())&&a.id().equals(ref.artifactId())&&a.version()==ref.version());
            JsonNode planRef=context.plan()==null?null:context.plan().path("data").path("referenceImage");
            if(!known&&(planRef==null||!ref.artifactId().equals(planRef.path("artifactId").asText())||ref.version()!=planRef.path("version").asInt()))throw invalid();
        }
    }
    /** Speaker and text stay adjacent, so swapping dialogue ownership cannot pass. */
    public static List<String> spokenLines(JsonNode scene) {
        validate(scene);
        var result=new ArrayList<String>();
        for(var line:scene.path("sound").path("dialogue"))result.add(line.path("speaker").asText()+":"+line.path("text").asText());
        String narration=scene.path("sound").has("narration")?scene.path("sound").path("narration").asText():scene.path("narration").asText("");
        narration.lines().filter(line->!line.isBlank()).forEach(result::add);
        return List.copyOf(result);
    }
    private static void optionalText(JsonNode node,String field,int max) {if(node.has(field))text(node,field,max);}
    private static org.example.seedancegenarate.exception.BusinessException field(String detail) {
        return org.example.seedancegenarate.exception.BusinessException.badRequest("字段 " + detail);
    }
}
