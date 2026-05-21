package ru.bmstu.dzhioev.urlshortener.service;

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
import tools.jackson.databind.ObjectMapper;
import ru.bmstu.dzhioev.urlshortener.dto.CachedLink;
import ru.bmstu.dzhioev.urlshortener.entity.Link;
import ru.bmstu.dzhioev.urlshortener.repository.LinkRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
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
    private ObjectMapper objectMapper;
    @Mock
    private LinkAccessBuffer linkAccessBuffer;

    private LinkService linkService;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(objectMapper.writeValueAsString(any(CachedLink.class))).thenReturn("json");

        RedisHealthTracker tracker = new RedisHealthTracker(new SimpleMeterRegistry());
        linkService = new LinkService(
                linkRepository,
                redisTemplate,
                new SimpleMeterRegistry(),
                tracker,
                objectMapper,
                linkAccessBuffer
        );

        ReflectionTestUtils.setField(linkService, "linkTtlDays", 7L);
    }

    @Test
    @DisplayName("Создание ссылки: адрес без схемы принимается и сохраняется")
    void createLink_NewURL_NormalizesAndSaves() {
        String inputUrl = "google.com/search";
        String expectedNormalized = "http://google.com/search";

        when(linkRepository.findFirstByOriginalUrlAndExpiresAtAfterOrderByCreatedAtDesc(eq(expectedNormalized), any()))
                .thenReturn(Optional.empty());
        when(linkRepository.save(any(Link.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Link result = linkService.createLink(inputUrl);

        assertThat(result).isNotNull();
        assertThat(result.getOriginalUrl()).isEqualTo(expectedNormalized);
        assertThat(result.getShortCode()).hasSize(7);
        verify(valueOperations).set(eq("link:" + result.getShortCode()), eq("json"), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Получение адреса: успешное чтение из Redis")
    void getOriginalUrl_CacheHit_ReturnsUrlAndRecordsAccess() throws Exception {
        String shortCode = "mycode1";
        String originalUrl = "https://habr.com";
        CachedLink cachedLink = new CachedLink(originalUrl, Instant.now().plusSeconds(3600));

        when(valueOperations.get("link:" + shortCode)).thenReturn("json");
        when(objectMapper.readValue("json", CachedLink.class)).thenReturn(cachedLink);

        Optional<String> result = linkService.getOriginalUrl(shortCode);

        assertThat(result).isPresent().contains(originalUrl);
        verify(linkRepository, never()).findByShortCode(anyString());
        verify(linkAccessBuffer).recordAccess(shortCode, originalUrl);
    }

    @Test
    @DisplayName("Получение адреса: промах Redis и успешное чтение из PostgreSQL")
    void getOriginalUrl_CacheMissDbHit_RecordsAccess() {
        String shortCode = "mycode1";
        String originalUrl = "https://habr.com";
        Link dbLink = Link.builder()
                .shortCode(shortCode)
                .originalUrl(originalUrl)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(valueOperations.get("link:" + shortCode)).thenReturn(null);
        when(linkRepository.findByShortCode(shortCode)).thenReturn(Optional.of(dbLink));

        Optional<String> result = linkService.getOriginalUrl(shortCode);

        assertThat(result).isPresent().contains(originalUrl);
        verify(linkAccessBuffer).recordAccess(shortCode, originalUrl);
    }

    @Test
    @DisplayName("Получение адреса: просроченная ссылка из PostgreSQL не возвращается")
    void getOriginalUrl_DbHitButExpired_ReturnsEmpty() {
        String shortCode = "mycode1";
        Link expiredLink = Link.builder()
                .shortCode(shortCode)
                .expiresAt(Instant.now().minusSeconds(100))
                .build();

        when(valueOperations.get("link:" + shortCode)).thenReturn(null);
        when(linkRepository.findByShortCode(shortCode)).thenReturn(Optional.of(expiredLink));

        Optional<String> result = linkService.getOriginalUrl(shortCode);

        assertThat(result).isEmpty();
        verify(redisTemplate).delete("link:" + shortCode);
        verifyNoInteractions(linkAccessBuffer);
    }
}
