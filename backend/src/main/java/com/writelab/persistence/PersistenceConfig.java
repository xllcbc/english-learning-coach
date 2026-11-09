package com.writelab.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;

import java.util.List;

@Configuration
public class PersistenceConfig {

    @Bean
    JdbcCustomConversions jdbcCustomConversions() {
        return new JdbcCustomConversions(List.of(
                new JdbcConverters.JsonbWritingConverter(),
                new JdbcConverters.JsonbReadingConverter(),
                new JdbcConverters.InstantWritingConverter(),
                new JdbcConverters.InstantReadingConverter()
        ));
    }
}
