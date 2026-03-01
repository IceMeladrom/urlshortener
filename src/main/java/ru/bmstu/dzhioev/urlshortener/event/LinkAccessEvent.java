package ru.bmstu.dzhioev.urlshortener.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Событие, публикуемое при доступе к короткой ссылке.
 * Содержит shortCode — используется асинхронным обработчиком для обновлений в БД.
 */
@Getter
public class LinkAccessEvent extends ApplicationEvent {
    private final String shortCode;

    public LinkAccessEvent(Object source, String shortCode) {
        super(source);
        this.shortCode = shortCode;
    }
}