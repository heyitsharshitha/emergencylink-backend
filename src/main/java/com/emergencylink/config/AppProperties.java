package com.emergencylink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Cors cors, Resources resources, Seed seed, Llm llm) {
    public record Jwt(String secret, long expirationMinutes) {}
    public record Cors(java.util.List<String> allowedOrigins) {}
    public record Resources(long staleAfterMinutes) {}
    public record Seed(boolean enabled) {}
    public record Llm(String apiKey, String baseUrl, String model, int maxTokens, int historyMessages) {}
}
