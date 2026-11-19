package com.otog.repository;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.Notification;
import com.otog.model.NotificationType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite persistence repository for user read/unread notifications with idempotent
 * {@code dedup_key} handling and owner-scoped status updates.
 */
public class NotificationRepository {

    private final DatabaseManager databaseManager;

    public NotificationRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Persists a notification idempotently. If an identical {@code dedupKey} already exists,
     * returns the existing persisted notification without creating a duplicate row.
     */
    public Notification save(Notification notification) {
        try (Connection conn = databaseManager.getConnection()) {
            return save(notification, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to save notification: " + e.getMessage(), e);
        }
    }

    public Notification save(Notification notification, Connection conn) throws SQLException {
        if (notification == null) {
            throw new IllegalArgumentException("Notification cannot be null");
        }
        String sql = "INSERT OR IGNORE INTO notifications " +
                "(notification_id, user_id, transaction_id, reference_number, type, title, message, " +
                "is_read, dedup_key, created_at, read_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, notification.getNotificationId());
            ps.setString(2, notification.getUserId());
            ps.setString(3, notification.getTransactionId());
            ps.setString(4, notification.getReferenceNumber());
            ps.setString(5, notification.getType().name());
            ps.setString(6, notification.getTitle());
            ps.setString(7, notification.getMessage());
            ps.setInt(8, notification.isRead() ? 1 : 0);
            ps.setString(9, notification.getDedupKey());
            LocalDateTime created = notification.getCreatedAt() != null
                    ? notification.getCreatedAt()
                    : LocalDateTime.now();
            notification.setCreatedAt(created);
            ps.setString(10, created.toString());
            ps.setString(11, notification.getReadAt() != null ? notification.getReadAt().toString() : null);
            int inserted = ps.executeUpdate();
            if (inserted == 0 && notification.getDedupKey() != null) {
                return findByDedupKey(notification.getDedupKey(), conn).orElse(notification);
            }
            return notification;
        }
    }

    public Optional<Notification> findById(String notificationId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findById(notificationId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query notification by ID: " + e.getMessage(), e);
        }
    }

    public Optional<Notification> findById(String notificationId, Connection conn) throws SQLException {
        String sql = "SELECT notification_id, user_id, transaction_id, reference_number, type, title, message, " +
                "is_read, dedup_key, created_at, read_at FROM notifications WHERE notification_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, notificationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Notification> findByDedupKey(String dedupKey) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByDedupKey(dedupKey, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query notification by dedup key: " + e.getMessage(), e);
        }
    }

    public Optional<Notification> findByDedupKey(String dedupKey, Connection conn) throws SQLException {
        String sql = "SELECT notification_id, user_id, transaction_id, reference_number, type, title, message, " +
                "is_read, dedup_key, created_at, read_at FROM notifications WHERE dedup_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dedupKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<Notification> findByUserId(String userId, boolean unreadOnly) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByUserId(userId, unreadOnly, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query notifications for user: " + e.getMessage(), e);
        }
    }

    public List<Notification> findByUserId(String userId, boolean unreadOnly, Connection conn) throws SQLException {
        String sql = "SELECT notification_id, user_id, transaction_id, reference_number, type, title, message, " +
                "is_read, dedup_key, created_at, read_at FROM notifications " +
                "WHERE user_id = ?" + (unreadOnly ? " AND is_read = 0" : "") +
                " ORDER BY created_at DESC, notification_id DESC";
        List<Notification> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        }
        return results;
    }

    public List<Notification> findByTransactionId(String transactionId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByTransactionId(transactionId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query notifications by transaction ID: " + e.getMessage(), e);
        }
    }

    public List<Notification> findByTransactionId(String transactionId, Connection conn) throws SQLException {
        String sql = "SELECT notification_id, user_id, transaction_id, reference_number, type, title, message, " +
                "is_read, dedup_key, created_at, read_at FROM notifications " +
                "WHERE transaction_id = ? ORDER BY created_at DESC, notification_id DESC";
        List<Notification> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        }
        return results;
    }

    public int countUnreadByUserId(String userId) {
        try (Connection conn = databaseManager.getConnection()) {
            return countUnreadByUserId(userId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to count unread notifications: " + e.getMessage(), e);
        }
    }

    public int countUnreadByUserId(String userId, Connection conn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public boolean markAsRead(String notificationId, String ownerUserId) {
        try (Connection conn = databaseManager.getConnection()) {
            return markAsRead(notificationId, ownerUserId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to mark notification as read: " + e.getMessage(), e);
        }
    }

    public boolean markAsRead(String notificationId, String ownerUserId, Connection conn) throws SQLException {
        String sql = "UPDATE notifications SET is_read = 1, read_at = COALESCE(read_at, ?) " +
                "WHERE notification_id = ? AND user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, LocalDateTime.now().toString());
            ps.setString(2, notificationId);
            ps.setString(3, ownerUserId);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean markAsUnread(String notificationId, String ownerUserId) {
        try (Connection conn = databaseManager.getConnection()) {
            return markAsUnread(notificationId, ownerUserId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to mark notification as unread: " + e.getMessage(), e);
        }
    }

    public boolean markAsUnread(String notificationId, String ownerUserId, Connection conn) throws SQLException {
        String sql = "UPDATE notifications SET is_read = 0, read_at = NULL " +
                "WHERE notification_id = ? AND user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, notificationId);
            ps.setString(2, ownerUserId);
            return ps.executeUpdate() > 0;
        }
    }

    public int markAllAsRead(String ownerUserId) {
        try (Connection conn = databaseManager.getConnection()) {
            return markAllAsRead(ownerUserId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to mark all notifications as read: " + e.getMessage(), e);
        }
    }

    public int markAllAsRead(String ownerUserId, Connection conn) throws SQLException {
        String sql = "UPDATE notifications SET is_read = 1, read_at = COALESCE(read_at, ?) " +
                "WHERE user_id = ? AND is_read = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, LocalDateTime.now().toString());
            ps.setString(2, ownerUserId);
            return ps.executeUpdate();
        }
    }

    private Notification mapRow(ResultSet rs) throws SQLException {
        Notification n = new Notification();
        n.setNotificationId(rs.getString("notification_id"));
        n.setUserId(rs.getString("user_id"));
        n.setTransactionId(rs.getString("transaction_id"));
        n.setReferenceNumber(rs.getString("reference_number"));
        String rawType = rs.getString("type");
        try {
            n.setType(rawType != null ? NotificationType.valueOf(rawType) : NotificationType.PAYMENT_UPDATE);
        } catch (IllegalArgumentException ex) {
            n.setType(NotificationType.PAYMENT_UPDATE);
        }
        n.setTitle(rs.getString("title"));
        n.setMessage(rs.getString("message"));
        n.setRead(rs.getInt("is_read") != 0);
        n.setDedupKey(rs.getString("dedup_key"));
        String created = rs.getString("created_at");
        if (created != null) {
            n.setCreatedAt(LocalDateTime.parse(created));
        }
        String readAt = rs.getString("read_at");
        if (readAt != null) {
            n.setReadAt(LocalDateTime.parse(readAt));
        }
        return n;
    }
}
