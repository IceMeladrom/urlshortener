package ru.bmstu.dzhioev.urlshortener.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import ru.bmstu.dzhioev.urlshortener.dto.CachedLink;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class LinkAccessBuffer {

    private static final Logger log = LoggerFactory.getLogger(LinkAccessBuffer.class);
    private static final String LINK_PREFIX = "link:";
    private static final String CLICK_BUFFER_KEY = "links:clicks:buffer";
    private static final String EXPIRY_BUFFER_KEY = "links:expiry:buffer";

    private final StringRedisTemplate redisTemplate;
    private final RedisHealthTracker redisHealthTracker;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    private final AtomicLong clickBufferSize = new AtomicLong();
    private final AtomicLong expiryBufferSize = new AtomicLong();

    @Value("${app.link-ttl-days:7}")
    private long linkTtlDays;

    @PostConstruct
    void registerMetrics() {
        meterRegistry.gauge("link.click.buffer.size", clickBufferSize);
        meterRegistry.gauge("link.expiry.buffer.size", expiryBufferSize);
    }

    @Scheduled(fixedDelayString = "${app.buffer-metrics-interval-ms:5000}")
    public void refreshBufferSizes() {
        if (!redisHealthTracker.isAvailable()) {
            clickBufferSize.set(0);
            expiryBufferSize.set(0);
            return;
        }

        try {
            Long clicks = redisTemplate.opsForHash().size(CLICK_BUFFER_KEY);
            Long expiries = redisTemplate.opsForHash().size(EXPIRY_BUFFER_KEY);
            clickBufferSize.set(clicks == null ? 0 : clicks);
            expiryBufferSize.set(expiries == null ? 0 : expiries);
        } catch (Exception e) {
            meterRegistry.counter("redis.errors").increment();
            redisHealthTracker.markUnavailable();
            log.debug("Не удалось обновить размер накопителей: {}", e.getMessage());
        }
    }

    public void recordAccess(String shortCode, String originalUrl) {
        if (!redisHealthTracker.isAvailable()) {
            return;
        }

        Instant expiresAt = Instant.now().plus(linkTtlDays, ChronoUnit.DAYS);
        try {
            CachedLink cachedLink = new CachedLink(originalUrl, expiresAt);
            redisTemplate.opsForValue().set(
                    LINK_PREFIX + shortCode,
                    objectMapper.writeValueAsString(cachedLink),
                    linkTtlDays,
                    TimeUnit.DAYS
            );
            redisTemplate.opsForHash().increment(CLICK_BUFFER_KEY, shortCode, 1L);
            redisTemplate.opsForHash().put(EXPIRY_BUFFER_KEY, shortCode, expiresAt.toString());
            meterRegistry.counter("link.access.buffered").increment();
            meterRegistry.counter("link.expiry.buffered").increment();
        } catch (Exception e) {
            meterRegistry.counter("redis.errors").increment();
            redisHealthTracker.markUnavailable();
            log.warn("Redis недоступен при накоплении перехода: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.access-flush-interval-ms:30000}")
    public void flush() {
        if (!redisHealthTracker.isAvailable()) {
            return;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            flushClicks();
            flushExpiry();
        } catch (Exception e) {
            meterRegistry.counter("link.buffer.flush.errors").increment();
            log.warn("Ошибка записи накопленных данных: {}", e.getMessage());
        } finally {
            sample.stop(meterRegistry.timer("link.buffer.flush.duration"));
        }
    }

    private void flushClicks() {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(CLICK_BUFFER_KEY);
        clickBufferSize.set(entries.size());
        if (entries.isEmpty()) {
            return;
        }

        List<ClickDelta> deltas = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String code = String.valueOf(entry.getKey());
            long count = Long.parseLong(String.valueOf(entry.getValue()));
            if (count > 0) {
                deltas.add(new ClickDelta(code, count));
            }
        }

        if (deltas.isEmpty()) {
            redisTemplate.delete(CLICK_BUFFER_KEY);
            return;
        }

        jdbcTemplate.batchUpdate(
                "UPDATE links SET click_count = click_count + ? WHERE short_code = ?",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ClickDelta delta = deltas.get(i);
                        ps.setLong(1, delta.count());
                        ps.setString(2, delta.shortCode());
                    }

                    @Override
                    public int getBatchSize() {
                        return deltas.size();
                    }
                }
        );

        redisTemplate.opsForHash().delete(
                CLICK_BUFFER_KEY,
                deltas.stream().map(ClickDelta::shortCode).toArray()
        );
        clickBufferSize.set(0);
        meterRegistry.counter("link.click.flushed").increment(
                deltas.stream().mapToLong(ClickDelta::count).sum()
        );
    }

    private void flushExpiry() {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(EXPIRY_BUFFER_KEY);
        expiryBufferSize.set(entries.size());
        if (entries.isEmpty()) {
            return;
        }

        List<ExpiryUpdate> updates = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            updates.add(new ExpiryUpdate(
                    String.valueOf(entry.getKey()),
                    Instant.parse(String.valueOf(entry.getValue()))
            ));
        }

        jdbcTemplate.batchUpdate(
                "UPDATE links SET expires_at = ? WHERE short_code = ? AND expires_at < ?",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ExpiryUpdate update = updates.get(i);
                        Timestamp expiresAt = Timestamp.from(update.expiresAt());
                        ps.setTimestamp(1, expiresAt);
                        ps.setString(2, update.shortCode());
                        ps.setTimestamp(3, expiresAt);
                    }

                    @Override
                    public int getBatchSize() {
                        return updates.size();
                    }
                }
        );

        redisTemplate.opsForHash().delete(
                EXPIRY_BUFFER_KEY,
                updates.stream().map(ExpiryUpdate::shortCode).toArray()
        );
        expiryBufferSize.set(0);
        meterRegistry.counter("link.expiry.flushed").increment(updates.size());
    }

    private record ClickDelta(String shortCode, long count) {
    }

    private record ExpiryUpdate(String shortCode, Instant expiresAt) {
    }
}
