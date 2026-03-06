package ru.bmstu.dzhioev.urlshortener.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.bmstu.dzhioev.urlshortener.entity.Link;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface LinkRepository extends JpaRepository<Link, Long> {

    Optional<Link> findByShortCode(String shortCode);

    Optional<Link> findByOriginalUrl(String originalUrl);

    /**
     * Атомарно увеличивает счётчик кликов.
     * clearAutomatically = true — сбрасывает first-level cache после UPDATE,
     * чтобы следующее чтение этой сущности получило актуальные данные из БД.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Link l SET l.clickCount = l.clickCount + 1 WHERE l.shortCode = :shortCode")
    void incrementClickCount(@Param("shortCode") String shortCode);

    /**
     * Удаляет просроченные ссылки пачками через нативный SQL.
     * flushAutomatically = true — сбрасывает pending-изменения перед DELETE.
     * clearAutomatically = true — очищает кеш после DELETE.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "DELETE FROM links WHERE id IN (SELECT id FROM links WHERE expires_at < :now LIMIT :limit)",
            nativeQuery = true
    )
    int deleteExpiredBatch(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Продлевает expiresAt только если текущее значение меньше нового.
     * Возвращает 0 (не нужно продлевать) или 1 (продлено).
     * clearAutomatically = true — сбрасывает кеш после UPDATE.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Link l
            SET l.expiresAt = :newExpiresAt
            WHERE l.shortCode = :shortCode
              AND l.expiresAt < :newExpiresAt
            """)
    int extendExpiryIfLess(@Param("shortCode") String shortCode,
                           @Param("newExpiresAt") Instant newExpiresAt);
}
