package com.fairylearn.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(@Value("${ai.python.base-url}") String baseUrl) {
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(configurer -> {
                    configurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder());
                    configurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder());
                }).build();

        return WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(exchangeStrategies)
                .build();
    }
}
