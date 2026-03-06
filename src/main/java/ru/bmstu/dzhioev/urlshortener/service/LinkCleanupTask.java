package ru.bmstu.dzhioev.urlshortener.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.bmstu.dzhioev.urlshortener.repository.LinkRepository;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class LinkCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(LinkCleanupTask.class);
    private static final int BATCH_SIZE = 500;

    private final LinkRepository repository;

    @Scheduled(fixedDelayString = "${app.cleanup-interval-ms:3600000}") // Лучше fixedDelay, чтобы не пересекались
    public void cleanupExpiredLinks() {
        int totalDeleted = 0;
        int deleted;

        do {
            // Транзакция обеспечивается на уровне самого репозитория (@Modifying)
            deleted = repository.deleteExpiredBatch(Instant.now(), BATCH_SIZE);
            totalDeleted += deleted;
        } while (deleted == BATCH_SIZE);

        if (totalDeleted > 0) {
            log.info("Очистка просроченных ссылок: удалено {} записей", totalDeleted);
        }
    }
}
