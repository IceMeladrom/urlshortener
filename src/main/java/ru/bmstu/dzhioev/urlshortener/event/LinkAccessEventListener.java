package ru.bmstu.dzhioev.urlshortener.event;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import ru.bmstu.dzhioev.urlshortener.repository.LinkRepository;
import ru.bmstu.dzhioev.urlshortener.service.RedisHealthTracker;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class LinkAccessEventListener {

    private static final Logger log = LoggerFactory.getLogger(LinkAccessEventListener.class);
    private static final String EXTEND_LOCK_PREFIX = "link:extend:";

    private final LinkRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final RedisHealthTracker redisHealthTracker;

    @Value("${app.link-ttl-days:7}")
    private long linkTtlDays;

    @Value("${app.extend-threshold-minutes:60}")
    private long extendThresholdMinutes;

    @Async
    @EventListener
    public void onLinkAccess(LinkAccessEvent event) {
        String code = event.shortCode();
        incrementClickCount(code);
        tryExtendExpiry(code);
    }

    private void incrementClickCount(String code) {
        try {
            repository.incrementClickCount(code);
            meterRegistry.counter("link.redirects").increment();
        } catch (Exception e) {
            log.warn("Не удалось увеличить счётчик кликов для {}: {}", code, e.getMessage());
        }
    }

    private void tryExtendExpiry(String code) {
        if (!redisHealthTracker.isAvailable()) {
            meterRegistry.counter("expiry.extend.skipped").increment();
            return;
        }

        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    EXTEND_LOCK_PREFIX + code,
                    "1",
                    extendThresholdMinutes,
                    TimeUnit.MINUTES
            );

            if (!Boolean.TRUE.equals(acquired)) {
                meterRegistry.counter("expiry.extend.skipped").increment();
                return;
            }

            meterRegistry.counter("expiry.extend.attempt").increment();
            Instant newExpiry = Instant.now().plus(linkTtlDays, ChronoUnit.DAYS);

            // Транзакция для БД откроется только здесь, не блокируя Redis
            int updated = repository.extendExpiryIfLess(code, newExpiry);

            if (updated > 0) {
                meterRegistry.counter("expiry.extend.success").increment();
            } else {
                meterRegistry.counter("expiry.extend.skipped").increment();
            }

            redisHealthTracker.markAvailable();
        } catch (RedisConnectionFailureException e) {
            redisHealthTracker.markUnavailable();
            meterRegistry.counter("expiry.extend.error").increment();
            log.warn("Redis недоступен при продлении {}: {}", code, e.getMessage());
        } catch (Exception e) {
            meterRegistry.counter("expiry.extend.error").increment();
            log.warn("Ошибка при продлении срока для {}: {}", code, e.getMessage());
        }
    }
}
