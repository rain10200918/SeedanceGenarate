package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.entity.UserAsset;
import org.example.seedancegenarate.mapper.UserAssetMapper;
import org.example.seedancegenarate.service.AssetService;
import org.junit.jupiter.api.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSnapshotImageBatchTest {
    final AgentWorkspaceIntegrationTest f=new AgentWorkspaceIntegrationTest();
    final UserAssetMapper mapper=mock(UserAssetMapper.class);
    final Map<Long,UserAsset> rows=new LinkedHashMap<>(); AgentApplication app;
    @BeforeEach void setup() throws Exception {
        f.setup();var oss=new OssConfig();oss.setDomain("https://media.example.test");
        when(mapper.selectById(anyLong())).thenAnswer(a->rows.get(a.getArgument(0)));
        when(mapper.selectList(any(Wrapper.class))).thenAnswer(a->new ArrayList<>(rows.values()));
        app=new AgentApplication(f.store,f.tx,f.jobs,f.models,f.json,mock(AgentApprovalApplication.class),f.approvals,
                new AgentImageInputs(mock(AssetService.class),mapper,oss));
    }
    void add(long id,long owner) {
        var a=new UserAsset();a.setId(id);a.setUserId(owner);a.setType("IMAGE");a.setStatus("ACTIVE");a.setUrl("https://media.example.test/"+id+".png");rows.put(id,a);
        var parts=f.json.createArrayNode();parts.addObject().put("type","image").put("assetId",Long.toString(id)).put("url","https://old.example.test/private");
        f.store.message(f.session(),null,"USER",null,parts,null,null);
    }
    // 【测什么】100条图片消息一次批量查询，保持顺序，失效/他人/非法地址图片不泄漏旧URL。
    // 【怎么算红】恢复逐图resolve则selectById被调用；删checked会暴露外人或失效图片。
    @Test void hundredImagesUseOneReadAndInvalidImagesRemainUnavailable() {
        for(long id=1;id<=100;id++)add(id,1);
        rows.get(2L).setUserId(2L);rows.get(3L).setStatus("DELETED");rows.get(4L).setUrl("https://other.example.test/x");
        var messages=app.snapshot(1,f.id).messages();
        for(int i=0;i<100;i++) {
            var p=messages.get(i).parts().get(0);assertEquals(""+(i+1),p.path("assetId").asText());
            boolean valid=i<1||i>3;assertEquals(!valid,p.path("unavailable").asBoolean());assertEquals(valid,p.has("url"));
        }
        verify(mapper,never()).selectById(anyLong());verify(mapper).selectList(any(Wrapper.class));
    }
    // 【测什么】没有图片不查表，删除后下一请求隐藏，数据库失败不伪装成无图成功。
    // 【怎么算红】跨请求缓存会继续返回已删除URL，吞DB异常会不再抛错。
    @Test void emptyFreshnessAndDatabaseFailure() {
        app.snapshot(1,f.id);verifyNoInteractions(mapper);
        add(1,1);assertTrue(app.snapshot(1,f.id).messages().get(0).parts().get(0).has("url"));
        rows.clear();assertFalse(app.snapshot(1,f.id).messages().get(0).parts().get(0).has("url"));
        when(mapper.selectList(any(Wrapper.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->app.snapshot(1,f.id));
    }
}
