package ru.bmstu.dzhioev.urlshortener.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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
     * Удалить просроченные ссылки.
     */
    @Modifying
    @Query("DELETE FROM Link l WHERE l.expiresAt < :now")
    void deleteExpired(Instant now);

    /**
     * Условное продление времени действия: обновит expiresAt только если текущее меньше newExpiresAt.
     * Возвращает количество обновлённых записей (0 или 1).
     */
    @Modifying
    @Query("UPDATE Link l SET l.expiresAt = :newExpiresAt WHERE l.shortCode = :shortCode AND l.expiresAt < :newExpiresAt")
    int extendExpiryIfLess(String shortCode, Instant newExpiresAt);
}