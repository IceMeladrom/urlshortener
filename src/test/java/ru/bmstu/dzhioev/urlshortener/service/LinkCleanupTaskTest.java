package ru.bmstu.dzhioev.urlshortener.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.bmstu.dzhioev.urlshortener.repository.LinkRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LinkCleanupTaskTest {

    @Mock
    private LinkRepository linkRepository;

    @InjectMocks
    private LinkCleanupTask linkCleanupTask;

    @Test
    @DisplayName("Очистка удаляет пачками, пока удаляется BATCH_SIZE")
    void cleanupExpiredLinks_DeletesInBatches() {
        // Симуляция:
        // Итерация 1: удалено 500 (полная пачка)
        // Итерация 2: удалено 500 (полная пачка)
        // Итерация 3: удалено 120 (остаток, меньше 500) -> Цикл прерывается
        when(linkRepository.deleteExpiredBatch(any(), eq(500)))
                .thenReturn(500)
                .thenReturn(500)
                .thenReturn(120);

        linkCleanupTask.cleanupExpiredLinks();

        // Метод репозитория должен быть вызван ровно 3 раза
        verify(linkRepository, times(3)).deleteExpiredBatch(any(), eq(500));
    }

    @Test
    @DisplayName("Если нет просроченных, выполнится только 1 запрос в БД")
    void cleanupExpiredLinks_NoExpired_SingleRun() {
        when(linkRepository.deleteExpiredBatch(any(), eq(500))).thenReturn(0);

        linkCleanupTask.cleanupExpiredLinks();

        verify(linkRepository, times(1)).deleteExpiredBatch(any(), eq(500));
    }
}

