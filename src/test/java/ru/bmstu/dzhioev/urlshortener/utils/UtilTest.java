package ru.bmstu.dzhioev.urlshortener.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UtilTest {

    @Test
    @DisplayName("Генерация кода: длина должна быть ровно 7 символов")
    void generateShortCode_ShouldHaveCorrectLength() {
        String code = Util.generateShortCode();
        assertThat(code).hasSize(7);
    }

    @Test
    @DisplayName("Генерация кода: должен содержать только символы Base62")
    void generateShortCode_ShouldContainOnlyBase62Chars() {
        String code = Util.generateShortCode();
        assertThat(code).matches("^[0-9A-Za-z]+$");
    }

    @Test
    @DisplayName("Генерация кода: уникальность (нет коллизий на малой выборке)")
    void generateShortCode_ShouldGenerateUniqueCodes() {
        int iterations = 10000;
        Set<String> generatedCodes = new HashSet<>();

        for (int i = 0; i < iterations; i++) {
            generatedCodes.add(Util.generateShortCode());
        }

        // Если размер сета равен числу итераций, коллизий не было
        assertThat(generatedCodes).hasSize(iterations);
    }
}

