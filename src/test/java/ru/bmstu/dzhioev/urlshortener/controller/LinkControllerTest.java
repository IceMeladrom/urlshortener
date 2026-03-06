package ru.bmstu.dzhioev.urlshortener.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.bmstu.dzhioev.urlshortener.entity.Link;
import ru.bmstu.dzhioev.urlshortener.service.LinkService;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LinkController.class)
class LinkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LinkService linkService;

    @Test
    @DisplayName("POST /shorten - Успешное создание ссылки (201 Created)")
    void shorten_ValidUrl_Returns201AndCode() throws Exception {
        Link mockLink = new Link();
        mockLink.setShortCode("abc123X");

        when(linkService.createLink(anyString())).thenReturn(mockLink);

        String requestBody = """
                {
                    "url": "https://habr.com/ru"
                }
                """;

        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/abc123X"))
                .andExpect(jsonPath("$.shortCode").value("abc123X"));
    }

    @Test
    @DisplayName("POST /shorten - Невалидный URL (400 Bad Request)")
    void shorten_InvalidUrl_Returns400() throws Exception {
        String requestBody = """
                {
                    "url": "not-a-valid-url"
                }
                """;

        // Ожидаем срабатывания GlobalExceptionHandler
        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.detail").value("url: Неверный формат URL"));
    }

    @Test
    @DisplayName("GET /{shortCode} - Успешный редирект (302 Found)")
    void redirect_ValidCode_Returns302() throws Exception {
        when(linkService.getOriginalUrl("abc123X"))
                .thenReturn(Optional.of("https://habr.com/ru"));

        mockMvc.perform(get("/api/v1/abc123X"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://habr.com/ru"));
    }

    @Test
    @DisplayName("GET /{shortCode} - Ссылка не найдена (404 Not Found)")
    void redirect_InvalidCode_Returns404() throws Exception {
        when(linkService.getOriginalUrl("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/unknown"))
                .andExpect(status().isNotFound());
    }
}

