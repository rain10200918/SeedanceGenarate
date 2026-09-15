package org.example.seedancegenarate.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.*;
import org.example.seedancegenarate.engine.comfyui.Impl.MiniMaxH3T2vHdWorkflowBuilder;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VideoResolutionControllerTest {
    final VideoSubmitService submit=mock(VideoSubmitService.class);
    final OssService oss=mock(OssService.class);
    MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        var user=new AppUser(); user.setId(7L); user.setRole("USER"); UserContext.setUser(user);
        var controller=new VideoController(null,null,oss,null,null,submit,null,null,null,null,null,null,null);
        mvc=MockMvcBuilders.standaloneSetup(controller).build();
        var spec=new MiniMaxH3T2vHdWorkflowBuilder(new ObjectMapper()).spec();
        when(submit.validateResolution(any(),any(),any(),any())).thenAnswer(i ->
                GenerationParameters.resolveMegapixels(spec,i.getArgument(2),i.getArgument(3)));
        var task=new VideoTask(); task.setBizTaskId("tier-task");task.setStatus("PROCESSING");
        when(submit.submit(any())).thenReturn(task);
    }
    @AfterEach void cleanup() { UserContext.clear(); }

    // 【测什么】UI estimate query和公开quote JSON都透传档位，并返回服务端解析值。
    // 【怎么算红】任一Controller仍只调用旧三/二参数方法或漏响应MP时请求/JSON断言失败。
    @Test void quoteEndpointsBindResolutionAndReturnResolvedMp() throws Exception {
        var estimate=new VideoSubmitService.PriceEstimate("comfyui","hd",8,"VIDEO",java.math.BigDecimal.ONE,
                java.math.BigDecimal.TEN,"CNY","2k",0.9);
        when(submit.estimate("comfyui","hd",8,"2k",null)).thenReturn(estimate);
        mvc.perform(get("/api/video/estimate").param("provider","comfyui").param("model","hd")
                .param("duration","8").param("resolution","2k")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution").value("2k")).andExpect(jsonPath("$.data.megapixels").value(0.9));
        verify(submit).estimate("comfyui","hd",8,"2k",null);
        var api=mock(ApiVideoService.class);when(api.quote("hd",8,"2k",null)).thenReturn(estimate);
        var apiMvc=MockMvcBuilders.standaloneSetup(new ApiGenerationController(api)).build();
        apiMvc.perform(post("/api/v1/generations/quote").contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"hd\",\"duration\":8,\"resolution\":\"2k\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolution").value("2k"))
                .andExpect(jsonPath("$.megapixels").value(0.9));
        verify(api).quote("hd",8,"2k",null);verifyNoInteractions(oss);
    }

    // 【测什么】真实JSON text2video不再把MP写null，resolution与旧MP都可完整传到服务。
    // 【怎么算红】DTO漏字段或Controller恢复null，捕获入参断言变红。
    @Test void textEndpointBindsBothNewAndLegacyFields() throws Exception {
        mvc.perform(post("/api/video/text2video").contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"p\",\"provider\":\"comfyui\",\"model\":\"minimax-h3-t2v-hd\",\"resolution\":\"2k\",\"megapixels\":0.9}"))
                .andExpect(status().isOk());
        var c=ArgumentCaptor.forClass(VideoSubmitService.SubmitRequest.class);verify(submit).submit(c.capture());
        assertEquals("2k",c.getValue().resolution());assertEquals(0.9,c.getValue().megapixels());
        verifyNoInteractions(oss);
    }

    // 【测什么】multipart清晰度在上传前校验，合法档位映射为执行MP。
    // 【怎么算红】先上传、漏新字段或沿用null MP会使异常/调用次数/参数断言失败。
    @Test void multipartRejectsInvalidTierBeforeUploadingAndPassesResolvedMp() throws Exception {
        var file=new MockMultipartFile("images","a.png","image/png",new byte[]{1});
        for (String tier:java.util.List.of("4k"," ","2K")) {
            var error=assertThrows(jakarta.servlet.ServletException.class,()->mvc.perform(multipart("/api/video/image2video")
                    .file(file).param("prompt","p").param("provider","comfyui").param("model","minimax-h3-t2v-hd")
                    .param("resolution",tier)));
            assertEquals(400,((BusinessException)error.getCause()).getCode());
        }
        verifyNoInteractions(oss);verify(submit,never()).submit(any());
        when(oss.upload(any())).thenReturn("https://media.test/a.png");
        mvc.perform(multipart("/api/video/image2video").file(file).param("prompt","p").param("provider","comfyui")
                .param("model","minimax-h3-t2v-hd").param("resolution","2k")).andExpect(status().isOk());
        var c=ArgumentCaptor.forClass(VideoSubmitService.SubmitRequest.class);verify(submit).submit(c.capture());
        assertEquals("2k",c.getValue().resolution());assertEquals(0.9,c.getValue().megapixels());
    }

    // 【测什么】已受理UI原键在能力变化后仍返回旧任务，不校验新档位也不再次上传/提交。
    // 【怎么算红】将新校验移到重放快路前，或删除快路，零调用断言变红。
    @Test void acceptedReplayBypassesNewResolutionSemantics() throws Exception {
        var old=new VideoTask();old.setBizTaskId("old-task");old.setMegapixels(0.3);
        when(submit.findAcceptedByRequestId(7L,"old")).thenReturn(old);
        mvc.perform(multipart("/api/video/image2video").param("prompt","p").param("requestId","old")
                .param("resolution","4k")).andExpect(status().isOk());
        mvc.perform(post("/api/video/text2video").contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"p\",\"requestId\":\"old\",\"resolution\":\"4k\"}")).andExpect(status().isOk());
        verify(submit,never()).validateResolution(any(),any(),any(),any());verify(submit,never()).submit(any());
        verifyNoInteractions(oss);assertEquals(0.3,old.getMegapixels());
    }
}
