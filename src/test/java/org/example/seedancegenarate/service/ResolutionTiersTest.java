package org.example.seedancegenarate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.comfyui.Impl.MiniMaxH3HdWorkflowBuilder;
import org.example.seedancegenarate.engine.comfyui.Impl.MiniMaxH3HdFastWorkflowBuilder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.comfyui.Impl.*;
import org.example.seedancegenarate.exception.BusinessException;
import java.util.List;

class ResolutionTiersTest {
    private final ObjectMapper json = new ObjectMapper();

    // 【测什么】所有已声明可调模型保留完整三档，Flux专属合法映射保留原1MP默认。
    // 【怎么算红】Flux照抄0.4/0.9被交集删档、或任一模型漏2k/1080p，此测试变红。
    @Test void declaredMappingsSurviveLegalMpIntersection() {
        var flux = new Flux2ImageEditWorkflowBuilder(json).spec();
        assertEquals(List.of(0.5,1.0,2.0),flux.resolutions().stream().map(ModelSpec.ResolutionOption::megapixels).toList());
        assertEquals("720p",flux.defaultResolution());
        for (var spec : List.of(new MiniMaxH3HdWorkflowBuilder(json).spec(),new MiniMaxH3T2vHdWorkflowBuilder(json).spec(),
                new MiniMaxH3Fl2vaHdWorkflowBuilder(json).spec())) {
            assertEquals(List.of("720p","1080p","2k"),spec.resolutions().stream().map(ModelSpec.ResolutionOption::id).toList());
            assertTrue(spec.resolutions().stream().allMatch(ModelSpec.ResolutionOption::upscaled));
            assertTrue(spec.resolutions().stream().allMatch(o -> spec.megapixels().contains(o.megapixels())));
        }
        for (var spec : List.of(new MiniMaxH3OptWorkflowBuilder(json).spec(),new MiniMaxH3AccelWorkflowBuilder(json).spec(),
                new MiniMaxH34StepWorkflowBuilder(json).spec())) {
            assertEquals(List.of("480p","720p","1080p"),spec.resolutions().stream().map(ModelSpec.ResolutionOption::id).toList());
            assertEquals(List.of(0.4,0.9,2.0),spec.resolutions().stream().map(ModelSpec.ResolutionOption::megapixels).toList());
            assertEquals("720p",spec.defaultResolution());
        }
        assertTrue(new MiniMaxMusic3WorkflowBuilder(json).spec().resolutions().isEmpty());
        assertNull(new MiniMaxMusic3WorkflowBuilder(json).spec().defaultResolution());
    }

    // 【测什么】新档位解析、固定null、旧空值与合法MP不变，非法/冲突严格400。
    // 【怎么算红】静默选默认、允许4k/空白/冲突，或固定档位变0.5MP时断言变红。
    @Test void resolvesNewTiersWithoutReinterpretingLegacyDefaults() {
        var hd = new MiniMaxH3HdWorkflowBuilder(json).spec();
        assertEquals(0.9,GenerationParameters.resolveMegapixels(hd,"2k",null));
        assertNull(GenerationParameters.resolveMegapixels(hd,null,null));
        assertEquals(0.3,GenerationParameters.resolveMegapixels(hd,null,0.3));
        assertEquals(0.9,GenerationParameters.resolveMegapixels(hd,"2k",0.9));
        for (String bad : List.of("", " ", "2K", "4k", "480p", "garbage"))
            assertEquals(400,assertThrows(BusinessException.class,()->GenerationParameters.resolveMegapixels(hd,bad,null)).getCode());
        for (Double bad : List.of(0.5,Double.NaN,Double.POSITIVE_INFINITY,-1.0))
            assertEquals(400,assertThrows(BusinessException.class,()->GenerationParameters.resolveMegapixels(hd,"2k",bad)).getCode());
        var fixed = new MiniMaxH3HdFastWorkflowBuilder(json).spec();
        assertNull(GenerationParameters.resolveMegapixels(fixed,"480p",null));
        assertThrows(BusinessException.class,()->GenerationParameters.resolveMegapixels(fixed,"480p",0.5));
        assertThrows(BusinessException.class,()->GenerationParameters.resolveMegapixels(fixed,null,0.5));
    }

    // 【测什么】高清2K能力使用0.9MP且标明超分，固定快速版不伪造可调MP。
    // 【怎么算红】移除resolutions能力，或把HD的2k映射改为0.5，本测试变红。
    @Test void declaresHdAndFixedCapabilities() {
        var hd = json.valueToTree(new MiniMaxH3HdWorkflowBuilder(json).spec());
        assertEquals(3, hd.path("resolutions").size());
        assertEquals("2k", hd.path("resolutions").get(2).path("id").asText());
        assertEquals(0.9, hd.path("resolutions").get(2).path("megapixels").doubleValue());
        assertTrue(hd.path("resolutions").get(2).path("upscaled").asBoolean());
        var fixed = json.valueToTree(new MiniMaxH3HdFastWorkflowBuilder(json).spec());
        assertEquals("480p", fixed.path("defaultResolution").asText());
        assertEquals(1, fixed.path("resolutions").size());
        assertTrue(fixed.path("resolutions").get(0).path("megapixels").isNull());
        assertFalse(fixed.path("resolutions").get(0).path("upscaled").asBoolean());
    }
}
