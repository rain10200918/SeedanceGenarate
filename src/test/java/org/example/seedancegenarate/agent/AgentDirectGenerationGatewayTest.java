package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.generation.AgentDirectGenerationGateway;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.dto.SendMessageRequest.Attachment;
import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.entity.UserAsset;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.UserAssetMapper;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentDirectGenerationGatewayTest {
    static final byte[] PNG={(byte)137,80,78,71,13,10,26,10};
    final ObjectMapper json=new ObjectMapper();
    final VideoEngine engine=mock(VideoEngine.class);
    final ModelAccessService access=mock(ModelAccessService.class);
    final VideoSubmitService submit=mock(VideoSubmitService.class);
    final ConversationMediaResolver media=mock(ConversationMediaResolver.class);
    final UserAssetMapper assets=mock(UserAssetMapper.class);
    final OssConfig oss=new OssConfig();
    AgentDirectGenerationGateway gateway;
    @BeforeEach void setup() {
        when(engine.provider()).thenReturn("comfyui");
        when(engine.models()).thenReturn(List.of(
                new ModelSpec("comfyui","image","Image",false,0,4,List.of("1:1"),0,0,List.of(),OutputType.IMAGE),
                new ModelSpec("comfyui","video","Video",true,1,2,List.of("16:9"),5,10,List.of(5,10),OutputType.VIDEO,List.of(),1,1,false),
                new ModelSpec("comfyui","music","Music",false,0,0,List.of(),30,300,List.of(30,60,300),OutputType.AUDIO)));
        when(access.isOpen(anyString())).thenReturn(true); oss.setDomain("https://media.example.com");
        when(submit.estimate(anyString(),anyString(),anyInt())).thenAnswer(a->new VideoSubmitService.PriceEstimate(a.getArgument(0),a.getArgument(1),a.getArgument(2),
                "music".equals(a.getArgument(1))?"AUDIO":"video".equals(a.getArgument(1))?"VIDEO":"IMAGE",BigDecimal.ONE,new BigDecimal("2"),"CNY"));
        when(media.resolve(anyList(),any())).thenAnswer(a->a.getArgument(0));
        gateway=new AgentDirectGenerationGateway(new VideoEngineRegistry(List.of(engine)),access,submit,media,assets,oss,json);
    }
    com.fasterxml.jackson.databind.JsonNode input(String model,int duration) { return json.createObjectNode().put("provider","comfyui").put("model",model).put("prompt","创作内容").put("duration",duration); }
    UserAsset asset(long owner,String url,String type) { var a=new UserAsset();a.setId(8L);a.setUserId(owner);a.setUrl(url);a.setType(type);a.setStatus("ACTIVE");return a; }

    // 【测什么】DIRECT新档位在上传前校验，批准快照只含MP，重新报价不改变旧快照形状。
    // 【怎么算红】保留resolution进snapshot、丢MP或在上传后校验时字段/零调用断言失败。
    @Test void resolutionIsCleanedBeforeApprovalAndNeverLeaksIntoSnapshot() {
        var spec=new ModelSpec("comfyui","video","Video",false,0,2,List.of("16:9"),5,10,List.of(5,10),
                OutputType.VIDEO,List.of(0.2,0.5,0.9)).withResolutions(List.of(new ModelSpec.ResolutionOption("2k",0.9,true)),0.2);
        when(engine.models()).thenReturn(List.of(spec));
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)input("video",5);
        input.put("resolution","4k");
        assertEquals(400,assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"VIDEO",input,List.of(),null)).getCode());
        verifyNoInteractions(media);
        input.put("resolution","2k");
        var quote=gateway.prepareDirect(7,"VIDEO",input,List.of(),null);
        assertFalse(quote.inputSnapshot().has("resolution"));
        assertEquals(0.9,quote.inputSnapshot().path("megapixels").doubleValue());
        assertEquals(quote,gateway.requote(7,"VIDEO",quote.inputSnapshot()));
        assertEquals("2k",input.path("resolution").asText());
        input.put("megapixels",0.5);
        assertEquals(400,assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"VIDEO",input,List.of(),null)).getCode());
    }

    // 【测什么】DIRECT允许开放音乐模型300秒，不把Agent纯文本120秒上限或画幅强加到音频。
    // 【怎么算红】音乐被过滤或硬限120秒时quote抛错，origin/规范参数断言失败。
    @Test void musicUsesActualCapabilitiesAndSamePricing() throws Exception {
        assertEquals(3,gateway.models().size());
        var q=gateway.prepareDirect(7,"AUDIO",input("music",300),List.of(),null);
        assertEquals("DIRECT",q.origin());assertEquals("AUDIO",q.mediaType());assertEquals(300,q.inputSnapshot().path("duration").asInt());
        assertEquals(7,q.inputSnapshot().path("ownerId").asLong());assertFalse(q.inputSnapshot().has("ratio"));
        assertEquals(new BigDecimal("2"),q.amount());verify(submit).estimate("comfyui","music",300);
        verify(submit,never()).submitApproved(any(),any());
    }

    // 【测什么】同存储域名不是归属证明，跨用户和非白名单URL在上传前拒绝。
    // 【怎么算红】只查host不核owner，跨用户asset将通过prepare。
    @Test void libraryReferencesRequireOwnerTypeAndTrustedHost() {
        String url="https://media.example.com/images/own.png";
        when(assets.selectOne(any())).thenReturn(asset(8,url,"IMAGE"));
        assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"IMAGE",input("image",8),List.of(new Attachment("image",url)),null));
        when(assets.selectOne(any())).thenReturn(asset(7,url,"VIDEO"));
        assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"IMAGE",input("image",8),List.of(new Attachment("image",url)),null));
        for(String bad:List.of("http://127.0.0.1/x.png","https://media.example.com.evil.test/x","https://user@media.example.com/images/x.png","https://media.example.com/images/x.png?redirect=x"))
            assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"IMAGE",input("image",8),List.of(new Attachment("image",bad)),null));
        verifyNoInteractions(media);
    }

    // 【测什么】本地文件与总引用数量、模型要求先验，不先上传再发现参数不合法。
    // 【怎么算红】将校验移到上传之后或漏MIME/参考数量会产生media调用或不抛错。
    @Test void invalidFilesAndRequiredInputsFailBeforeUpload() {
        var html=new MockMultipartFile("images","x.html","text/html","<script>".getBytes());
        var files=new ConversationMediaResolver.LocalFiles(new MockMultipartFile[]{html},null,null,null);
        assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"IMAGE",input("image",8),List.of(),files));
        assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"VIDEO",input("video",5),List.of(),null));
        var bad=(com.fasterxml.jackson.databind.node.ObjectNode)input("music",30);bad.put("origin","DIRECT");
        assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"AUDIO",bad,List.of(),null));
        verifyNoInteractions(media);
    }

    // 【测什么】审批重验保留上传地址但不重新上传，素材软删和换用户后拒绝。
    // 【怎么算红】requote不核owner/status或重新上传会使断言失败。
    @Test void requoteRechecksLibraryAndOwnerWithoutUploading() {
        String url="https://media.example.com/images/own.png";var a=asset(7,url,"IMAGE");
        when(assets.selectOne(any())).thenReturn(a);when(assets.selectById(8L)).thenReturn(a);
        var q=gateway.prepareDirect(7,"IMAGE",input("image",8),List.of(new Attachment("image",url)),null);
        clearInvocations(media);
        assertEquals(q,gateway.requote(7,"IMAGE",q.inputSnapshot()));verifyNoInteractions(media);
        a.setStatus("DELETED");assertThrows(BusinessException.class,()->gateway.requote(7,"IMAGE",q.inputSnapshot()));
        assertThrows(BusinessException.class,()->gateway.requote(8,"IMAGE",q.inputSnapshot()));
    }

    // 【测什么】真实resolver保留本地/库图片交错顺序，审批重放不再上传，视频音频引用使用对应域参数。
    // 【怎么算红】删掉imageOrder传递或canonical库assetId记录，顺序/归属断言失败。
    @Test void localUploadsUseRealResolverOrderingAndCanonicalOwnership() throws Exception {
        OssService storage=mock(OssService.class);String own="https://media.example.com/images/own.png";
        String uploaded="https://media.example.com/images/local.png",video="https://media.example.com/images/local.mp4",audio="https://media.example.com/images/local.mp3";
        var img=new MockMultipartFile("images","x.png","image/png",PNG);
        var vid=new MockMultipartFile("videos","x.mp4","video/mp4",new byte[]{0,0,0,24,102,116,121,112,105,115,111,109});
        var aud=new MockMultipartFile("audios","x.mp3","audio/mpeg",new byte[]{73,68,51,4,0,0,0,0,0,0});
        when(storage.upload(img)).thenReturn(uploaded);when(storage.upload(vid)).thenReturn(video);when(storage.upload(aud)).thenReturn(audio);
        var asset=asset(7,own,"IMAGE");when(assets.selectOne(any())).thenReturn(asset);when(assets.selectById(8L)).thenReturn(asset);
        var real=new AgentDirectGenerationGateway(new VideoEngineRegistry(List.of(engine)),access,submit,new ConversationMediaResolver(storage,oss),assets,oss,json);
        var files=new ConversationMediaResolver.LocalFiles(new MockMultipartFile[]{img},List.of("url","file"),new MockMultipartFile[]{vid},new MockMultipartFile[]{aud});
        var q=real.prepareDirect(7,"VIDEO",input("video",10),List.of(new Attachment("image",own)),files);
        var refs=q.inputSnapshot().path("references");assertEquals(4,refs.size());assertEquals(own,refs.get(0).path("url").asText());assertEquals(8,refs.get(0).path("assetId").asInt());
        assertEquals(uploaded,refs.get(1).path("url").asText());assertFalse(refs.get(1).has("assetId"));assertEquals("video",refs.get(2).path("type").asText());assertEquals("audio",refs.get(3).path("type").asText());
        clearInvocations(storage);assertEquals(q,real.requote(7,"VIDEO",q.inputSnapshot()));verifyNoInteractions(storage);
    }

    // 【测什么】本地上限、畸形顺序、未配置存储均在任何上传之前失败；上传失败不产生报价。
    // 【怎么算红】移除文件大小/顺序/存储封闭守卫会调用resolver，或上传异常仍进入estimate。
    @Test void limitsOrderingAndUploadFailureAreFailClosed() {
        var oversized=mock(org.springframework.web.multipart.MultipartFile.class);when(oversized.getSize()).thenReturn(50L*1024*1024+1);
        assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"IMAGE",input("image",8),List.of(),new ConversationMediaResolver.LocalFiles(new org.springframework.web.multipart.MultipartFile[]{oversized},null,null,null)));
        var img=new MockMultipartFile("images","x.png","image/png",PNG);
        assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"IMAGE",input("image",8),List.of(),new ConversationMediaResolver.LocalFiles(new MockMultipartFile[]{img},List.of("url"),null,null)));
        oss.setDomain(null);
        assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"IMAGE",input("image",8),List.of(),new ConversationMediaResolver.LocalFiles(new MockMultipartFile[]{img},null,null,null)));verifyNoInteractions(media);
        oss.setDomain("https://media.example.com");when(media.resolve(anyList(),any())).thenThrow(new IllegalStateException("upload failed"));
        var failure=assertThrows(BusinessException.class,()->gateway.prepareDirect(7,"IMAGE",input("image",8),List.of(),new ConversationMediaResolver.LocalFiles(new MockMultipartFile[]{img},null,null,null)));
        assertEquals(503,failure.getCode());verifyNoInteractions(submit);
    }
}
