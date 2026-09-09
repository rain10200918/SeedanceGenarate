package org.example.seedancegenarate.agent;

import org.junit.jupiter.api.Test;
import org.example.seedancegenarate.agent.application.AgentImageInputs;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.entity.UserAsset;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.UserAssetMapper;
import org.example.seedancegenarate.service.AssetService;
import org.springframework.mock.web.MockMultipartFile;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentImageInputsTest {
    final AssetService assets=mock(AssetService.class);
    final UserAssetMapper mapper=mock(UserAssetMapper.class);
    final OssConfig oss=new OssConfig();
    final AgentImageInputs inputs=new AgentImageInputs(assets,mapper,oss);
    AgentImageInputsTest(){oss.setDomain("https://media.example.test");}
    UserAsset asset(long id,long owner,String url) {
        var a=new UserAsset();a.setId(id);a.setUserId(owner);a.setType("IMAGE");a.setStatus("ACTIVE");a.setUrl(url);return a;
    }
    // 【测什么】图片引用只接受本人有效图片，保持调用者顺序，拒绝恶意地址与异常ID。
    // 【怎么算红】删除resolve的归属检查或排序改为ID升序，这条必须失败。
    @Test void resolvesOnlyOwnedActiveImagesInOrder() {
        when(mapper.selectById(2L)).thenReturn(asset(2,1,"https://media.example.test/images/b.png"));
        when(mapper.selectById(1L)).thenReturn(asset(1,1,"https://media.example.test/images/a.png"));
        assertEquals(List.of("2","1"),inputs.resolve(1,List.of("2","1")).stream().map(AgentImageInputs.ImageRef::assetId).toList());
        assertThrows(BusinessException.class,()->inputs.resolve(2,List.of("1")));
        for(String id:List.of("0","-1","01","9223372036854775808","x"))assertThrows(BusinessException.class,()->inputs.resolve(1,List.of(id)));
        assertThrows(BusinessException.class,()->inputs.resolve(1,List.of("1","1")));
        when(mapper.selectById(1L)).thenReturn(asset(1,1,"https://other.example.test/a.png"));
        assertThrows(BusinessException.class,()->inputs.resolve(1,List.of("1")));
        var deleted=asset(1,1,"https://media.example.test/a.png");deleted.setStatus("DELETED");when(mapper.selectById(1L)).thenReturn(deleted);
        assertThrows(BusinessException.class,()->inputs.resolve(1,List.of("1")));
    }
    // 【测什么】本地文件必须是真实图片且不超过10MiB，上传失败不泄漏上游错误。
    // 【怎么算红】删除magic校验或将10MiB上限移除，伪装图片断言必须失败。
    @Test void validatesBeforeUploadAndMapsFailure() throws Exception {
        var valid=new MockMultipartFile("file","x.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10});
        when(assets.uploadImage(1L,null,valid)).thenReturn(asset(1,1,"https://media.example.test/images/x.png"));
        assertEquals("1",inputs.upload(1,valid).assetId());
        assertThrows(BusinessException.class,()->inputs.upload(1,new MockMultipartFile("file","x.png","image/png","not png".getBytes())));
        assertThrows(BusinessException.class,()->inputs.upload(1,new MockMultipartFile("file","x.png","image/png",new byte[10*1024*1024+1])));
        when(assets.uploadImage(1L,null,valid)).thenThrow(new RuntimeException("secret provider body"));
        var error=assertThrows(BusinessException.class,()->inputs.upload(1,valid));assertEquals(503,error.getCode());assertFalse(error.getMessage().contains("secret"));
        verify(assets,times(2)).uploadImage(anyLong(),isNull(),any());
    }
}
