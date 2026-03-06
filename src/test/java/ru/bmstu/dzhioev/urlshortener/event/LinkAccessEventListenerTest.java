package ru.bmstu.dzhioev.urlshortener.event;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import ru.bmstu.dzhioev.urlshortener.repository.LinkRepository;
import ru.bmstu.dzhioev.urlshortener.service.RedisHealthTracker;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LinkAccessEventListenerTest {

    @Mock
    private LinkRepository linkRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisHealthTracker healthTracker;
    private LinkAccessEventListener listener;

    @BeforeEach
    void setUp() {
        healthTracker = new RedisHealthTracker(new SimpleMeterRegistry());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        listener = new LinkAccessEventListener(
                linkRepository, redisTemplate, new SimpleMeterRegistry(), healthTracker
        );

        ReflectionTestUtils.setField(listener, "linkTtlDays", 7L);
        ReflectionTestUtils.setField(listener, "extendThresholdMinutes", 60L);
    }

    @Test
    @DisplayName("Успешное продление жизни ссылки (Lock получен)")
    void onLinkAccess_LockAcquired_ExtendsExpiryAndIncrementsClick() {
        String code = "123code";
        // Мокаем получение блокировки (setIfAbsent вернул true)
        when(valueOperations.setIfAbsent(eq("link:extend:" + code), eq("1"), eq(60L), eq(TimeUnit.MINUTES)))
                .thenReturn(true);

        listener.onLinkAccess(new LinkAccessEvent(code));

        // Счётчик должен инкрементироваться
        verify(linkRepository).incrementClickCount(code);
        // Должен быть запрос на продление в БД
        verify(linkRepository).extendExpiryIfLess(eq(code), any());
    }

    @Test
    @DisplayName("Блокировка не получена (слишком часто), клик засчитан, продления БД нет")
    void onLinkAccess_LockNotAcquired_OnlyIncrementsClick() {
        String code = "123code";
        // Мокаем, что блокировка занята (setIfAbsent вернул false)
        when(valueOperations.setIfAbsent(eq("link:extend:" + code), eq("1"), eq(60L), eq(TimeUnit.MINUTES)))
                .thenReturn(false);

        listener.onLinkAccess(new LinkAccessEvent(code));

        // Счётчик кликов инкрементируется всегда
        verify(linkRepository).incrementClickCount(code);
        // Запроса на продление не должно быть (сберегли БД!)
        verify(linkRepository, never()).extendExpiryIfLess(anyString(), any());
    }

    @Test
    @DisplayName("Если RedisHealthTracker пометил Redis как недоступный, в Redis даже не ходим")
    void onLinkAccess_RedisUnavailable_SkipRedisCall() {
        healthTracker.markUnavailable(); // Симулируем падение Redis
        String code = "123code";

        listener.onLinkAccess(new LinkAccessEvent(code));

        // Засчитали клик
        verify(linkRepository).incrementClickCount(code);
        // Никаких походов в Redis
        verifyNoInteractions(redisTemplate);
        // Никаких продлений
        verify(linkRepository, never()).extendExpiryIfLess(anyString(), any());
    }
}

