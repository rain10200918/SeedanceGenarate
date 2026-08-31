package org.example.seedancegenarate.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.service.ConfigInvalidationNotifier;
import org.example.seedancegenarate.service.ConfigSnapshotReloadable;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失效广播订阅：按类型路由重载、未知类型忽略、脏消息不抛、
 * 单个实现重载失败不影响其他实现。
 */
class ConfigInvalidationSubscriberTest {

    @Test
    void reloadsOnlyTheMatchingSnapshotHolder() {
        ConfigSnapshotReloadable modelAccess = reloadable(ConfigInvalidationNotifier.TYPE_MODEL_ACCESS);
        ConfigSnapshotReloadable pricing = reloadable(ConfigInvalidationNotifier.TYPE_PRICING);
        org.example.seedancegenarate.service.PublicModelPricingService publicModelPricingService = mock(org.example.seedancegenarate.service.PublicModelPricingService.class);
        ConfigInvalidationSubscriber subscriber = new ConfigInvalidationSubscriber(
                List.of(modelAccess, pricing), new ObjectMapper(), publicModelPricingService);

        subscriber.onMessage(message("{\"type\":\"MODEL_ACCESS\"}"), null);

        verify(modelAccess).reload();
        verify(pricing, never()).reload();
        verify(publicModelPricingService).clearCache();
    }

    @Test
    void unknownTypeIsIgnored() {
        ConfigSnapshotReloadable modelAccess = reloadable(ConfigInvalidationNotifier.TYPE_MODEL_ACCESS);
        org.example.seedancegenarate.service.PublicModelPricingService publicModelPricingService = mock(org.example.seedancegenarate.service.PublicModelPricingService.class);
        ConfigInvalidationSubscriber subscriber = new ConfigInvalidationSubscriber(
                List.of(modelAccess), new ObjectMapper(), publicModelPricingService);

        // 新版本加了新类型，老实例不认识——不该报错，也不该乱重载
        subscriber.onMessage(message("{\"type\":\"SOMETHING_NEW\"}"), null);

        verify(modelAccess, never()).reload();
    }

    @Test
    void malformedPayloadDoesNotThrow() {
        ConfigSnapshotReloadable modelAccess = reloadable(ConfigInvalidationNotifier.TYPE_MODEL_ACCESS);
        org.example.seedancegenarate.service.PublicModelPricingService publicModelPricingService = mock(org.example.seedancegenarate.service.PublicModelPricingService.class);
        ConfigInvalidationSubscriber subscriber = new ConfigInvalidationSubscriber(
                List.of(modelAccess), new ObjectMapper(), publicModelPricingService);

        subscriber.onMessage(message("not json"), null);
        subscriber.onMessage(message("{}"), null);

        verify(modelAccess, never()).reload();
    }

    @Test
    void oneFailingReloadDoesNotBlockOthers() {
        ConfigSnapshotReloadable faulty = reloadable(ConfigInvalidationNotifier.TYPE_PRICING);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(faulty).reload();
        ConfigSnapshotReloadable healthy = reloadable(ConfigInvalidationNotifier.TYPE_PRICING);
        org.example.seedancegenarate.service.PublicModelPricingService publicModelPricingService = mock(org.example.seedancegenarate.service.PublicModelPricingService.class);
        ConfigInvalidationSubscriber subscriber = new ConfigInvalidationSubscriber(
                List.of(faulty, healthy), new ObjectMapper(), publicModelPricingService);

        subscriber.onMessage(message("{\"type\":\"PRICING\"}"), null);

        verify(faulty).reload();
        verify(healthy).reload();
        verify(publicModelPricingService).clearCache();
    }

    private ConfigSnapshotReloadable reloadable(String type) {
        ConfigSnapshotReloadable reloadable = mock(ConfigSnapshotReloadable.class);
        when(reloadable.snapshotType()).thenReturn(type);
        return reloadable;
    }

    private Message message(String body) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
