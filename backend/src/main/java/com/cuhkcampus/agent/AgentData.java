package com.cuhkcampus.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

@Component
public class AgentData {
    public final JdbcClient jdbc;
    public final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    public AgentData(JdbcClient jdbc) { this.jdbc = jdbc; }
    public String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }
    public Object parse(String value) {
        try { return value == null ? null : json.readValue(value, Object.class); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> object(String value) { return (Map<String,Object>) parse(value); }
    private Object[] parameters(Object[] params) {
        return Arrays.stream(params).map(p -> p instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : p).toArray();
    }
    public List<Map<String,Object>> rows(String sql, Object... params) {
        return jdbc.sql(sql).params(parameters(params)).query((rs, rowNum) -> {
            Map<String,Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                String label = rs.getMetaData().getColumnLabel(i);
                StringBuilder camel = new StringBuilder();
                boolean upper = false;
                for (char c : label.toCharArray()) {
                    if (c == '_') upper = true;
                    else { camel.append(upper ? Character.toUpperCase(c) : c); upper = false; }
                }
                Object value = rs.getObject(i);
                if (value instanceof Timestamp timestamp) value = timestamp.toInstant().toString();
                else if (value instanceof OffsetDateTime dateTime) value = dateTime.toInstant().toString();
                else if ("jsonb".equals(rs.getMetaData().getColumnTypeName(i))) value = parse(rs.getString(i));
                row.put(camel.toString(), value);
            }
            return row;
        }).list();
    }
    public Map<String,Object> one(String sql, Object... params) {
        return rows(sql, params).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    public int update(String sql, Object... params) { return jdbc.sql(sql).params(parameters(params)).update(); }
    public void conversation(UUID userId, UUID id) {
        one("select id from conversations where id=? and user_id=?", id, userId);
    }
}
