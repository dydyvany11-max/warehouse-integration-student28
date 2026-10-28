package ru.myasnikov.integration;

import java.util.*;
import java.util.stream.Collectors;

public final class Csv {
    private Csv() { }
    public static String format(List<Spare> spares) {
        return spares.stream().sorted(Comparator.comparing(Spare::spareCode))
                .map(s -> s.fields().stream().map(Csv::escape).collect(Collectors.joining(";")))
                .collect(Collectors.joining("\n", "", spares.isEmpty() ? "" : "\n"));
    }
    private static String escape(String value) {
        return value.matches("(?s).*[;\"\r\n].*") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
