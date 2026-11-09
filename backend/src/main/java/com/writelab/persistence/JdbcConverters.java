package com.writelab.persistence;

import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

import java.sql.Timestamp;
import java.time.Instant;

final class JdbcConverters {

    private JdbcConverters() {
    }

    @WritingConverter
    static final class JsonbWritingConverter implements Converter<Jsonb, PGobject> {

        @Override
        public PGobject convert(Jsonb source) {
            PGobject object = new PGobject();
            object.setType("jsonb");
            try {
                object.setValue(source.value());
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException("无法写入 JSONB 数据", exception);
            }
            return object;
        }
    }

    @ReadingConverter
    static final class JsonbReadingConverter implements Converter<PGobject, Jsonb> {

        @Override
        public Jsonb convert(PGobject source) {
            return new Jsonb(source.getValue());
        }
    }

    @WritingConverter
    static final class InstantWritingConverter implements Converter<Instant, Timestamp> {

        @Override
        public Timestamp convert(Instant source) {
            return Timestamp.from(source);
        }
    }

    @ReadingConverter
    static final class InstantReadingConverter implements Converter<Timestamp, Instant> {

        @Override
        public Instant convert(Timestamp source) {
            return source.toInstant();
        }
    }
}
