package ru.bmstu.dzhioev.urlshortener.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Централизованный трекер доступности Redis.
 * <p>
 * Состояние обновляется компонентами приложения через markAvailable() /
 * markUnavailable() в момент реальных операций с Redis — никакого
 * активного опроса, никаких утечек соединений.
 * <p>
 * Gauge redis.available:
 * 1.0 — Redis отвечает на запросы
 * 0.0 — последняя операция завершилась RedisConnectionFailureException
 */
@Component
public class RedisHealthTracker {

    private final AtomicBoolean available = new AtomicBoolean(true);

    public RedisHealthTracker(MeterRegistry meterRegistry) {
        // Gauge читает только флаг — соединение не открывается
        meterRegistry.gauge("redis.available", this,
                tracker -> tracker.isAvailable() ? 1.0 : 0.0);
    }

    public boolean isAvailable() {
        return available.get();
    }

    /**
     * Вызывается когда операция с Redis завершилась успешно
     * после периода недоступности.
     */
    public void markAvailable() {
        available.set(true);
    }

    /**
     * Вызывается при получении RedisConnectionFailureException.
     */
    public void markUnavailable() {
        available.set(false);
    }
}
