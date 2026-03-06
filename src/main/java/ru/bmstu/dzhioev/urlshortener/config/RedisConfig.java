package ru.bmstu.dzhioev.urlshortener.config;

import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    @Bean
    public ClientResourcesBuilderCustomizer lettuceMetrics(MeterRegistry meterRegistry) {
        return builder -> {
            // Включаем гистограмму (пример опции)
            MicrometerOptions options = MicrometerOptions.builder()
                    .histogram(true)
                    .build();
            // Регистрируем recorder в ClientResources.Builder
            builder.commandLatencyRecorder(new MicrometerCommandLatencyRecorder(meterRegistry, options));
        };
    }
}