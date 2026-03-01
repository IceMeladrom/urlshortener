package ru.bmstu.dzhioev.urlshortener.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.bmstu.dzhioev.urlshortener.entity.Link;
import ru.bmstu.dzhioev.urlshortener.event.LinkAccessEvent;
import ru.bmstu.dzhioev.urlshortener.repository.LinkRepository;
import ru.bmstu.dzhioev.urlshortener.utils.Util;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Служба создания и получения коротких ссылок.
 */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    private final LinkRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${app.link-ttl-days:7}")
    private long linkTtlDays;

    private static final String KEY_PREFIX = "link:";

    // счётчики micrometer
    private final Counter cacheHit = null;
    private final Counter cacheMiss = null;
    private final Counter cacheSet = null;
    private final Counter redisErrors = null;

    public LinkService(LinkRepository repository,
                       StringRedisTemplate redisTemplate,
                       ApplicationEventPublisher eventPublisher,
                       JdbcTemplate jdbcTemplate,
                       MeterRegistry meterRegistry) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;

        // регистрируем счётчики
        meterRegistry.counter("cache.hit");
        meterRegistry.counter("cache.miss");
        meterRegistry.counter("cache.set");
        meterRegistry.counter("redis.errors");
    }

    @Transactional
    public Link createLink(String originalUrl) {
        String normalized = normalizeUrl(originalUrl);

        try {
            Optional<Link> existing = repository.findByOriginalUrl(normalized);
            if (existing.isPresent()) {
                Link link = existing.get();
                if (link.getExpiresAt().isAfter(Instant.now())) {
                    try {
                        cacheLink(link);
                    } catch (RedisConnectionFailureException ex) {
                        meterRegistry.counter("redis.errors").increment();
                        log.warn("Временное хранилище недоступно при кэшировании существующей ссылки {}: {}", link.getShortCode(), ex.toString());
                    }
                    return link;
                }
            }
        } catch (Exception e) {
            log.warn("Ошибка поиска существующей записи для {}: {}", normalized, e.toString());
        }

        String shortCode = generateShortCode();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(linkTtlDays, ChronoUnit.DAYS);

        Link link = Link.builder()
                .originalUrl(normalized)
                .shortCode(shortCode)
                .createdAt(now)
                .expiresAt(expiresAt)
                .clickCount(0L)
                .build();

        try {
            Link saved = repository.save(link);
            try {
                cacheLink(saved);
            } catch (RedisConnectionFailureException ex) {
                meterRegistry.counter("redis.errors").increment();
                log.warn("Временное хранилище недоступно при кэшировании новой ссылки {}: {}", saved.getShortCode(), ex.toString());
            }
            return saved;
        } catch (DataIntegrityViolationException ex) {
            log.info("Нарушение целостности при сохранении URL {}: предполагаем конкурентную вставку, читаем существующую", normalized);
            Optional<Link> other = repository.findByOriginalUrl(normalized);
            return other.orElseThrow(() -> ex);
        }
    }

    public Optional<String> getOriginalUrl(String shortCode) {
        String key = KEY_PREFIX + shortCode;

        try {
            String cachedUrl = redisTemplate.opsForValue().get(key);
            if (cachedUrl != null) {
                meterRegistry.counter("cache.hit").increment();
                try {
                    redisTemplate.expire(key, linkTtlDays, TimeUnit.DAYS);
                } catch (Exception e) {
                    meterRegistry.counter("redis.errors").increment();
                    log.debug("Не удалось продлить время жизни ключа {} в ускорителе: {}", key, e.toString());
                }
                eventPublisher.publishEvent(new LinkAccessEvent(this, shortCode));
                return Optional.of(cachedUrl);
            } else {
                meterRegistry.counter("cache.miss").increment();
            }
        } catch (RedisConnectionFailureException ex) {
            meterRegistry.counter("redis.errors").increment();
            log.warn("Временное хранилище недоступно при чтении ключа {} — падение на БД. Причина: {}", key, ex.toString());
        } catch (Exception ex) {
            meterRegistry.counter("redis.errors").increment();
            log.warn("Неожиданная ошибка при обращении к ускорителю для ключа {}: {}", key, ex.toString());
        }

        Optional<Link> linkOpt = repository.findByShortCode(shortCode);
        if (linkOpt.isPresent()) {
            Link link = linkOpt.get();
            if (link.getExpiresAt().isAfter(Instant.now())) {
                try {
                    cacheLink(link);
                } catch (RedisConnectionFailureException ex) {
                    meterRegistry.counter("redis.errors").increment();
                    log.warn("Временное хранилище недоступно при кэшировании ключа {}: {}", key, ex.toString());
                }
                eventPublisher.publishEvent(new LinkAccessEvent(this, shortCode));
                return Optional.of(link.getOriginalUrl());
            } else {
                try {
                    redisTemplate.delete(key);
                } catch (Exception ignored) {}
            }
        }
        return Optional.empty();
    }

    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void cleanupExpiredLinks() {
        repository.deleteExpired(Instant.now());
    }

    private void cacheLink(Link link) {
        String key = KEY_PREFIX + link.getShortCode();
        try {
            redisTemplate.opsForValue().set(key, link.getOriginalUrl(), linkTtlDays, TimeUnit.DAYS);
            meterRegistry.counter("cache.set").increment();
        } catch (RedisConnectionFailureException ex) {
            meterRegistry.counter("redis.errors").increment();
            log.warn("Временное хранилище недоступно при записи ключа {}: {}", key, ex.toString());
            throw ex;
        } catch (Exception ex) {
            meterRegistry.counter("redis.errors").increment();
            log.warn("Неожиданная ошибка при кэшировании ключа {}: {}", key, ex.toString());
        }
    }

    private String generateShortCode() {
        Long seq = jdbcTemplate.queryForObject("SELECT nextval('short_code_seq')", Long.class);
        if (seq == null) {
            throw new IllegalStateException("Не удалось получить значение последовательности для short code");
        }
        return Util.encodeBase62(seq);
    }

    private String normalizeUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (!url.matches("(?i)^https?://.*")) {
            url = "http://" + url;
        }
        try {
            URI uri = URI.create(url).normalize();

            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "http";
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
            int port = uri.getPort();
            String path = uri.getPath() == null ? "" : uri.getPath();

            if (path == null || path.equals("/")) {
                path = "";
            } else {
                if (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
            }

            String portPart = "";
            if (port != -1 && !((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443))) {
                portPart = ":" + port;
            }

            String query = uri.getQuery();
            return scheme + "://" + host + portPart + (path.isEmpty() ? "" : path) + (query != null ? "?" + query : "");
        } catch (IllegalArgumentException ex) {
            log.warn("Не удалось нормализовать URL '{}': {}", rawUrl, ex.toString());
            return url;
        }
    }
}