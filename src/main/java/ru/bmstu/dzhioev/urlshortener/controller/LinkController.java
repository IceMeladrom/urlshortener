package ru.bmstu.dzhioev.urlshortener.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.bmstu.dzhioev.urlshortener.dto.ShortenRequest;
import ru.bmstu.dzhioev.urlshortener.entity.Link;
import ru.bmstu.dzhioev.urlshortener.service.LinkService;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;

/**
 * Контроллер для создания коротких ссылок и редиректа по shortCode.
 * <p>
 * Команды:
 * POST /api/v1/shorten  — создать короткую ссылку
 * GET  /{shortCode}     — перейти по короткой ссылке (редирект)
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
public class LinkController {

    private final LinkService linkService;

    /**
     * Создаёт короткую ссылку.
     * В теле ожидается JSON с полем "url".
     * Возвращает shortCode.
     */
    @PostMapping("/shorten")
    public ResponseEntity<Map<String, String>> shorten(@Valid @RequestBody ShortenRequest payload) {
        String url = payload.url().trim();

        // Добавляем схему по умолчанию, если не указана
        if (!url.matches("(?i)^https?://.*")) {
            url = "http://" + url;
        }

        // Простая проверка схемы (разрешаем только http и https)
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return ResponseEntity.badRequest().body(Map.of("error", "Разрешены только схемы http и https"));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Некорректный URL"));
        }

        Link link = linkService.createLink(url);
        return ResponseEntity.ok(Map.of("shortCode", link.getShortCode()));
    }

    /**
     * Редирект по короткому коду.
     * Если shortCode не найден или просрочен — возвращаем 404.
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<?> redirect(@PathVariable String shortCode) {
        var urlOpt = linkService.getOriginalUrl(shortCode);

        if (urlOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String url = urlOpt.get();
        try {
            return ResponseEntity.status(302).location(URI.create(url)).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "В хранилище сохранён некорректный URL"));
        }
    }
}