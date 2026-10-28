package ru.myasnikov.integration;

import org.apache.camel.*;
import org.apache.camel.impl.DefaultCamelContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

public final class App {
    public static void main(String[] args) {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        try {
            String command = args.length == 0 ? "sync" : args[0];
            if (!java.util.Set.of("sync", "results", "export").contains(command))
                throw new IllegalArgumentException("Usage: java -jar warehouse-integration-1.0.0.jar [sync|results|export]");
            Config config = Config.load();
            if (command.equals("results")) {
                try (var context = new DefaultCamelContext()) {
                    context.setLogMask(true);
                    context.addRoutes(new IntegrationRoutes(config, null));
                    context.start();
                    try (var producer = context.createProducerTemplate()) {
                        System.out.println(producer.requestBody("direct:results", null, String.class));
                    }
                }
                return;
            }
            config.requireCredentials();
            try (var store = new Store(config)) {
                if (command.equals("export")) {
                    Files.createDirectories(config.reportDir());
                    Files.writeString(config.reportDir().resolve("latest.csv"), Csv.format(store.all()), StandardCharsets.UTF_8);
                    System.out.println("CSV exported to " + config.reportDir().resolve("latest.csv"));
                    return;
                }
                try (var context = new DefaultCamelContext()) {
                    context.setLogMask(true);
                    context.addRoutes(new IntegrationRoutes(config, store));
                    context.start();
                    try (var producer = context.createProducerTemplate()) {
                        Exchange result = producer.request("direct:sync", e -> { });
                        if (result.getException() != null) {
                            UUID runId = result.getProperty("runId", UUID.class);
                            if (runId != null) store.fail(runId, safeMessage(result.getException(), config));
                            throw result.getException();
                        }
                        System.out.println(result.getMessage().getBody(String.class));
                    }
                }
            }
        } catch (Exception error) {
            // Never print request bodies, authorization headers, passwords, or access tokens.
            System.err.println("Operation failed: " + safeMessage(error, null));
            System.exit(1);
        }
    }

    private static String safeMessage(Throwable error, Config config) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        if (config != null) {
            if (!config.password().isEmpty()) message = message.replace(config.password(), "REDACTED");
            if (!config.dbPassword().isEmpty()) message = message.replace(config.dbPassword(), "REDACTED");
        }
        return message;
    }
}
