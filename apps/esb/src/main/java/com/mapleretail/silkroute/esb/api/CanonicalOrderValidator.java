package com.mapleretail.silkroute.esb.api;

import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalOrder;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Validates the inbound canonical JSON against the ESB-owned JSON Schema
 * (canonical/order/v1/order.schema.json, draft 2020-12, additionalProperties
 * false at every level) using the networknt validator (camel-json-validator
 * starter dependency). Invalid payloads → HTTP 400 SCHEMA-VIOLATION (SENDER).
 */
@Component
public class CanonicalOrderValidator {

    private final ObjectMapper mapper;
    private final JsonSchema schema;

    public CanonicalOrderValidator() {
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        config.setFormatAssertionsEnabled(true);
        try (InputStream in = new ClassPathResource("canonical/order/v1/order.schema.json").getInputStream()) {
            String schemaText = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaText, config);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load canonical order schema", e);
        }
    }

    public ValidationResult validate(String json) {
        try {
            var node = mapper.readTree(json == null ? "" : json);
            var messages = schema.validate(node);
            if (messages.isEmpty()) {
                return ValidationResult.ok();
            }
            List<String> errors = messages.stream().map(ValidationMessage::getMessage).sorted().toList();
            return ValidationResult.failing(errors);
        } catch (Exception e) {
            return ValidationResult.failing(List.of("request body is not valid JSON: " + e.getMessage()));
        }
    }

    public CanonicalOrder parse(String json) {
        try {
            return mapper.readValue(json, CanonicalOrder.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse canonical order: " + e.getMessage(), e);
        }
    }

    public record ValidationResult(boolean valid, List<String> errors) {
        static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        static ValidationResult failing(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }
}
