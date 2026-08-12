package org.example.seedancegenarate.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.service.ConfigSnapshotReloadable;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 订阅配置失效通知，按类型让对应的快照持有者重载。
 * <p>
 * 收到未知类型就忽略——新增一种配置快照时，老实例不认识新类型也不该报错。
 * 单个实现重载失败不影响其他实现（各自 try 住）。
 */
@Slf4j
@Component
public class ConfigInvalidationSubscriber implements MessageListener {

    private final List<ConfigSnapshotReloadable> reloadables;
    private final ObjectMapper objectMapper;

    public ConfigInvalidationSubscriber(List<ConfigSnapshotReloadable> reloadables,
                                       ObjectMapper objectMapper) {
        this.reloadables = reloadables;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String type = parseType(new String(message.getBody(), StandardCharsets.UTF_8));
        if (type == null) {
            return;
        }
        for (ConfigSnapshotReloadable reloadable : reloadables) {
            if (!type.equals(reloadable.snapshotType())) {
                continue;
            }
            try {
                reloadable.reload();
                log.info("按失效通知重载配置快照: type={}", type);
            } catch (Exception e) {
                log.warn("重载配置快照失败（保留上一份快照，兜底重载将再试）: type={}, reason={}",
                        type, e.getMessage());
            }
        }
    }

    private String parseType(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            JsonNode type = json.get("type");
            return type == null || type.isNull() ? null : type.asText();
        } catch (Exception e) {
            log.warn("解析配置失效通知失败: {}", body);
            return null;
        }
    }
}
