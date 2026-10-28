package ru.myasnikov.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

public record Spare(String spareCode, String spareName, String spareDescription, String spareType,
                    String spareStatus, String price, int quantity, String updatedAt) {
    private static final Set<String> TYPES = Set.of("ENGINE", "GEARBOX", "BRAKE", "CLUTCH", "BATTERY", "TIRE", "RADIATOR");
    private static final Set<String> STATUSES = Set.of("AVAILABLE", "RESERVED", "OUT_OF_STOCK", "IN_TRANSIT", "DAMAGED");

    public static Spare parse(JsonNode node) {
        JsonNode quantity = node.get("quantity");
        if (quantity == null || !quantity.isIntegralNumber() || !quantity.canConvertToInt())
            throw new IllegalArgumentException("Invalid quantity");
        return new Spare(text(node, "spareCode"), text(node, "spareName"), text(node, "spareDescription"),
                text(node, "spareType"), text(node, "spareStatus"), text(node, "price"), quantity.intValue(), text(node, "updatedAt"));
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("Missing or invalid field: " + key);
        return value.textValue();
    }

    public Spare {
        Objects.requireNonNull(spareCode); Objects.requireNonNull(spareName); Objects.requireNonNull(spareDescription);
        Objects.requireNonNull(spareType); Objects.requireNonNull(spareStatus); Objects.requireNonNull(price); Objects.requireNonNull(updatedAt);
        if (spareCode.isBlank() || spareName.isBlank()) throw new IllegalArgumentException("Empty code or name");
        if (!TYPES.contains(spareType) || !STATUSES.contains(spareStatus)) throw new IllegalArgumentException("Unknown type/status for " + spareCode);
        if (new BigDecimal(price).signum() < 0 || quantity < 0) throw new IllegalArgumentException("Negative price/quantity for " + spareCode);
        try { LocalDateTime.parse(updatedAt); }
        catch (DateTimeParseException local) {
            try { OffsetDateTime.parse(updatedAt); }
            catch (DateTimeParseException offset) { LocalDate.parse(updatedAt); }
        }
    }

    public List<String> fields() { return List.of(spareCode, spareName, spareDescription, spareType, spareStatus, price, Integer.toString(quantity), updatedAt); }
}
