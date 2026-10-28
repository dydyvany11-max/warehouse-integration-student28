package ru.myasnikov.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public final class Store implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Connection connection;
    private final int studentId;

    public Store(Config config) throws Exception {
        studentId = config.studentId();
        connection = DriverManager.getConnection(config.dbUrl(), config.dbUser(), config.dbPassword());
        try {
            try (var lock = connection.prepareStatement("SELECT pg_try_advisory_lock(74511, ?)")) {
                lock.setInt(1, studentId);
                try (var result = lock.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) throw new IllegalStateException("Another sync is running for this student");
                }
            }
            try (var stream = Store.class.getResourceAsStream("/schema.sql")) {
                if (stream == null) throw new IllegalStateException("schema.sql missing");
                try (var statement = connection.createStatement()) {
                    statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) { connection.close(); throw e; }
    }

    public UUID begin() throws SQLException {
        UUID id = UUID.randomUUID();
        try (var statement = connection.prepareStatement("INSERT INTO sync_runs(id,student_id) VALUES (?,?)")) {
            statement.setObject(1, id); statement.setInt(2, studentId); statement.executeUpdate();
        }
        return id;
    }

    public void save(UUID runId, List<Spare> incoming) throws Exception {
        Set<String> codes = new HashSet<>();
        for (Spare spare : incoming) if (!codes.add(spare.spareCode())) throw new IllegalArgumentException("Duplicate spareCode: " + spare.spareCode());
        connection.setAutoCommit(false);
        try {
            try (var mark = connection.prepareStatement("UPDATE spares SET is_present=false WHERE student_id=?")) {
                mark.setInt(1, studentId); mark.executeUpdate();
            }
            String upsert = """
                INSERT INTO spares(student_id,spare_code,spare_name,spare_description,spare_type,spare_status,price,quantity,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (student_id,spare_code) DO UPDATE SET
                spare_name=EXCLUDED.spare_name,spare_description=EXCLUDED.spare_description,
                spare_type=EXCLUDED.spare_type,spare_status=EXCLUDED.spare_status,price=EXCLUDED.price,
                quantity=EXCLUDED.quantity,updated_at=EXCLUDED.updated_at,last_seen_at=now(),is_present=true
                """;
            try (var statement = connection.prepareStatement(upsert);
                 var snapshot = connection.prepareStatement("INSERT INTO spare_snapshots(run_id,student_id,spare_code,payload) VALUES (?,?,?,?::jsonb)")) {
                for (Spare spare : incoming) {
                    statement.setInt(1, studentId); statement.setString(2, spare.spareCode()); statement.setString(3, spare.spareName());
                    statement.setString(4, spare.spareDescription()); statement.setString(5, spare.spareType()); statement.setString(6, spare.spareStatus());
                    statement.setString(7, spare.price()); statement.setInt(8, spare.quantity()); statement.setString(9, spare.updatedAt()); statement.addBatch();
                    snapshot.setObject(1, runId); snapshot.setInt(2, studentId); snapshot.setString(3, spare.spareCode());
                    snapshot.setString(4, JSON.writeValueAsString(spare)); snapshot.addBatch();
                }
                statement.executeBatch(); snapshot.executeBatch();
            }
            try (var statement = connection.prepareStatement("UPDATE sync_runs SET fetched_count=?,total_count=(SELECT count(*) FROM spares WHERE student_id=?),status='SAVED' WHERE id=?")) {
                statement.setInt(1, incoming.size()); statement.setInt(2, studentId); statement.setObject(3, runId); statement.executeUpdate();
            }
            connection.commit();
        } catch (Exception e) { connection.rollback(); throw e; }
        finally { connection.setAutoCommit(true); }
    }

    public List<Spare> all() throws SQLException {
        List<Spare> spares = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT spare_code,spare_name,spare_description,spare_type,spare_status,price,quantity,updated_at FROM spares WHERE student_id=? ORDER BY spare_code")) {
            statement.setInt(1, studentId);
            try (var result = statement.executeQuery()) {
                while (result.next()) spares.add(new Spare(result.getString(1),result.getString(2),result.getString(3),result.getString(4),result.getString(5),result.getString(6),result.getInt(7),result.getString(8)));
            }
        }
        return spares;
    }

    public void finish(UUID runId, String result) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE sync_runs SET status='SUBMITTED',completed_at=now(),server_result=?::jsonb WHERE id=?")) {
            statement.setString(1, result); statement.setObject(2, runId); statement.executeUpdate();
        }
    }

    public void fail(UUID runId, String message) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE sync_runs SET status=CASE WHEN status='SUBMITTED' THEN 'SUBMITTED_VERIFY_FAILED' ELSE 'FAILED' END,completed_at=now(),error=? WHERE id=?")) {
            statement.setString(1, message); statement.setObject(2, runId); statement.executeUpdate();
        }
    }
    @Override public void close() throws SQLException { connection.close(); }
}
