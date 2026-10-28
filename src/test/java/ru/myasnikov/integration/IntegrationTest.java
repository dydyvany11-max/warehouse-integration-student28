package ru.myasnikov.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.apache.camel.*;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path reports;
    HttpServer server;
    Config config;
    String schema;
    List<Spare> source = new ArrayList<>();
    List<Integer> requestedPages = new ArrayList<>();
    String uploaded;
    int submissions;
    int brokenPage = -1;
    boolean duplicatePages;
    boolean rejectReport;
    int responseStudent = 28;

    @BeforeEach void setup() throws Exception {
        Assumptions.assumeTrue(System.getenv("TEST_DB_URL") != null, "Set TEST_DB_URL and TEST_DB_PASSWORD to run PostgreSQL integration tests");
        schema = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getenv("TEST_DB_URL"), "integration", System.getenv("TEST_DB_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cms/spares", this::cms);
        server.createContext("/auth/token", exchange -> {
            var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            if (!request.path("username").asText().equals("student28") || !request.path("password").asText().equals("test-password")) { reply(exchange,401,"{}"); return; }
            reply(exchange,200,"{\"token\":\"test-token\"}");
        });
        server.createContext("/report/csv", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod()) || !"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) { reply(exchange,401,"{}"); return; }
            uploaded = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            submissions++;
            reply(exchange, rejectReport ? 500 : 200, result());
        });
        server.createContext("/students/28/results", exchange -> reply(exchange,200,result()));
        server.start();
        String dbUrl = System.getenv("TEST_DB_URL");
        config = new Config("http://127.0.0.1:" + server.getAddress().getPort(),28,"student28","test-password",
                dbUrl + (dbUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema,"integration",System.getenv("TEST_DB_PASSWORD"),reports,100);
    }

    @AfterEach void cleanup() throws Exception {
        if (server != null) server.stop(0);
        if (schema != null) try (var connection = DriverManager.getConnection(System.getenv("TEST_DB_URL"),"integration",System.getenv("TEST_DB_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void twoPeriodsRetainRemovedUpdateExistingAndPreserveSnapshots() throws Exception {
        for (int i=1;i<=12;i++) source.add(spare(i,"10.00"));
        try (var store = new Store(config)) {
            sync(store);
            assertEquals(List.of(0,1),requestedPages);
            assertEquals(12,store.all().size());
            assertEquals(12,uploaded.lines().count());
            assertTrue(uploaded.contains("Деталь 1"));
            source.removeFirst();
            source.set(0,spare(2,"99.1200"));
            source.add(spare(13,"13.00"));
            sync(store);
            assertEquals(13,store.all().size());
            assertTrue(store.all().stream().anyMatch(s -> s.spareCode().equals("SPARE-1")));
            assertEquals("99.1200",store.all().stream().filter(s -> s.spareCode().equals("SPARE-2")).findFirst().orElseThrow().price());
            assertEquals(13,uploaded.lines().count());
            assertEquals(24,count("spare_snapshots"));
            sync(store);
            assertEquals(13,store.all().size());
            assertEquals(36,count("spare_snapshots"));
            assertEquals(3,submissions);
            assertTrue(Files.exists(reports.resolve("latest-result.json")));
            try (var connection = DriverManager.getConnection(config.dbUrl(),config.dbUser(),config.dbPassword()); var statement=connection.createStatement(); var result=statement.executeQuery("SELECT is_present FROM spares WHERE spare_code='SPARE-1'")) {
                result.next(); assertFalse(result.getBoolean(1));
            }
        }
    }

    @Test void pageFailureDoesNotPartiallyUpdateDatabaseOrSendReport() throws Exception {
        source.add(spare(1,"10"));
        try (var store=new Store(config)) {
            sync(store);
            source.clear(); for(int i=1;i<=12;i++)source.add(spare(i,"900"));
            brokenPage=1;
            assertThrows(Exception.class,() -> sync(store));
            assertEquals(1,store.all().size()); assertEquals("10",store.all().getFirst().price());
            assertEquals(1,submissions); assertEquals(1,count("spare_snapshots"));
        }
    }

    @Test void exactFullPageRequiresFollowingEmptyPage() throws Exception {
        for(int i=1;i<=10;i++)source.add(spare(i,"10"));
        try(var store=new Store(config)){sync(store);assertEquals(List.of(0,1),requestedPages);assertEquals(10,store.all().size());}
    }

    @Test void duplicatePageIsRejectedInsteadOfLoopingOrUploading() throws Exception {
        for(int i=1;i<=10;i++)source.add(spare(i,"10"));
        duplicatePages=true;
        try(var store=new Store(config)){assertThrows(Exception.class,() -> sync(store));assertTrue(store.all().isEmpty());assertEquals(0,submissions);}
    }

    @Test void uploadFailureKeepsHistoricalDataForNextAttempt() throws Exception {
        source.add(spare(1,"10")); rejectReport=true;
        try(var store=new Store(config)) {
            assertThrows(Exception.class,() -> sync(store)); assertEquals(1,store.all().size());
            assertTrue(Files.exists(reports.resolve("latest.csv")));
            rejectReport=false;sync(store);assertEquals(1,store.all().size());assertEquals(2,submissions);
        }
    }

    @Test void wrongStudentResultIsRejected() throws Exception {
        source.add(spare(1,"10"));responseStudent=999;
        try(var store=new Store(config)){assertThrows(Exception.class,() -> sync(store));assertFalse(Files.exists(reports.resolve("latest-result.json")));}
    }

    @Test void emptySourceDoesNotSendOrEraseHistory() throws Exception {
        source.add(spare(1,"10"));
        try(var store=new Store(config)){sync(store);source.clear();assertThrows(Exception.class,() -> sync(store));assertEquals(1,store.all().size());assertEquals(1,submissions);}
    }

    @Test void concurrentSyncIsRejected() throws Exception {
        try(var store=new Store(config)){assertThrows(IllegalStateException.class,() -> new Store(config));}
        try(var store=new Store(config)){assertTrue(store.all().isEmpty());}
    }

    private void sync(Store store) throws Exception {
        try(var context=new DefaultCamelContext()) {
            context.setLogMask(true);context.addRoutes(new IntegrationRoutes(config,store));context.start();
            try(var template=context.createProducerTemplate()) {
                Exchange exchange=template.request("direct:sync",e -> {});
                if(exchange.getException()!=null) throw exchange.getException();
            }
        }
    }
    private int count(String table) throws Exception {
        try(var connection=DriverManager.getConnection(config.dbUrl(),config.dbUser(),config.dbPassword());var statement=connection.createStatement();var result=statement.executeQuery("SELECT count(*) FROM "+table)){result.next();return result.getInt(1);}
    }
    private void cms(HttpExchange exchange) throws java.io.IOException {
        String expected="Basic "+Base64.getEncoder().encodeToString("student28:test-password".getBytes(StandardCharsets.UTF_8));
        if(!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))){reply(exchange,401,"{}");return;}
        String query=exchange.getRequestURI().getQuery();
        int page=Integer.parseInt(query.split("&")[0].split("=")[1]);requestedPages.add(page);
        if(page==brokenPage){reply(exchange,500,"{}");return;}
        int start=duplicatePages ? 0 : page*10;
        reply(exchange,200,JSON.writeValueAsString(source.subList(Math.min(start,source.size()),Math.min(start+10,source.size()))));
    }
    private String result(){return "{\"studentId\":"+responseStudent+",\"result\":3,\"period\":1,\"lastSyncMessage\":\"ok\"}";}
    private static void reply(HttpExchange exchange,int status,String body) throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json;charset=UTF-8");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
    static Spare spare(int id,String price){return new Spare("SPARE-"+id,"Деталь "+id,"Описание","BRAKE","AVAILABLE",price,3,"2026-10-28T10:00:00.123456789");}
}
