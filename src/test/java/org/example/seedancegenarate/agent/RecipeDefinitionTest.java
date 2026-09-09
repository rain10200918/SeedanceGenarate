package org.example.seedancegenarate.agent;

import java.util.List;
import org.example.seedancegenarate.agent.recipe.RecipeDefinition;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeDefinitionTest {
    // 【测什么】阶段不能依赖尚未产生、也未声明输入的作品，避免发布永远卡死的流程。
    // 【怎么算红】删除阶段前置作品可达性检查，此处assertThrows失败。
    @Test void rejectsUnavailableEarlierArtifact() {
        var stage=new RecipeDefinition.Stage("board","分镜","分镜",List.of("storyboard-generation"),List.of(),"SCRIPT",true,"STORYBOARD");
        var definition=new RecipeDefinition("导演",List.of(),List.of(),List.of(stage),List.of("storyboard-generation"),List.of());
        assertEquals(400,assertThrows(BusinessException.class,definition::validate).getCode());
    }
}
