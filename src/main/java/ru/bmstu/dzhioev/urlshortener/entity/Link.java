package ru.bmstu.dzhioev.urlshortener.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Сущность ссылки.
 * <p>
 * Поле shortCode содержит короткий код (base62).
 * originalUrl — исходный URL.
 * createdAt и expiresAt — метки времени создания и истечения срока действия.
 * clickCount — количество переходов по ссылке.
 */
@Entity
@Table(name = "links")
@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Короткий код ссылки.
     * Длина колонки задаётся в миграции (предложение: VARCHAR(16)).
     */
    @Column(name = "short_code", nullable = false, unique = true, length = 16)
    private String shortCode;

    /**
     * Оригинальный URL (нормализованный).
     */
    @Column(name = "original_url", nullable = false, columnDefinition = "TEXT")
    private String originalUrl;

    /**
     * Когда запись создана.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Когда запись должна считаться просроченной.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Счетчик переходов.
     */
    @Column(name = "click_count", nullable = false)
    private Long clickCount = 0L;
}