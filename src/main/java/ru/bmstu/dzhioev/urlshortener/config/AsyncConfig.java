package ru.bmstu.dzhioev.urlshortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Конфигурация пулов потоков для асинхронных задач.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(20);                 // увеличен с 10
        exec.setMaxPoolSize(100);                 // увеличен с 50
        exec.setQueueCapacity(1000);               // увеличен с 500
        exec.setThreadNamePrefix("async-exec-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy()); // задача выполняется в вызывающем потоке при переполнении
        exec.initialize();
        return exec;
    }
}