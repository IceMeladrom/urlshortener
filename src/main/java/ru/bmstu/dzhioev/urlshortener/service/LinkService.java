package ru.bmstu.dzhioev.urlshortener.service;

import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Objects;
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
    private final MeterRegistry meterRegistry;

    @Value("${app.link-ttl-days:7}")
    private long linkTtlDays;

    private static final String KEY_PREFIX = "link:";
    private static final int MAX_CREATE_ATTEMPTS = 5; // количество попыток при коллизии

    public LinkService(LinkRepository repository,
                       StringRedisTemplate redisTemplate,
                       ApplicationEventPublisher eventPublisher,
                       JdbcTemplate jdbcTemplate,
                       MeterRegistry meterRegistry) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;

        meterRegistry.counter("cache.hit");
        meterRegistry.counter("cache.miss");
        meterRegistry.counter("cache.set");
        meterRegistry.counter("redis.errors");

        // Датчик доступности Redis (1.0 - доступен, 0.0 - нет)
        meterRegistry.gauge("redis.available", redisTemplate,
                t -> {
                    try {
                        return Objects.requireNonNull(t.getConnectionFactory()).getConnection().ping() != null ? 1.0 : 0.0;
                    } catch (Exception e) {
                        return 0.0;
                    }
                });
    }

    /**
     * Создание короткой ссылки.
     * Сначала проверяем, существует ли уже действующая ссылка для данного URL.
     * Если нет — пытаемся сохранить новую с уникальным shortCode (до 5 попыток).
     * При коллизии по short_code или original_url повторяем с новым кодом.
     */
    @Transactional
    public Link createLink(String originalUrl) {
        String normalized = normalizeUrl(originalUrl);

        // 1. Ищем существующую действующую ссылку
        try {
            Optional<Link> existing = repository.findByOriginalUrl(normalized);
            if (existing.isPresent() && existing.get().getExpiresAt().isAfter(Instant.now())) {
                // Пытаемся закэшировать, но игнорируем ошибки Redis
                safeCacheLink(existing.get());
                return existing.get();
            }
        } catch (Exception e) {
            log.warn("Ошибка при поиске существующей записи для {}: {}", normalized, e.toString());
        }

        // 2. Пытаемся сохранить новую ссылку с повторными попытками при коллизиях
        for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
            String shortCode = Util.generateShortCode();
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
                safeCacheLink(saved); // пробуем закэшировать, но не падаем при ошибке
                return saved;
            } catch (DataIntegrityViolationException ex) {
                // Проверяем, может быть, запись с таким original_url уже создана другим потоком
                Optional<Link> maybeExisting = repository.findByOriginalUrl(normalized);
                if (maybeExisting.isPresent()) {
                    // Действительно, дубликат по URL — возвращаем существующую
                    Link existing = maybeExisting.get();
                    safeCacheLink(existing);
                    return existing;
                }
                // Иначе это, скорее всего, коллизия short_code — пробуем ещё раз
                log.info("Коллизия shortCode при создании ссылки для {}, попытка {}", normalized, attempt);
                if (attempt == MAX_CREATE_ATTEMPTS) {
                    throw new IllegalStateException("Не удалось создать ссылку после " + MAX_CREATE_ATTEMPTS + " попыток", ex);
                }
                // Небольшая пауза перед следующей попыткой (для снижения конкуренции)
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException ignored) {
                }
            } catch (Exception e) {
                log.error("Неожиданная ошибка при сохранении ссылки {}: {}", normalized, e.toString());
                throw new RuntimeException("Ошибка создания ссылки", e);
            }
        }
        throw new IllegalStateException("Не удалось создать ссылку из-за частых коллизий shortCode");
    }

    /**
     * Получение оригинального URL по короткому коду.
     * Сначала ищем в Redis, при промахе — в БД.
     * Если ссылка найдена и не просрочена, публикуется событие доступа.
     */
    public Optional<String> getOriginalUrl(String shortCode) {
        String key = KEY_PREFIX + shortCode;

        // Попытка чтения из Redis
        try {
            String cachedUrl = redisTemplate.opsForValue().get(key);
            if (cachedUrl != null) {
                meterRegistry.counter("cache.hit").increment();
                // Продлеваем TLL в Redis, но игнорируем ошибки
                try {
                    redisTemplate.expire(key, linkTtlDays, TimeUnit.DAYS);
                } catch (Exception e) {
                    log.debug("Не удалось продлить время жизни ключа {} в Redis: {}", key, e.toString());
                }
                eventPublisher.publishEvent(new LinkAccessEvent(this, shortCode));
                return Optional.of(cachedUrl);
            } else {
                meterRegistry.counter("cache.miss").increment();
            }
        } catch (RedisConnectionFailureException ex) {
            meterRegistry.counter("redis.errors").increment();
            log.warn("Redis недоступен при чтении ключа {} — падаем на БД. Причина: {}", key, ex.toString());
        } catch (Exception ex) {
            meterRegistry.counter("redis.errors").increment();
            log.warn("Неожиданная ошибка при обращении к Redis для ключа {}: {}", key, ex.toString());
        }

        // Если в Redis нет (или ошибка), идём в БД
        try {
            Optional<Link> linkOpt = repository.findByShortCode(shortCode);
            if (linkOpt.isPresent()) {
                Link link = linkOpt.get();
                if (link.getExpiresAt().isAfter(Instant.now())) {
                    // Кэшируем (игнорируем ошибки)
                    safeCacheLink(link);
                    eventPublisher.publishEvent(new LinkAccessEvent(this, shortCode));
                    return Optional.of(link.getOriginalUrl());
                } else {
                    // Ссылка просрочена, удаляем из Redis, если он доступен
                    try {
                        redisTemplate.delete(key);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Ошибка при обращении к БД для shortCode {}: {}", shortCode, e.toString());
        }
        return Optional.empty();
    }

    /**
     * Периодическая очистка просроченных ссылок.
     * Удаляет пачками по 1000 записей за раз, чтобы не блокировать таблицу надолго.
     */
    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void cleanupExpiredLinks() {
        int deleted;
        int totalDeleted = 0;
        do {
            deleted = repository.deleteExpiredBatch(Instant.now(), 1000);
            totalDeleted += deleted;
        } while (deleted > 0);
        log.info("Очистка просроченных ссылок: удалено {} записей", totalDeleted);
    }

    /**
     * Сохраняет ссылку в Redis, игнорируя ошибки соединения.
     * Увеличивает счётчик ошибок при необходимости.
     */
    private void safeCacheLink(Link link) {
        String key = KEY_PREFIX + link.getShortCode();
        try {
            redisTemplate.opsForValue().set(key, link.getOriginalUrl(), linkTtlDays, TimeUnit.DAYS);
            meterRegistry.counter("cache.set").increment();
        } catch (RedisConnectionFailureException ex) {
            meterRegistry.counter("redis.errors").increment();
            log.warn("Redis недоступен при записи ключа {}: {}", key, ex.toString());
        } catch (Exception ex) {
            meterRegistry.counter("redis.errors").increment();
            log.warn("Неожиданная ошибка при кэшировании ключа {}: {}", key, ex.toString());
        }
    }

    /**
     * Проверяет доступность Redis (используется для метрики).
     */
    private boolean isRedisAvailable(StringRedisTemplate template) {
        try {
            return template.getConnectionFactory().getConnection().ping() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Нормализует URL: приводит к нижнему регистру, добавляет схему по умолчанию,
     * удаляет конечный слеш, нормализует путь.
     */
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
            String query = uri.getQuery();

            // Убираем конечный слеш, если путь не пустой и не равен "/"
            if (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }

            String portPart = "";
            if (port != -1 && !((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443))) {
                portPart = ":" + port;
            }

            return scheme + "://" + host + portPart +
                    (path.isEmpty() ? "" : path) +
                    (query != null ? "?" + query : "");
        } catch (IllegalArgumentException ex) {
            log.warn("Не удалось нормализовать URL '{}': {}", rawUrl, ex.toString());
            return url;
        }
    }
}