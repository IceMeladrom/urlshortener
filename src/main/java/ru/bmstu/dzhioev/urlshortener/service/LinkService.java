package ru.bmstu.dzhioev.urlshortener.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import ru.bmstu.dzhioev.urlshortener.entity.Link;
import ru.bmstu.dzhioev.urlshortener.event.LinkAccessEvent;
import ru.bmstu.dzhioev.urlshortener.repository.LinkRepository;
import ru.bmstu.dzhioev.urlshortener.utils.Util;

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
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final RedisHealthTracker redisHealthTracker;

    @Value("${app.link-ttl-days:7}")
    private long linkTtlDays;

    public Link createLink(String originalUrl) {
        String normalized = normalizeUrl(originalUrl);

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
                Optional<Link> race = repository.findByOriginalUrl(normalized);
                if (race.isPresent()) {
                    safeCacheLink(race.get());
                    return race.get();
                }
                log.info("Коллизия shortCode, попытка {}/{}", attempt, MAX_CREATE_ATTEMPTS);
                if (attempt == MAX_CREATE_ATTEMPTS) {
                    throw new IllegalStateException("Не удалось создать ссылку после 5 попыток", ex);
                }
            }
        }
        throw new IllegalStateException("Недостижимый код");
    }

    public Optional<String> getOriginalUrl(String shortCode) {
        Optional<String> fromCache = getFromCache(KEY_PREFIX + shortCode);
        if (fromCache.isPresent()) {
            eventPublisher.publishEvent(new LinkAccessEvent(shortCode));
            return fromCache;
        }
        return getFromDatabase(shortCode);
    }

    private Optional<Link> findActiveLink(String normalizedUrl) {
        try {
            return repository.findByOriginalUrl(normalizedUrl)
                    .filter(l -> l.getExpiresAt().isAfter(Instant.now()));
        } catch (Exception e) {
            log.warn("Ошибка базы при поиске: {}", e.getMessage());
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

    private Optional<String> getFromCache(String key) {
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                meterRegistry.counter("cache.hit").increment();
                redisTemplate.expire(key, linkTtlDays, TimeUnit.DAYS);
                redisHealthTracker.markAvailable();
                return Optional.of(cached);
            }
            meterRegistry.counter("cache.miss").increment();
            return Optional.empty();
        } catch (RedisConnectionFailureException e) {
            redisHealthTracker.markUnavailable();
            return Optional.empty();
        }
    }

    private Optional<String> getFromDatabase(String shortCode) {
        Optional<Link> linkOpt = repository.findByShortCode(shortCode);
        if (linkOpt.isEmpty()) return Optional.empty();

        Link link = linkOpt.get();
        if (link.getExpiresAt().isBefore(Instant.now())) {
            safeDeleteFromCache(KEY_PREFIX + shortCode);
            return Optional.empty();
        }

        safeCacheLink(link);
        eventPublisher.publishEvent(new LinkAccessEvent(shortCode));
        return Optional.of(link.getOriginalUrl());
    }

    private void safeCacheLink(Link link) {
        String key = KEY_PREFIX + link.getShortCode();
        try {
            redisTemplate.opsForValue().set(key, link.getOriginalUrl(), linkTtlDays, TimeUnit.DAYS);
            meterRegistry.counter("cache.set").increment();
            redisHealthTracker.markAvailable();
        } catch (Exception e) {
            redisHealthTracker.markUnavailable();
        }
    }

    private void safeDeleteFromCache(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception ignored) {
        }
    }

    private String normalizeUrl(String rawUrl) {
        String url = rawUrl != null ? rawUrl.trim() : "";
        if (!url.matches("(?i)^https?://.*")) {
            url = "http://" + url;
        }
        try {
            // UriComponentsBuilder безопаснее и корректнее обрабатывает edge-кейсы
            return UriComponentsBuilder.fromUriString(url).build().normalize().toUriString();
        } catch (IllegalArgumentException e) {
            log.warn("Нелегальный формат URL: {}", rawUrl);
            return url;
        }
    }
}