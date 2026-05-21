package ru.bmstu.dzhioev.urlshortener.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import ru.bmstu.dzhioev.urlshortener.dto.CachedLink;
import ru.bmstu.dzhioev.urlshortener.entity.Link;
import ru.bmstu.dzhioev.urlshortener.repository.LinkRepository;
import ru.bmstu.dzhioev.urlshortener.utils.Util;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);
    private static final String KEY_PREFIX = "link:";
    private static final int MAX_CREATE_ATTEMPTS = 5;

    private final LinkRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final RedisHealthTracker redisHealthTracker;
    private final ObjectMapper objectMapper;
    private final LinkAccessBuffer linkAccessBuffer;

    @Value("${app.link-ttl-days:7}")
    private long linkTtlDays;

    public Link createLink(String originalUrl) {
        String normalized = normalizeAndValidateUrl(originalUrl);

        Optional<Link> existing = findActiveLink(normalized);
        if (existing.isPresent()) {
            safeCacheLink(existing.get());
            return existing.get();
        }

        for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
            try {
                Link saved = repository.save(buildLink(normalized));
                safeCacheLink(saved);
                return saved;
            } catch (DataIntegrityViolationException ex) {
                Optional<Link> race = findActiveLink(normalized);
                if (race.isPresent()) {
                    safeCacheLink(race.get());
                    return race.get();
                }
                if (attempt == MAX_CREATE_ATTEMPTS) {
                    throw new IllegalStateException("Не удалось создать ссылку после нескольких попыток", ex);
                }
            }
        }

        throw new IllegalStateException("Не удалось создать ссылку");
    }

    public Optional<String> getOriginalUrl(String shortCode) {
        Optional<CachedLink> fromCache = getFromCache(shortCode);
        if (fromCache.isPresent()) {
            CachedLink cachedLink = fromCache.get();
            linkAccessBuffer.recordAccess(shortCode, cachedLink.url());
            return Optional.of(cachedLink.url());
        }

        return getFromDatabase(shortCode);
    }

    private Optional<Link> findActiveLink(String normalizedUrl) {
        try {
            return repository.findFirstByOriginalUrlAndExpiresAtAfterOrderByCreatedAtDesc(
                    normalizedUrl,
                    Instant.now()
            );
        } catch (Exception e) {
            log.warn("Ошибка базы при поиске ссылки: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Link buildLink(String normalizedUrl) {
        Instant now = Instant.now();
        return Link.builder()
                .originalUrl(normalizedUrl)
                .shortCode(Util.generateShortCode())
                .createdAt(now)
                .expiresAt(now.plus(linkTtlDays, ChronoUnit.DAYS))
                .clickCount(0L)
                .build();
    }

    private Optional<CachedLink> getFromCache(String shortCode) {
        if (!redisHealthTracker.isAvailable()) {
            return Optional.empty();
        }

        String key = KEY_PREFIX + shortCode;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                meterRegistry.counter("cache.miss").increment();
                return Optional.empty();
            }

            CachedLink cachedLink = objectMapper.readValue(cached, CachedLink.class);
            if (!cachedLink.expiresAt().isAfter(Instant.now())) {
                meterRegistry.counter("cache.expired").increment();
                safeDeleteFromCache(key);
                return Optional.empty();
            }

            meterRegistry.counter("cache.hit").increment();
            return Optional.of(cachedLink);
        } catch (JacksonException e) {
            meterRegistry.counter("cache.invalid").increment();
            safeDeleteFromCache(key);
            return Optional.empty();
        } catch (Exception e) {
            meterRegistry.counter("redis.errors").increment();
            redisHealthTracker.markUnavailable();
            log.warn("Redis недоступен при чтении ссылки: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> getFromDatabase(String shortCode) {
        Optional<Link> linkOpt = repository.findByShortCode(shortCode);
        if (linkOpt.isEmpty()) {
            return Optional.empty();
        }

        Link link = linkOpt.get();
        if (!link.getExpiresAt().isAfter(Instant.now())) {
            safeDeleteFromCache(KEY_PREFIX + shortCode);
            return Optional.empty();
        }

        linkAccessBuffer.recordAccess(shortCode, link.getOriginalUrl());
        return Optional.of(link.getOriginalUrl());
    }

    private void safeCacheLink(Link link) {
        if (!redisHealthTracker.isAvailable()) {
            return;
        }

        try {
            CachedLink cachedLink = new CachedLink(link.getOriginalUrl(), link.getExpiresAt());
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + link.getShortCode(),
                    objectMapper.writeValueAsString(cachedLink),
                    cacheTtlSeconds(link.getExpiresAt()),
                    TimeUnit.SECONDS
            );
            meterRegistry.counter("cache.set").increment();
            redisHealthTracker.markAvailable();
        } catch (Exception e) {
            meterRegistry.counter("redis.errors").increment();
            redisHealthTracker.markUnavailable();
            log.debug("Не удалось сохранить ссылку в Redis: {}", e.getMessage());
        }
    }

    private void safeDeleteFromCache(String key) {
        if (!redisHealthTracker.isAvailable()) {
            return;
        }

        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            meterRegistry.counter("redis.errors").increment();
            redisHealthTracker.markUnavailable();
        }
    }

    private long cacheTtlSeconds(Instant expiresAt) {
        return Math.max(1, Duration.between(Instant.now(), expiresAt).getSeconds());
    }

    private String normalizeAndValidateUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (!url.matches("(?i)^https?://.*")) {
            url = "http://" + url;
        }

        URI uri;
        try {
            uri = URI.create(url).normalize();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Неверный формат URL");
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            throw new IllegalArgumentException("Неверный формат URL");
        }
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException("Неверный формат URL");
        }
        if (!isValidHost(host)) {
            throw new IllegalArgumentException("Неверный формат URL");
        }

        return uri.toString();
    }

    private boolean isValidHost(String host) {
        return host.equalsIgnoreCase("localhost")
                || host.contains(".")
                || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }
}
