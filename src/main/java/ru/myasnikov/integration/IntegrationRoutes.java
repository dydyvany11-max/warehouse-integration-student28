package ru.myasnikov.integration;

import com.fasterxml.jackson.databind.*;
import org.apache.camel.*;
import org.apache.camel.builder.RouteBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** The integration is orchestrated by Camel routes; HTTP calls use camel-http. */
public final class IntegrationRoutes extends RouteBuilder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Config config;
    private final Store store;

    public IntegrationRoutes(Config config, Store store) { this.config = config; this.store = store; }

    @Override public void configure() {
        errorHandler(noErrorHandler());

        from("direct:results").routeId("student-results")
            .process(e -> request(e, "GET", null, null))
            .to(apiEndpoint("/students/" + config.studentId() + "/results"))
            .convertBodyTo(String.class)
            .process(e -> verifyResult(e.getMessage().getBody(String.class)));

        from("direct:sync").routeId("cms-to-postgres-to-report")
            .process(e -> {
                e.setProperty("runId", store.begin());
                e.setProperty("page", 0);
                e.setProperty("more", true);
                e.setProperty("incoming", new ArrayList<Spare>());
                e.setProperty("codes", new HashSet<String>());
            })
            .loopDoWhile(exchangeProperty("more").isEqualTo(true))
                .process(this::preparePage)
                .to(apiEndpoint("/cms/spares"))
                .convertBodyTo(String.class)
                .process(this::acceptPage)
            .end()
            .process(this::persistAndExport)
            .to("direct:token")
            .process(e -> {
                JsonNode response = JSON.readTree(e.getMessage().getBody(String.class));
                JsonNode token = response.get("token");
                if (token == null || !token.isTextual() || token.asText().isBlank()) throw new IllegalStateException("API did not return an authorization token");
                request(e, "POST", e.getProperty("csv", String.class).getBytes(StandardCharsets.UTF_8), "text/csv; charset=UTF-8");
                e.getMessage().setHeader("Authorization", "Bearer " + token.asText());
            })
            .to(apiEndpoint("/report/csv"))
            .convertBodyTo(String.class)
            .process(e -> {
                String response = e.getMessage().getBody(String.class);
                verifyResult(response);
                store.finish(e.getProperty("runId", UUID.class), response);
                e.setProperty("uploadResult", response);
                write("result-" + e.getProperty("runId") + ".json", response);
            })
            .to("direct:results")
            .process(e -> {
                write("latest-result.json", e.getMessage().getBody(String.class));
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("studentId", config.studentId());
                summary.put("runId", e.getProperty("runId").toString());
                summary.put("fetched", e.getProperty("fetched"));
                summary.put("historicalTotal", e.getProperty("total"));
                summary.put("report", config.reportDir().resolve("latest.csv").toString());
                summary.put("uploadResult", JSON.readTree(e.getProperty("uploadResult", String.class)));
                summary.put("verifiedResult", JSON.readTree(e.getMessage().getBody(String.class)));
                String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
                write("latest-summary.json", json);
                e.getMessage().setBody(json);
            });

        from("direct:token").routeId("report-authentication")
            .process(e -> request(e, "POST", JSON.writeValueAsBytes(Map.of("username", config.username(), "password", config.password())), "application/json"))
            .to(apiEndpoint("/auth/token"))
            .convertBodyTo(String.class);
    }

    private String apiEndpoint(String path) {
        return config.apiBaseUrl() + path + "?connectTimeout=15000&responseTimeout=30000&connectionRequestTimeout=15000";
    }

    private static void request(Exchange e, String method, Object body, String contentType) {
        e.getMessage().getHeaders().clear();
        e.getMessage().setHeader(Exchange.HTTP_METHOD, method);
        e.getMessage().setHeader("Accept", "application/json");
        if (contentType != null) e.getMessage().setHeader(Exchange.CONTENT_TYPE, contentType);
        e.getMessage().setBody(body);
    }

    private void preparePage(Exchange e) {
        int page = e.getProperty("page", Integer.class);
        if (page >= config.maxPages()) throw new IllegalStateException("Pagination exceeded MAX_PAGES; refusing incomplete synchronization");
        request(e, "GET", null, null);
        String credentials = config.username() + ":" + config.password();
        e.getMessage().setHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        e.getMessage().setHeader(Exchange.HTTP_QUERY, "page=" + page + "&size=10");
    }

    @SuppressWarnings("unchecked")
    private void acceptPage(Exchange e) throws Exception {
        JsonNode page = JSON.readTree(e.getMessage().getBody(String.class));
        if (!page.isArray() || page.size() > 10) throw new IllegalStateException("CMS returned an invalid page");
        List<Spare> incoming = (List<Spare>) e.getProperty("incoming");
        Set<String> codes = (Set<String>) e.getProperty("codes");
        for (JsonNode item : page) {
            Spare spare = Spare.parse(item);
            if (!codes.add(spare.spareCode())) throw new IllegalStateException("CMS returned duplicate code; pagination may be broken: " + spare.spareCode());
            incoming.add(spare);
        }
        e.setProperty("more", page.size() == 10);
        e.setProperty("page", e.getProperty("page", Integer.class) + 1);
    }

    @SuppressWarnings("unchecked")
    private void persistAndExport(Exchange e) throws Exception {
        List<Spare> incoming = (List<Spare>) e.getProperty("incoming");
        // An unexpectedly empty warehouse must not be submitted as a successful report.
        if (incoming.isEmpty()) throw new IllegalStateException("CMS returned no details; database preserved and report not sent");
        store.save(e.getProperty("runId", UUID.class), incoming);
        List<Spare> historical = store.all();
        String csv = Csv.format(historical);
        write("report-" + e.getProperty("runId") + ".csv", csv);
        write("latest.csv", csv);
        e.setProperty("csv", csv);
        e.setProperty("fetched", incoming.size());
        e.setProperty("total", historical.size());
    }

    private void verifyResult(String response) throws Exception {
        JsonNode result = JSON.readTree(response);
        if (!result.path("studentId").isIntegralNumber() || result.path("studentId").asInt() != config.studentId()
                || !result.path("result").isIntegralNumber()) throw new IllegalStateException("API returned a result for an unexpected student or in an invalid format");
    }

    private void write(String name, String content) throws Exception {
        Files.createDirectories(config.reportDir());
        Path temp = Files.createTempFile(config.reportDir(), ".write-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, config.reportDir().resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
