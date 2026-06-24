package com.example.orderbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class OrderRepository {
    private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);
    private final String jdbcUrl;

    public OrderRepository(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public void init() throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // Базовая схема (включая trigger_at, media_json)
                st.execute("""
                    CREATE TABLE IF NOT EXISTS orders(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      order_number TEXT NOT NULL,
                      department   TEXT NOT NULL,
                      due_at       INTEGER NOT NULL,
                      group_chat_id INTEGER NOT NULL,
                      photo_file_id TEXT,
                      reminder72h_sent INTEGER NOT NULL DEFAULT 0,
                      last_reminder_sent_at INTEGER,
                      last_status   TEXT,
                      last_status_check_at INTEGER,
                      created_at    INTEGER NOT NULL,
                      trigger_at    INTEGER,
                      media_json    TEXT
                    )
                """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_due_at ON orders(due_at)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_order_number ON orders(order_number)");
                ensureColumnExists(c, "orders", "last_reminder_sent_at",
                        "ALTER TABLE orders ADD COLUMN last_reminder_sent_at INTEGER");
                backfillLastReminderSentAt(c);

                // --- Авто-дедупликация перед созданием UNIQUE ---
                // Оставляем самую свежую запись (MAX(id)) в каждой группе (chat, order_number, due_at)
                int removed = deduplicateByCompositeKey(c);
                if (removed > 0) {
                    log.warn("Deduplicated {} duplicate rows in orders before creating UNIQUE index", removed);
                }

                // Пытаемся создать UNIQUE индекс; если вдруг ещё остались дубли (крайне маловероятно) — повторим чистку
                try {
                    st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_chat_order_due ON orders(group_chat_id, order_number, due_at)");
                } catch (SQLException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                    if (msg.contains("unique") || msg.contains("constraint")) {
                        log.warn("UNIQUE index creation failed once, retrying after dedup: {}", e.toString());
                        removed = deduplicateByCompositeKey(c);
                        if (removed > 0) {
                            log.warn("Deduplicated {} rows on retry", removed);
                        }
                        st.execute("DROP INDEX IF EXISTS ux_chat_order_due");
                        st.execute("CREATE UNIQUE INDEX ux_chat_order_due ON orders(group_chat_id, order_number, due_at)");
                    } else {
                        throw e;
                    }
                }

                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** Удаляет дубликаты по (group_chat_id, order_number, due_at), оставляя MAX(id). Возвращает кол-во удалённых строк. */
    private int deduplicateByCompositeKey(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            // В temp-CTE собираем id записей, которые надо оставить, затем удаляем все остальные
            String keepCte = """
                WITH keep AS (
                    SELECT MAX(id) AS id
                    FROM orders
                    GROUP BY group_chat_id, order_number, due_at
                )
                DELETE FROM orders
                WHERE id NOT IN (SELECT id FROM keep)
            """;
            int affected = st.executeUpdate(keepCte);
            return affected;
        }
    }

    private void ensureColumnExists(Connection c, String table, String column, String alterSql) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        if (!columns.contains(column)) {
            try (Statement st = c.createStatement()) {
                st.execute(alterSql);
            }
        }
    }

    private void backfillLastReminderSentAt(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE orders SET last_reminder_sent_at=? " +
                        "WHERE reminder72h_sent=1 AND last_reminder_sent_at IS NULL")) {
            ps.setLong(1, Instant.now().getEpochSecond());
            ps.executeUpdate();
        }
    }

    public long insert(Order o) throws SQLException {
        String sql = """
            INSERT INTO orders(order_number,department,due_at,group_chat_id,photo_file_id,
                               reminder72h_sent,last_reminder_sent_at,last_status,last_status_check_at,created_at,trigger_at,media_json)
            VALUES(?,?,?,?,?,0,NULL,NULL,NULL,?,?,?)
        """;
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, o.orderNumber());
            ps.setString(2, o.department());
            ps.setLong(3, o.dueAtEpochSec());
            ps.setLong(4, o.groupChatId());
            ps.setString(5, o.photoFileId());
            ps.setLong(6, o.createdAtEpochSec());
            if (o.triggerAtEpochSec() == null) ps.setNull(7, Types.INTEGER);
            else ps.setLong(7, o.triggerAtEpochSec());
            if (o.mediaJson() == null) ps.setNull(8, Types.VARCHAR);
            else ps.setString(8, o.mediaJson());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            // На случай гонки: если пара (chat, order, due) уже существует — просто найдём и вернём id
            if (msg.contains("unique") || msg.contains("constraint")) {
                Optional<Order> ex = findByChatNumberDue(o.groupChatId(), o.orderNumber(), o.dueAtEpochSec());
                if (ex.isPresent()) {
                    LoggerFactory.getLogger(OrderRepository.class).info(
                            "Duplicate order ignored on insert: chat={}, number={}, due={}",
                            o.groupChatId(), o.orderNumber(), o.dueAtEpochSec());
                    return ex.get().id();
                }
            }
            throw e;
        }
        throw new SQLException("Failed to insert order");
    }

    public Optional<Order> findByChatNumberDue(long chatId, String number, long dueAt) throws SQLException {
        String sql = "SELECT * FROM orders WHERE group_chat_id=? AND order_number=? AND due_at=? ORDER BY id DESC LIMIT 1";
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setString(2, number);
            ps.setLong(3, dueAt);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    public Optional<Order> findByChatAndNumber(long chatId, String number) throws SQLException {
        String sql = "SELECT * FROM orders WHERE group_chat_id=? AND order_number=? ORDER BY id DESC LIMIT 1";
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setString(2, number);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    public void markReminderSent(long id) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE orders SET reminder72h_sent=1, last_reminder_sent_at=?, trigger_at=NULL WHERE id=?")) {
            ps.setLong(1, Instant.now().getEpochSecond());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void markReminderInitialized(long id) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE orders SET reminder72h_sent=1, last_reminder_sent_at=?, trigger_at=NULL WHERE id=?")) {
            ps.setLong(1, Instant.now().getEpochSecond());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void markRepeatReminderSent(long id) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE orders SET last_reminder_sent_at=? WHERE id=?")) {
            ps.setLong(1, Instant.now().getEpochSecond());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void updateStatus(long id, String status) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE orders SET last_status=?, last_status_check_at=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setLong(2, Instant.now().getEpochSecond());
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public List<Order> findDueBetween(long startEpochSec, long endEpochSec) throws SQLException {
        String sql = """
            SELECT * FROM orders
             WHERE reminder72h_sent=0
               AND due_at BETWEEN ? AND ?
        """;
        List<Order> list = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, startEpochSec);
            ps.setLong(2, endEpochSec);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    public List<Order> findRepeatReminderCandidates(long sentBeforeEpochSec, Long dueAfterEpochSec) throws SQLException {
        StringBuilder sql = new StringBuilder("""
            SELECT * FROM orders
             WHERE reminder72h_sent=1
               AND last_reminder_sent_at IS NOT NULL
               AND last_reminder_sent_at <= ?
        """);
        if (dueAfterEpochSec != null) {
            sql.append(" AND due_at >= ?");
        }
        List<Order> list = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setLong(1, sentBeforeEpochSec);
            if (dueAfterEpochSec != null) {
                ps.setLong(2, dueAfterEpochSec);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    private Order map(ResultSet rs) throws SQLException {
        Long trig = rs.getObject("trigger_at") == null ? null : rs.getLong("trigger_at");
        Long lastReminderSentAt = rs.getObject("last_reminder_sent_at") == null ? null : rs.getLong("last_reminder_sent_at");
        String media = rs.getString("media_json");
        return new Order(
                rs.getLong("id"),
                rs.getString("order_number"),
                rs.getString("department"),
                rs.getLong("due_at"),
                rs.getLong("group_chat_id"),
                rs.getString("photo_file_id"),
                rs.getInt("reminder72h_sent") == 1,
                lastReminderSentAt,
                rs.getString("last_status"),
                rs.getLong("last_status_check_at"),
                rs.getLong("created_at"),
                trig,
                media
        );
    }
}
