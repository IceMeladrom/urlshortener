package ru.bmstu.dzhioev.urlshortener.utils;

/**
 * Утилита для кодирования в base62 с минимальной длиной.
 * Метод прост и детерминирован: 0 -> "aaaaa0" (в зависимости от MIN_CODE_LENGTH).
 */
public class Util {

    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int MIN_CODE_LENGTH = 6;

    public static String encodeBase62(long value) {
        if (value == 0) {
            return pad("0");
        }
        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v > 0) {
            int digit = (int) (v % 62);
            sb.append(BASE62[digit]);
            v = v / 62;
        }
        return pad(sb.reverse().toString());
    }

    /**
     * Дополняем слева символами 'a', чтобы обеспечить минимальную длину.
     * Можно поменять символ дополнения при желании.
     */
    private static String pad(String code) {
        if (code.length() >= MIN_CODE_LENGTH) return code;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MIN_CODE_LENGTH - code.length(); i++) sb.append('a');
        sb.append(code);
        return sb.toString();
    }
}