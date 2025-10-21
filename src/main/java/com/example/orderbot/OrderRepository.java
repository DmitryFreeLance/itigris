package com.example.orderbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class OrderRepository {
    private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);
    private final String jdbcUrl;

    public OrderRepository(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public void init() throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS orders(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  order_number TEXT NOT NULL,
                  department   TEXT NOT NULL,
                  due_at       INTEGER NOT NULL,
                  group_chat_id INTEGER NOT NULL,
                  photo_file_id TEXT,
                  reminder72h_sent INTEGER NOT NULL DEFAULT 0,
                  last_status   TEXT,
                  last_status_check_at INTEGER,
                  created_at    INTEGER NOT NULL
                )
            """);
            // безопасно: IF NOT EXISTS для индексов
            st.execute("CREATE INDEX IF NOT EXISTS idx_due_at ON orders(due_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_order_number ON orders(order_number)");
        }

        // Добавим новый столбец trigger_at при апгрейде схемы (мягко, если уже есть — проглотим ошибку)
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement()) {
            st.execute("ALTER TABLE orders ADD COLUMN trigger_at INTEGER");
        } catch (SQLException e) {
            // если уже существует — игнорируем
            if (!e.getMessage().toLowerCase().contains("duplicate column name")) {
                log.warn("ALTER TABLE add trigger_at failed: {}", e.toString());
            }
        }
    }

    public long insert(Order o) throws SQLException {
        String sql = """
            INSERT INTO orders(order_number,department,due_at,group_chat_id,photo_file_id,
                               reminder72h_sent,last_status,last_status_check_at,created_at,trigger_at)
            VALUES(?,?,?,?,?,0,NULL,NULL,?,?)
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
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Failed to insert order");
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
             PreparedStatement ps = c.prepareStatement("UPDATE orders SET reminder72h_sent=1, trigger_at=NULL WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public void clearTrigger(long id) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement("UPDATE orders SET trigger_at=NULL WHERE id=?")) {
            ps.setLong(1, id);
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

    /** Заказы, у которых due_at в указанном диапазоне (используем для окна вокруг lead). */
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

    /** Заказы, у которых test-триггер (trigger_at) попадает в окно now..now+window. */
    public List<Order> findTriggerBetween(long startEpochSec, long endEpochSec) throws SQLException {
        String sql = """
            SELECT * FROM orders
             WHERE reminder72h_sent=0
               AND trigger_at IS NOT NULL
               AND trigger_at BETWEEN ? AND ?
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

    private Order map(ResultSet rs) throws SQLException {
        Long trig = rs.getObject("trigger_at") == null ? null : rs.getLong("trigger_at");
        return new Order(
                rs.getLong("id"),
                rs.getString("order_number"),
                rs.getString("department"),
                rs.getLong("due_at"),
                rs.getLong("group_chat_id"),
                rs.getString("photo_file_id"),
                rs.getInt("reminder72h_sent") == 1,
                rs.getString("last_status"),
                rs.getLong("last_status_check_at"),
                rs.getLong("created_at"),
                trig
        );
    }
}