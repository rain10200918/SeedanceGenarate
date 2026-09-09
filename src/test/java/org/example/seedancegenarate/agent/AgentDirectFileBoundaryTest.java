package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.generation.AgentDirectGenerationGateway;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.dto.SendMessageRequest.Attachment;
import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.UserAssetMapper;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentDirectFileBoundaryTest {
    // 【测什么】客户端伪造image/png不能把HTML正文或主动内容扩展名经DIRECT上传到可信OSS。
    // 【怎么算红】只检查声明MIME、漏真实文件头或漏扩展名时，至少一个伪造文件会上传并报价而不抛400。
    @Test void disguisedActiveContentIsRejectedBeforeUploadAndPricing() {
        var json=new ObjectMapper();var engine=mock(VideoEngine.class);
        when(engine.provider()).thenReturn("comfyui");
        when(engine.models()).thenReturn(List.of(new ModelSpec("comfyui","image","Image",false,0,4,
                List.of("1:1"),0,0,List.of(),OutputType.IMAGE)));
        var access=mock(ModelAccessService.class);when(access.isOpen("image")).thenReturn(true);
        var submit=mock(VideoSubmitService.class);
        when(submit.estimate(anyString(),anyString(),anyInt())).thenReturn(new VideoSubmitService.PriceEstimate(
                "comfyui","image",8,"IMAGE",BigDecimal.ONE,new BigDecimal("2"),"CNY"));
        var media=mock(ConversationMediaResolver.class);
        when(media.resolve(anyList(),any())).thenReturn(List.of(new Attachment("image","https://media.example.com/images/uploaded.png")));
        var oss=new OssConfig();oss.setDomain("https://media.example.com");
        var gateway=new AgentDirectGenerationGateway(new VideoEngineRegistry(List.of(engine)),access,submit,media,
                mock(UserAssetMapper.class),oss,json);
        var input=json.createObjectNode().put("provider","comfyui").put("model","image").put("prompt","参考图生成");
        byte[] html="<!doctype html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aR6sAAAAASUVORK5CYII=");
        for(var file:List.of(new MockMultipartFile("images","payload.html","image/png",html),
                new MockMultipartFile("images","payload.png","image/png",html),
                new MockMultipartFile("images","payload.html","image/png",png))) {
            var files=new ConversationMediaResolver.LocalFiles(new MockMultipartFile[]{file},List.of("file"),null,null);
            var error=assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"IMAGE",input,List.of(),files),file.getOriginalFilename());
            assertEquals(400,error.getCode());
        }
        verifyNoInteractions(media,submit);
    }
}
