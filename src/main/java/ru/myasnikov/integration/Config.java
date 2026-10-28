package ru.myasnikov.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public record Config(String apiBaseUrl, int studentId, String username, String password,
                     String dbUrl, String dbUser, String dbPassword, Path reportDir, int maxPages) {
    public static Config load() throws Exception {
        Map<String, String> values = new HashMap<>();
        Path dotenv = Path.of(".env");
        if (Files.exists(dotenv)) {
            for (String line : Files.readAllLines(dotenv, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                int separator = line.indexOf('=');
                if (separator < 1) throw new IllegalArgumentException("Invalid .env entry");
                String value = line.substring(separator + 1).trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                values.put(line.substring(0, separator).trim(), value);
            }
        }
        values.putAll(System.getenv());
        return new Config(values.getOrDefault("COURSE_API_BASE_URL", "http://77.67.89.207:8080").replaceAll("/+$", ""),
                Integer.parseInt(values.getOrDefault("COURSE_STUDENT_ID", "28")),
                values.getOrDefault("COURSE_API_USERNAME", "student28"), values.getOrDefault("COURSE_API_PASSWORD", ""),
                values.getOrDefault("COURSE_DB_URL", "jdbc:postgresql://localhost:15428/integration"),
                values.getOrDefault("COURSE_DB_USER", "integration"), values.getOrDefault("COURSE_DB_PASSWORD", ""),
                Path.of(values.getOrDefault("COURSE_REPORT_DIR", "reports")),
                Integer.parseInt(values.getOrDefault("COURSE_MAX_PAGES", "10000")));
    }

    public Config {
        if (studentId < 1 || maxPages < 1) throw new IllegalArgumentException("Invalid studentId or COURSE_MAX_PAGES");
        if (!apiBaseUrl.matches("https?://[^?#]+")) throw new IllegalArgumentException("Invalid COURSE_API_BASE_URL");
    }

    public void requireCredentials() {
        if (username.isBlank() || password.isBlank() || dbPassword.isBlank())
            throw new IllegalArgumentException("Fill COURSE_API_USERNAME, COURSE_API_PASSWORD and COURSE_DB_PASSWORD in .env");
    }

    @Override public String toString() { return "Config[studentId=" + studentId + ", secrets=REDACTED]"; }
}
