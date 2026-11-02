package com.cuhkcampus.course;

import java.time.*;
import java.util.*;

/** Applies only the editable fields declared by each business endpoint. */
public final class EntityFields {
    private EntityFields() {}
    public static void apply(Object entity, Map<String, Object> input, String... names) {
        for (String name : names) {
            if (!input.containsKey(name)) continue;
            try {
                var field = entity.getClass().getField(name);
                Object value = input.get(name);
                if (value != null) {
                    Class<?> type = field.getType();
                    if (type == UUID.class) value = UUID.fromString(value.toString());
                    else if (type == Instant.class) value = Instant.parse(value.toString());
                    else if (type == LocalDate.class) value = LocalDate.parse(value.toString());
                    else if (type == LocalTime.class) value = LocalTime.parse(value.toString());
                    else if (type == Integer.class || type == int.class) value = ((Number) value).intValue();
                }
                field.set(entity, value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
