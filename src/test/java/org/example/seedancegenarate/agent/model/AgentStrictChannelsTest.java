package org.example.seedancegenarate.agent.model;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.example.seedancegenarate.entity.LlmChannel;
import org.example.seedancegenarate.mapper.LlmChannelMapper;
import org.example.seedancegenarate.service.llm.LlmChannelRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentStrictChannelsTest {
    // 【测什么】Agent不使用已缓存的启用状态或YAML，数据库断开必须失败关闭。
    // 【怎么算红】strict入口改调用channels/find将接受旧缓存或fallback，测试失败。
    @Test void strictLookupNeverFallsBackToCacheOrYaml() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), LlmChannel.class);
        var mapper = mock(LlmChannelMapper.class);
        var config = new PromptOptimizeConfig();
        config.setUrl("http://yaml"); config.setApiKey("test-key"); config.setModel("yaml-model");
        var registry = new LlmChannelRegistry(mapper, config);
        var row = new LlmChannel(); row.setName("own"); row.setEnabled(true); row.setArchived(false);
        row.setModel("model"); row.setBaseUrl("http://own"); row.setApiKey("test-key");
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(row));
        assertEquals(1, registry.channels().size());
        row.setEnabled(false);
        when(mapper.selectById("own")).thenReturn(row);
        assertNull(registry.findRoutableStrict("own"));
        when(mapper.selectById("own")).thenThrow(new RuntimeException("database unavailable"));
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenThrow(new RuntimeException("database unavailable"));
        assertThrows(RuntimeException.class, () -> registry.findRoutableStrict("own"));
        assertThrows(RuntimeException.class, registry::routableStrict);
        assertEquals(1, registry.channels().size(), "legacy cached behavior remains unchanged");
    }
}
