package ru.bmstu.dzhioev.urlshortener.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import ru.bmstu.dzhioev.urlshortener.entity.Link;
import ru.bmstu.dzhioev.urlshortener.event.LinkAccessEvent;
import ru.bmstu.dzhioev.urlshortener.repository.LinkRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LinkServiceTest {

    @Mock
    private LinkRepository linkRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private LinkService linkService;

    @BeforeEach
    void setUp() {
        // Мокаем возвращаемое значение для redisTemplate.opsForValue() 
        // leniency - убираем строгие проверки Mockito, так как не во всех тестах нужен кэш
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        RedisHealthTracker tracker = new RedisHealthTracker(new SimpleMeterRegistry());

        linkService = new LinkService(linkRepository, redisTemplate, eventPublisher, new SimpleMeterRegistry(), tracker);

        // Внедряем @Value ("app.link-ttl-days") через Reflection
        ReflectionTestUtils.setField(linkService, "linkTtlDays", 7L);
    }

    @Test
    @DisplayName("Создание ссылки: нормализация URL и сохранение в БД")
    void createLink_NewURL_NormalizesAndSaves() {
        // Убрали http:// 
        String inputUrl = "google.com/search";
        String expectedNormalized = "http://google.com/search";

        when(linkRepository.findByOriginalUrl(expectedNormalized)).thenReturn(Optional.empty());
        when(linkRepository.save(any(Link.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Link result = linkService.createLink(inputUrl);

        assertThat(result).isNotNull();
        assertThat(result.getOriginalUrl()).isEqualTo(expectedNormalized);
        assertThat(result.getShortCode()).hasSize(7);

        // Проверяем, что пытались положить свежую запись в Redis
        verify(valueOperations).set(eq("link:" + result.getShortCode()), eq(expectedNormalized), eq(7L), eq(TimeUnit.DAYS));
    }

    @Test
    @DisplayName("Получение URL: Cache HIT (есть в Redis)")
    void getOriginalUrl_CacheHit_ReturnsUrlAndPublishesEvent() {
        String shortCode = "mycode1";
        String originalUrl = "https://habr.com";

        when(valueOperations.get("link:" + shortCode)).thenReturn(originalUrl);

        Optional<String> result = linkService.getOriginalUrl(shortCode);

        assertThat(result).isPresent().contains(originalUrl);

        // Базу Дергать не должны были!
        verify(linkRepository, never()).findByShortCode(anyString());

        // Event должен быть отправлен
        ArgumentCaptor<LinkAccessEvent> eventCaptor = ArgumentCaptor.forClass(LinkAccessEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().shortCode()).isEqualTo(shortCode);
    }

    @Test
    @DisplayName("Получение URL: Cache MISS, DB HIT (прогрев кэша)")
    void getOriginalUrl_CacheMissDbHit_PopulatesCache() {
        String shortCode = "mycode1";
        String originalUrl = "https://habr.com";
        Link dbLink = Link.builder()
                .shortCode(shortCode)
                .originalUrl(originalUrl)
                .expiresAt(Instant.now().plusSeconds(3600)) // Активная
                .build();

        when(valueOperations.get("link:" + shortCode)).thenReturn(null); // В кэше пусто
        when(linkRepository.findByShortCode(shortCode)).thenReturn(Optional.of(dbLink));

        Optional<String> result = linkService.getOriginalUrl(shortCode);

        assertThat(result).isPresent().contains(originalUrl);

        // Должны прогреть кэш
        verify(valueOperations).set(eq("link:" + shortCode), eq(originalUrl), eq(7L), eq(TimeUnit.DAYS));
    }

    @Test
    @DisplayName("Получение URL: Просроченная ссылка в БД (возвращаем 404 и удаляем из кэша)")
    void getOriginalUrl_DbHitButExpired_ReturnsEmpty() {
        String shortCode = "mycode1";
        Link expiredLink = Link.builder()
                .shortCode(shortCode)
                .expiresAt(Instant.now().minusSeconds(100)) // Просрочено!
                .build();

        when(valueOperations.get("link:" + shortCode)).thenReturn(null);
        when(linkRepository.findByShortCode(shortCode)).thenReturn(Optional.of(expiredLink));

        Optional<String> result = linkService.getOriginalUrl(shortCode);

        assertThat(result).isEmpty();
        verify(redisTemplate).delete("link:" + shortCode);
    }
}

