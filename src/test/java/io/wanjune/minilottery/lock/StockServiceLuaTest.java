package io.wanjune.minilottery.lock;

import io.wanjune.minilottery.mapper.ActivityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockServiceLuaTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ActivityMapper activityMapper;

    @Test
    void deductStock_shouldReturnTrueWhenLuaScriptSucceeds() {
        StockService stockService = new StockService(redissonClient, stringRedisTemplate, activityMapper);
        when(stringRedisTemplate.execute(any(), eq(List.of("stock:A001")), any())).thenReturn(8L);

        boolean result = stockService.deductStock("A001", LocalDateTime.now().plusDays(1));

        assertTrue(result);
    }

    @Test
    void deductStock_shouldReturnFalseWhenLuaScriptReportsNoStock() {
        StockService stockService = new StockService(redissonClient, stringRedisTemplate, activityMapper);
        when(stringRedisTemplate.execute(any(), eq(List.of("stock:A001")), any())).thenReturn(-1L);

        boolean result = stockService.deductStock("A001", LocalDateTime.now().plusDays(1));

        assertFalse(result);
    }

    @Test
    void deductStock_shouldReturnFalseWhenLuaScriptReportsSegmentConflict() {
        StockService stockService = new StockService(redissonClient, stringRedisTemplate, activityMapper);
        when(stringRedisTemplate.execute(any(), eq(List.of("stock:A001")), any())).thenReturn(-2L);

        boolean result = stockService.deductStock("A001", LocalDateTime.now().plusDays(1));

        assertFalse(result);
    }
}
