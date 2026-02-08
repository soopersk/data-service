package com.company.observability.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.postgresql.util.PGobject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

@Component
public class JsonbConverter {

    private final ObjectMapper objectMapper;

    public JsonbConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PGobject toJsonb(Map<String, Object> map) {
        try {
            PGobject jsonb = new PGobject();
            jsonb.setType("jsonb");
            jsonb.setValue(
                map == null ? null : objectMapper.writeValueAsString(map)
            );
            return jsonb;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize JSONB", e);
        }
    }

    public Map<String, Object> fromJsonb(ResultSet rs, String column)
            throws SQLException {

        String json = rs.getString(column);
        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(
                json,
                new TypeReference<Map<String, Object>>() {}
            );
        } catch (Exception e) {
            throw new SQLException(
                "Invalid JSON in column " + column,
                e
            );
        }
    }
}
