package ru.bmstu.dzhioev.urlshortener.utils;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Генерация коротких кодов.
 *
 * ThreadLocalRandom — потокобезопасен без синхронизации (каждый поток
 * работает со своим экземпляром), значительно быстрее SecureRandom
 * под конкурентной нагрузкой. Для URL-кодов криптостойкость не нужна.
 *
 * 62^7 = ~3.5 триллиона комбинаций — вероятность коллизии пренебрежимо мала
 * при разумном объёме данных.
 */
public final class Util {

    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int CODE_LENGTH = 7;

    private Util() {}

    public static String generateShortCode() {
        char[] code = new char[CODE_LENGTH];
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < CODE_LENGTH; i++) {
            code[i] = BASE62[rnd.nextInt(BASE62.length)];
        }
        return new String(code);
    }
}
