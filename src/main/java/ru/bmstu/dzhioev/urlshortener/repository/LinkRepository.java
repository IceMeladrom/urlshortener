package ru.bmstu.dzhioev.urlshortener.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.bmstu.dzhioev.urlshortener.entity.Link;

import java.time.Instant;
import java.util.Optional;

/**
 * Репозиторий для работы с таблицей links.
 * Методы с модификацией помечены @Modifying и должны вызываться в транзакции.
 */
@Repository
public interface LinkRepository extends JpaRepository<Link, Long> {

    Optional<Link> findByShortCode(String shortCode);

    Optional<Link> findByOriginalUrl(String originalUrl);

    /**
     * Увеличить счетчик кликов на 1 для shortCode.
     * Используется в асинхронном обработчике доступа.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Link l SET l.clickCount = l.clickCount + 1 WHERE l.shortCode = :shortCode")
    void incrementClickCount(String shortCode);

    /**
     * Удалить просроченные ссылки пачками.
     * @param now текущее время
     * @param limit максимальное количество удаляемых записей за один вызов
     * @return количество удалённых записей
     */
    @Modifying
    @Query(value = "DELETE FROM links WHERE id IN (SELECT id FROM links WHERE expires_at < :now LIMIT :limit)", nativeQuery = true)
    int deleteExpiredBatch(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Условное продление времени действия: обновит expiresAt только если текущее меньше newExpiresAt.
     * Возвращает количество обновлённых записей (0 или 1).
     */
    @Modifying
    @Query("UPDATE Link l SET l.expiresAt = :newExpiresAt WHERE l.shortCode = :shortCode AND l.expiresAt < :newExpiresAt")
    int extendExpiryIfLess(String shortCode, Instant newExpiresAt);
}