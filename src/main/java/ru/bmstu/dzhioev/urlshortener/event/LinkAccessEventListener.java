package ru.bmstu.dzhioev.urlshortener.event;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;
import ru.bmstu.dzhioev.urlshortener.repository.LinkRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * Асинхронный обработчик события доступа к короткой ссылке.
 * <p>
 * Логика:
 * 1) Всегда инкрементируем счётчик переходов.
 * 2) Пытаемся продлить expiresAt в БД — но не чаще, чем раз в extendThresholdMinutes для одной ссылки.
 * Для ограничения частоты используем ключ-блокировку в временном хранилище-ускорителе.
 */
@Component
public class LinkAccessEventListener {

    private static final Logger log = LoggerFactory.getLogger(LinkAccessEventListener.class);

    private final LinkRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

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

        try {
            repository.incrementClickCount(code);
            meterRegistry.counter("click.increment").increment();
        } catch (Exception e) {
            meterRegistry.counter("expiry.extend.error").increment();
            log.warn("Не удалось увеличить счётчик кликов для {}: {}", code, e.toString());
        }

        String lockKey = EXTEND_LOCK_PREFIX + code;
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", extendThresholdMinutes, TimeUnit.MINUTES);
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
        } catch (Exception e) {
            meterRegistry.counter("expiry.extend.error").increment();
            log.debug("Не удалось получить блокировку продления в ускорителе для {}: {}", code, e.toString());
        }
    }
}