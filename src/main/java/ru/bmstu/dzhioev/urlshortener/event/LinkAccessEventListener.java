package ru.bmstu.dzhioev.urlshortener.event;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import ru.bmstu.dzhioev.urlshortener.repository.LinkRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Асинхронный обработчик события доступа к короткой ссылке.
 * <p>
 * Увеличивает счётчик переходов и, если с момента последнего продления прошло больше
 * заданного порога, продлевает срок действия ссылки. Для ограничения частоты используется
 * блокировка в Redis. Если Redis недоступен, продление не выполняется, но счётчик
 * всё равно увеличивается.
 */
@Component
public class LinkAccessEventListener {

    private static final Logger log = LoggerFactory.getLogger(LinkAccessEventListener.class);

    private final LinkRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean redisAvailable = new AtomicBoolean(true); // флаг доступности Redis

    @Value("${app.link-ttl-days:7}")
    private long linkTtlDays;

    @Value("${app.extend-threshold-minutes:60}")
    private long extendThresholdMinutes;

    private static final String EXTEND_LOCK_PREFIX = "link:extend:";

    public LinkAccessEventListener(LinkRepository repository,
                                   StringRedisTemplate redisTemplate,
                                   MeterRegistry meterRegistry) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;

        meterRegistry.counter("expiry.extend.attempt");
        meterRegistry.counter("expiry.extend.success");
        meterRegistry.counter("expiry.extend.error");
        meterRegistry.counter("click.increment");
        meterRegistry.counter("expiry.extend.skipped");
    }

    @Async("taskExecutor")
    @EventListener
    @Transactional
    public void onLinkAccess(LinkAccessEvent event) {
        String code = event.getShortCode();

        // 1. Увеличиваем счётчик переходов
        try {
            repository.incrementClickCount(code);
            meterRegistry.counter("click.increment").increment();
        } catch (Exception e) {
            meterRegistry.counter("expiry.extend.error").increment();
            log.warn("Не удалось увеличить счётчик кликов для {}: {}", code, e.toString());
        }

        // 2. Пытаемся продлить срок, но только если Redis доступен
        if (redisAvailable.get()) {
            try {
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                        EXTEND_LOCK_PREFIX + code,
                        "1",
                        extendThresholdMinutes,
                        TimeUnit.MINUTES
                );
                if (Boolean.TRUE.equals(acquired)) {
                    meterRegistry.counter("expiry.extend.attempt").increment();
                    Instant newExpiry = Instant.now().plus(linkTtlDays, ChronoUnit.DAYS);
                    try {
                        int updated = repository.extendExpiryIfLess(code, newExpiry);
                        if (updated > 0) {
                            meterRegistry.counter("expiry.extend.success").increment();
                            log.debug("Продлил время действия для {} до {}", code, newExpiry);
                        } else {
                            meterRegistry.counter("expiry.extend.skipped").increment();
                            log.debug("Продление не потребовалось для {}", code);
                        }
                    } catch (Exception e) {
                        meterRegistry.counter("expiry.extend.error").increment();
                        log.warn("Не удалось продлить время действия в БД для {}: {}", code, e.toString());
                    }
                } else {
                    meterRegistry.counter("expiry.extend.skipped").increment();
                    log.debug("Пропускаем продление для {} — недавно уже продлевали", code);
                }
                // Если Redis был недоступен, но сейчас заработал, сбрасываем флаг
                if (!redisAvailable.get()) {
                    redisAvailable.set(true);
                }
            } catch (RedisConnectionFailureException e) {
                meterRegistry.counter("expiry.extend.error").increment();
                redisAvailable.set(false);
                log.warn("Redis недоступен при попытке установить блокировку для {}: {}. Продление отложено.", code, e.toString());
            } catch (Exception e) {
                meterRegistry.counter("expiry.extend.error").increment();
                log.warn("Неожиданная ошибка при работе с Redis для {}: {}", code, e.toString());
            }
        } else {
            // Redis не доступен — просто логируем и не пытаемся продлить
            log.debug("Redis недоступен, пропускаем продление для {}", code);
            meterRegistry.counter("expiry.extend.skipped").increment();
        }
    }
}