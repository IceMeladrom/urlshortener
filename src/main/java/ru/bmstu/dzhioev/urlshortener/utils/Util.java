package ru.bmstu.dzhioev.urlshortener.utils;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Утилитарный класс для генерации коротких кодов.
 * Использует криптостойкий генератор случайных чисел и Base62.
 */
public final class Util {

    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int CODE_LENGTH = 7;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Util() {
        // запрещаем создание экземпляров
    }

    /**
     * Генерирует случайный короткий код длиной 7 символов из алфавита Base62.
     * Использует SecureRandom, что обеспечивает потокобезопасность.
     *
     * @return строка длиной 7 символов
     */
    public static String generateShortCode() {
        // Генерируем 48 бит случайности (6 байт) — этого достаточно для 62^7 комбинаций
        byte[] bytes = new byte[6];
        SECURE_RANDOM.nextBytes(bytes);

        BigInteger bi = new BigInteger(1, bytes);
        return toBase62(bi);
    }

    /**
     * Преобразует BigInteger в строку Base62 фиксированной длины.
     * Если длина получается меньше CODE_LENGTH, дополняет слева нулями ('0').
     */
    private static String toBase62(BigInteger value) {
        BigInteger base = BigInteger.valueOf(62);
        StringBuilder sb = new StringBuilder();

        while (value.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divmod = value.divideAndRemainder(base);
            sb.append(BASE62[divmod[1].intValue()]);
            value = divmod[0];
        }

        // Переворачиваем строку, т.к. собирали с младших разрядов
        String reversed = sb.reverse().toString();

        // Если длина превышает CODE_LENGTH, обрезаем (маловероятно при 6 байтах)
        if (reversed.length() > CODE_LENGTH) {
            return reversed.substring(0, CODE_LENGTH);
        }

        // Дополняем слева нулями до нужной длины
        StringBuilder result = new StringBuilder();
        for (int i = reversed.length(); i < CODE_LENGTH; i++) {
            result.append('0');
        }
        result.append(reversed);
        return result.toString();
    }
}