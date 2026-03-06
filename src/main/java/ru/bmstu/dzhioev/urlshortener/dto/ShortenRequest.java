package ru.bmstu.dzhioev.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;

public record ShortenRequest(
        @NotBlank(message = "URL не может быть пустым")
        @URL(message = "Неверный формат URL")
        String url) {
}