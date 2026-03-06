package ru.bmstu.dzhioev.urlshortener.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.bmstu.dzhioev.urlshortener.dto.ShortenRequest;
import ru.bmstu.dzhioev.urlshortener.dto.ShortenResponse;
import ru.bmstu.dzhioev.urlshortener.entity.Link;
import ru.bmstu.dzhioev.urlshortener.service.LinkService;

import java.net.URI;
import java.util.Optional;

/**
 * Контроллер намеренно не содержит бизнес-логики:
 * только приём запроса, делегирование сервису, формирование ответа.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class LinkController {

    private final LinkService linkService;

    /**
     * POST /api/v1/shorten
     * Тело: {"url": "https://example.com/..."}
     * Ответ: 201 Created, Location: /api/v1/{shortCode}, {"shortCode": "..."}
     */
    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest request) {
        log.debug("Received request for url: {}", request.url());
        Link link = linkService.createLink(request.url());
        URI location = URI.create("/api/v1/" + link.getShortCode());
        return ResponseEntity
                .created(location)
                .body(new ShortenResponse(link.getShortCode()));
    }

    /**
     * GET /api/v1/{shortCode}
     * Ответ: 302 с заголовком Location на оригинальный URL
     * 404 если ссылка не найдена или просрочена
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        log.debug("Received redirect for shortCode: {}", shortCode);
        Optional<String> urlOpt = linkService.getOriginalUrl(shortCode);
        if (urlOpt.isEmpty())
            return ResponseEntity.notFound().build();

        return ResponseEntity
                .status(302)
                .location(URI.create(urlOpt.get()))
                .build();
    }
}
