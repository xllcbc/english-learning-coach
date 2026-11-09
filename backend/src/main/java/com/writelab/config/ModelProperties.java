package com.writelab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Model provider configuration, bound from environment variables via {@code writelab.model.*}.
 * A blank/absent {@code apiKey} selects the deterministic Mock mode. The {@code toString} is
 * overridden to mask the key so it can never leak into logs through the properties object.
 */
@ConfigurationProperties(prefix = "writelab.model")
public record ModelProperties(String baseUrl, String apiKey, String name) {

    @Override
    public String toString() {
        return "ModelProperties[baseUrl=" + baseUrl + ", apiKey=" + (StringUtils.hasText(apiKey) ? "****" : "")
                + ", name=" + name + "]";
    }
}
