package ru.bmstu.dzhioev.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;

public record ShortenRequest(@NotBlank String url) {
}