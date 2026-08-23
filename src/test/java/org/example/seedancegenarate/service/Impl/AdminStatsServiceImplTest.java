package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.ConfigCacheProperties;
import org.example.seedancegenarate.dto.AdminDashboardResponse;
import org.example.seedancegenarate.dto.ApiCallSummary;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.example.seedancegenarate.mapper.ApiKeyMapper;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminStatsServiceImplTest {

    @Mock
    private VideoTaskService videoTaskService;

    @Mock
    private VideoTaskMapper videoTaskMapper;

    @Mock
    private AppUserService appUserService;

    @Mock
    private ApiCallLogMapper apiCallLogMapper;

    @Mock
    private ApiKeyMapper apiKeyMapper;

    @Mock
    private VideoEngineRegistry videoEngineRegistry;

    @Mock
    private ConfigCacheProperties cacheProperties;

    @InjectMocks
    private AdminStatsServiceImpl adminStatsService;

    @BeforeEach
    void setUp() {
        ConfigCacheProperties.Stats stats = new ConfigCacheProperties.Stats();
        stats.setEnabled(false); // 测试时不走缓存，直接触发 compute
        when(cacheProperties.getStats()).thenReturn(stats);
    }

    @Test
    @DisplayName("管理端看板聚合: 单条 SQL 聚合结果正确组装")
    void testDashboardAggregation() {
        when(videoTaskMapper.selectDashboardOverview(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Map.of(
                        "totalTasks", 100L,
                        "totalSuccess", 90L,
                        "totalFailed", 10L,
                        "todayTasks", 5L,
                        "todaySuccess", 4L,
                        "todayFailed", 1L,
                        "todayCost", new BigDecimal("25.00"),
                        "monthCost", new BigDecimal("450.00")
                ));

        when(videoTaskMapper.selectDailyTrend(any(LocalDateTime.class)))
                .thenReturn(List.of(
                        Map.of("date", "2026-08-19", "count", 20L),
                        Map.of("date", "2026-08-20", "count", 5L)
                ));

        when(videoTaskMapper.selectModelStats())
                .thenReturn(List.of(
                        Map.of("model", "minimax-video", "count", 60L, "cost", new BigDecimal("300.00")),
                        Map.of("model", "seedance-v1", "count", 40L, "cost", new BigDecimal("150.00"))
                ));

        when(videoTaskMapper.selectTopUsers())
                .thenReturn(List.of(
                        Map.of("userId", 1001L, "totalCost", new BigDecimal("200.00")),
                        Map.of("userId", 1002L, "totalCost", new BigDecimal("100.00"))
                ));

        AppUser user1 = new AppUser();
        user1.setId(1001L);
        user1.setUsername("alice");
        AppUser user2 = new AppUser();
        user2.setId(1002L);
        user2.setUsername("bob");
        when(appUserService.listByIds(List.of(1001L, 1002L))).thenReturn(List.of(user1, user2));

        AdminDashboardResponse response = adminStatsService.dashboard();

        assertNotNull(response);
        assertEquals(100L, response.totalTask());
        assertEquals(90L, response.totalSuccess());
        assertEquals(10L, response.totalFailed());
        assertEquals(90.0, response.successRate());
        assertEquals(5L, response.todayTask());
        assertEquals(new BigDecimal("25.00"), response.todayCost());
        assertEquals(new BigDecimal("450.00"), response.monthCost());
        assertEquals(7, response.dailyTrend().size());
        assertEquals(2, response.modelStats().size());
        assertEquals(2, response.topUsers().size());
        assertEquals("alice", response.topUsers().get(0).username());
        assertEquals("bob", response.topUsers().get(1).username());
    }

    @Test
    @DisplayName("API 调用汇总: 单条 SQL 聚合正确统计")
    void testApiCallSummaryAggregation() {
        when(apiCallLogMapper.selectStatusCounts(1L))
                .thenReturn(List.of(
                        Map.of("status", "SUCCESS", "count", 50L),
                        Map.of("status", "FAILED", "count", 5L),
                        Map.of("status", "REJECTED", "count", 2L)
                ));

        when(apiCallLogMapper.selectErrorCodeCounts(1L))
                .thenReturn(List.of(
                        Map.of("errorCode", "INSUFFICIENT_BALANCE", "count", 4L),
                        Map.of("errorCode", "RATE_LIMIT_EXCEEDED", "count", 1L)
                ));

        ApiCallSummary summary = adminStatsService.apiCallSummary(1L);

        assertNotNull(summary);
        assertEquals(57L, summary.total());
        assertEquals(50L, summary.success());
        assertEquals(5L, summary.failed());
        assertEquals(2L, summary.rejected());
        assertEquals(2, summary.byErrorCode().size());
        assertEquals("INSUFFICIENT_BALANCE", summary.byErrorCode().get(0).errorCode());
        assertEquals(4L, summary.byErrorCode().get(0).count());
    }
}
