package org.example.seedancegenarate.agent;

import org.junit.jupiter.api.Test;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.model.AgentDecision;
import org.example.seedancegenarate.agent.recipe.RecipeDefinition;
import org.example.seedancegenarate.agent.skill.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentDirectorTemplateRunTest extends AgentRecipeRunTest {
    // 【测什么】真实打包的校园导演模板沿原Runtime产出脚本、自动推进分镜并完成，无人工阶段审批。
    // 【怎么算红】删除模板SCRIPT依赖或Runtime结果推进，source/stage/status断言失败或卡在脚本。
    @Test void bundledTemplateRunsThroughExistingTextRuntime() throws Exception {
        var accepted=app.send(1,id,send("director-start","根据已提供学校资料制作校园宣传片"));
        seedRun(accepted.turn().id());
        try(var input=getClass().getResourceAsStream("/agent/recipe-templates.json")) {
            assertNotNull(input);
            var templates=json.readTree(input);com.fasterxml.jackson.databind.JsonNode template=null;
            for(var item:templates) if("campus-promo".equals(item.path("id").asText())) template=item;
            assertNotNull(template);
            var definition=json.treeToValue(template.path("definition"),RecipeDefinition.class);definition.validate();
            db.update("UPDATE creative_recipe_version SET definition_json=? WHERE id='version'",json.writeValueAsString(definition));
        }
        adoptPlan(true);
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","脚本",json.createObjectNode(),"SCRIPT"));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode()));
        when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","校园脚本","真实资料形成的完整剧本",null));
        run();run();
        var recipe=store.recipes().latest(store.owned(id,1,false));
        assertEquals(1,recipe.stage());
        var source=recipe.results().path("script");
        assertFalse(source.path("artifactId").asText().isBlank());
        var board=mock(CreativeSkill.class);
        when(board.descriptor()).thenReturn(new SkillDescriptor("storyboard-generation","1","分镜",json.createObjectNode(),"STORYBOARD"));
        skills=new SkillRegistry(List.of(skill,board));
        runtime=new org.example.seedancegenarate.agent.runtime.AgentRuntime(store,jobs,tx,planner,skills,json,
                mock(org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime.class),diagnostics,models);
        var ref=new AgentContext.ArtifactRef(source.path("artifactId").asText(),source.path("version").asInt(),null);
        var args=json.createObjectNode();args.set("source",json.valueToTree(ref));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"storyboard-generation",args));
        when(board.execute(any(),any())).thenReturn(new SkillResult("STORYBOARD","校园分镜","分镜正文",null,
                json.readTree("{\"scenes\":[{\"sceneId\":\"s1\",\"title\":\"校门\",\"visual\":\"校门晨光\",\"narration\":\"欢迎\"}]}"),ref));
        run();run();
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
        verify(planner,times(2)).decide(any()); // No closing model invocation after real results are ready.
        assertEquals("COMPLETED",store.recipes().latest(store.owned(id,1,false)).status());
        assertEquals(2,store.recipes().latest(store.owned(id,1,false)).results().size());
        verify(skill,times(1)).execute(any(),any());verify(board,times(1)).execute(any(),any());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_approval",Integer.class));
    }
}
