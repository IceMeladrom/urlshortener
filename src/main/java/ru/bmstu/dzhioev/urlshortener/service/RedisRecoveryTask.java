package ru.bmstu.dzhioev.urlshortener.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(RedisRecoveryTask.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisHealthTracker redisHealthTracker;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${app.redis-probe-interval-ms:5000}")
    public void probe() {
        if (redisHealthTracker.isAvailable()) {
            return;
        }

        try {
            String answer = redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
            if ("PONG".equalsIgnoreCase(answer)) {
                redisHealthTracker.markAvailable();
                meterRegistry.counter("redis.recovered").increment();
                log.info("Redis снова доступен");
            }
        } catch (Exception e) {
            meterRegistry.counter("redis.probe.errors").increment();
        }
    }
}
