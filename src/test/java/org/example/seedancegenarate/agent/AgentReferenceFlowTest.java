package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.runtime.AgentBatchRuntime;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentReferenceFlowTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode node(String s)throws Exception{return json.readTree(s);}
    AgentContext context(JsonNode plan,List<AgentContext.ArtifactContext> artifacts){return new AgentContext(1L,"s","t","model","cat",null,List.of(),artifacts,0,List.of(),plan,null);}
    TaskQuote quote(JsonNode input){return new TaskQuote("engine","model","Reference video","VIDEO",input,BigDecimal.ONE,"CNY");}
    // Shared image identity and style are execution facts, not optional Planner recollection.
    @Test void videoInheritsConfirmedPlanAndRejectsSubstitution()throws Exception{
        var gateway=mock(AgentGenerationGateway.class);var skill=new VideoGenerationSkill(gateway);
        var plan=node("{\"confirmed\":true,\"data\":{\"referenceImage\":{\"artifactId\":\"cat\",\"version\":1},\"visualStyle\":\"warm animation\"}}");
        skill.quote(context(plan,List.of()),node("{\"model\":\"model\",\"prompt\":\"wake up\"}"));
        var cap=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(gateway).quoteVideo(any(),cap.capture());
        assertEquals(plan.path("data").path("referenceImage"),cap.getValue().path("referenceImage"));
        assertEquals("REFERENCE_IMAGE",cap.getValue().path("referenceMode").asText());
        assertEquals("warm animation",cap.getValue().path("visualStyle").asText());
        assertThrows(BusinessException.class,()->skill.quote(context(plan,List.of()),node("{\"model\":\"model\",\"prompt\":\"x\",\"referenceImage\":{\"artifactId\":\"other\",\"version\":1}}")));
        verifyNoMoreInteractions(gateway);
    }
    @Test void planSavesTrustedImageAndRejectsWrongType()throws Exception{
        var model=mock(AgentModelGateway.class);when(model.complete(any(),anyString(),anyString(),anyString())).thenReturn("{\"title\":\"Animation\",\"goal\":\"20 seconds\",\"constraints\":[],\"steps\":[{\"id\":\"v\",\"kind\":\"VIDEO\",\"title\":\"Video\"}]}");
        var skill=new PlanGenerationSkill(model,json);var input=node("{\"instruction\":\"animate cat\",\"referenceImage\":{\"artifactId\":\"cat\",\"version\":1},\"visualStyle\":\"warm\"}");
        var cat=new AgentContext.ArtifactContext("cat",1,"IMAGE","Cat","",json.createObjectNode());
        var result=skill.execute(context(null,List.of(cat)),input);
        assertEquals(input.path("referenceImage"),result.data().path("referenceImage"));assertEquals("warm",result.data().path("visualStyle").asText());
        var wrong=new AgentContext.ArtifactContext("cat",1,"SCRIPT","Cat","",json.createObjectNode());
        assertThrows(BusinessException.class,()->skill.execute(context(null,List.of(wrong)),input));
        verify(model,times(1)).complete(any(),anyString(),anyString(),anyString());
    }
    AgentContext batchContext() {
        var plan=json.createObjectNode().put("currentStepId","v");var step=plan.putArray("steps").addObject().put("id","v").put("executionStepId","step").put("kind","VIDEO").put("scope","STORYBOARD_SCENES");
        var scenes=step.putArray("scenes");var board=json.createObjectNode();var entries=board.putArray("scenes");int[] durations={5,7,5,3};
        for(int i=0;i<4;i++) {var s=scenes.addObject().put("id","scene"+i).put("ordinal",i+1).put("status","READY");s.putObject("sourceRef").put("artifactId","board").put("version",1).put("sceneId","s"+i);entries.addObject().put("sceneId","s"+i).put("visual","visual"+i).put("duration",durations[i]);}
        return context(plan,List.of(new AgentContext.ArtifactContext("board",1,"STORYBOARD","Board","",board)));
    }
    // 【测什么】所有幕共用参考和画风，但使用各自分镜时长与画面，不复制第一幕。
    // 【怎么算红】删除BatchRuntime的duration赋值，5/7/5/3断言失败。
    @Test void batchQuotesEveryScenesActualDurationAndVisual()throws Exception{
        var gateway=mock(AgentGenerationGateway.class);var input=(ObjectNode)node("{\"model\":\"model\",\"prompt\":\"first only\",\"duration\":5,\"referenceMode\":\"REFERENCE_IMAGE\",\"referenceImage\":{\"artifactId\":\"cat\",\"version\":1},\"visualStyle\":\"warm\"}");
        when(gateway.videoParameters(any())).thenAnswer(a->((ObjectNode)a.getArgument(0)).deepCopy());
        when(gateway.quoteVideo(any(),any())).thenAnswer(a->quote(a.getArgument(1)));
        var batch=AgentBatchRuntime.prepare(gateway,batchContext(),quote(input));
        assertEquals(List.of(5,7,5,3),batch.items().stream().map(i->i.quote().inputSnapshot().path("duration").asInt()).toList());
        for(int i=0;i<4;i++){var spec=batch.items().get(i).quote().inputSnapshot();assertEquals("visual"+i,spec.path("prompt").asText());assertEquals(input.path("referenceImage"),spec.path("referenceImage"));assertEquals("warm",spec.path("visualStyle").asText());}
        verify(gateway,times(4)).quoteVideo(any(),any());verify(gateway,never()).submit(anyLong(),any(),anyString());
    }
    @Test void unsupportedSceneStopsBeforeAnySubmission()throws Exception{
        var gateway=mock(AgentGenerationGateway.class);when(gateway.videoParameters(any())).thenAnswer(a->((ObjectNode)a.getArgument(0)).deepCopy());
        when(gateway.quoteVideo(any(),any())).thenAnswer(a->{JsonNode spec=a.getArgument(1);if(spec.path("duration").asInt()!=5)throw BusinessException.badRequest("unsupported duration");return quote(spec);});
        assertThrows(BusinessException.class,()->AgentBatchRuntime.prepare(gateway,batchContext(),quote(node("{\"model\":\"model\",\"prompt\":\"x\"}"))));
        verify(gateway,never()).submit(anyLong(),any(),anyString());
    }
    @Test void firstFrameIsNotAMultiSceneCharacterReference()throws Exception{
        var gateway=mock(AgentGenerationGateway.class);
        assertThrows(BusinessException.class,()->AgentBatchRuntime.prepare(gateway,batchContext(),quote(node("{\"referenceMode\":\"FIRST_FRAME\"}"))));verifyNoInteractions(gateway);
    }
    @Test void approvalProjectionDoesNotLeakStorageOrIdentity()throws Exception{
        var target=json.createObjectNode();AgentReferenceProjection.apply(target,node("{\"referenceImage\":{\"artifactId\":\"cat\",\"version\":2},\"referenceMode\":\"REFERENCE_IMAGE\",\"_reference\":{\"userId\":1,\"sessionId\":\"secret\",\"objectKey\":\"private\",\"title\":\"Cat\",\"mediaPath\":\"/api/agent/media/task\"}}"));
        assertEquals(5,target.path("referenceImage").size());assertFalse(target.toString().contains("private"));assertFalse(target.toString().contains("secret"));
        AgentReferenceProjection.apply(target,json.createObjectNode());assertFalse(target.has("referenceImage"));
    }
}
