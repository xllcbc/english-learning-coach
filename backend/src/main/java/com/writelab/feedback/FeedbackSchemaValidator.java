package com.writelab.feedback;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;

/**
 * JSON Schema layer of structured-output validation. The schema is generated from the
 * {@link StructuredFeedback} bean, so the contract the model is asked to satisfy and the
 * contract we validate against can never drift apart.
 */
public final class FeedbackSchemaValidator {

    private final Schema schema;

    private FeedbackSchemaValidator(String schemaJson) {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.schema = registry.getSchema(schemaJson, InputFormat.JSON);
    }

    public static FeedbackSchemaValidator forStructuredFeedback() {
        String schemaJson = new BeanOutputConverter<>(StructuredFeedback.class).getJsonSchema();
        return new FeedbackSchemaValidator(schemaJson);
    }

    /** Returns specific, human-readable schema errors; empty when the payload conforms. */
    public List<String> validate(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of("模型未返回结构化 JSON 内容");
        }
        try {
            List<Error> errors = schema.validate(rawJson, InputFormat.JSON);
            return errors.stream()
                    .map(FeedbackSchemaValidator::describe)
                    .toList();
        } catch (RuntimeException exception) {
            return List.of("结构化 JSON 无法解析：" + exception.getClass().getSimpleName());
        }
    }

    private static String describe(Error error) {
        String location = error.getInstanceLocation() == null ? "" : error.getInstanceLocation().toString();
        String message = error.getMessage() == null ? error.getKeyword() : error.getMessage();
        return location.isBlank() ? message : location + " " + message;
    }
}
