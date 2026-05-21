package ru.bmstu.dzhioev.urlshortener.dto;

import java.time.Instant;

public record CachedLink(String url, Instant expiresAt) {
}
