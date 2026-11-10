package com.writelab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Model provider configuration, bound from environment variables via {@code writelab.model.*}.
 * A blank/absent {@code apiKey} selects the deterministic Mock mode. {@code timeout} and
 * {@code maxRetries} are applied per HTTP call; the SDK retries timeouts, so the worst-case
 * wait is {@code timeout * (1 + maxRetries)}. The {@code toString} is overridden to mask the
 * key so it can never leak into logs through the properties object.
 */
@ConfigurationProperties(prefix = "writelab.model")
public record ModelProperties(String baseUrl, String apiKey, String name, Duration timeout, int maxRetries) {

    @Override
    public String toString() {
        return "ModelProperties[baseUrl=" + baseUrl + ", apiKey=" + (StringUtils.hasText(apiKey) ? "****" : "")
                + ", name=" + name + ", timeout=" + timeout + ", maxRetries=" + maxRetries + "]";
    }
}
