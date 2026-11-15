package com.otog.repository;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.Credential;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

public class CredentialRepository {
    private final DatabaseManager databaseManager;

    public CredentialRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Credential save(Credential credential) {
        try (Connection conn = databaseManager.getConnection()) {
            return save(credential, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to save credentials: " + e.getMessage(), e);
        }
    }

    public Credential save(Credential credential, Connection conn) throws SQLException {
        String sql = "INSERT INTO credentials (user_id, password_hash, salt, failed_attempts, locked_until, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, credential.getUserId());
            ps.setString(2, credential.getPasswordHash());
            ps.setString(3, credential.getSalt());
            ps.setInt(4, credential.getFailedAttempts());
            ps.setString(5, credential.getLockedUntil() != null ? credential.getLockedUntil().toString() : null);
            ps.setString(6, credential.getUpdatedAt().toString());
            ps.executeUpdate();
            return credential;
        }
    }

    public Optional<Credential> findByUserId(String userId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByUserId(userId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query credentials for user: " + e.getMessage(), e);
        }
    }

    public Optional<Credential> findByUserId(String userId, Connection conn) throws SQLException {
        String sql = "SELECT user_id, password_hash, salt, failed_attempts, locked_until, updated_at " +
                "FROM credentials WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public int recordFailedAttempt(String userId) {
        try (Connection conn = databaseManager.getConnection()) {
            String sql = "UPDATE credentials SET failed_attempts = failed_attempts + 1, updated_at = ? " +
                    "WHERE user_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, LocalDateTime.now().toString());
                ps.setString(2, userId);
                ps.executeUpdate();
            }

            // Return current failed attempts
            String query = "SELECT failed_attempts FROM credentials WHERE user_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("failed_attempts");
                    }
                }
            }
            return 0;
        } catch (SQLException e) {
            throw new OtogException("Failed to update failed login attempts: " + e.getMessage(), e);
        }
    }

    public void resetFailedAttempts(String userId) {
        try (Connection conn = databaseManager.getConnection()) {
            String sql = "UPDATE credentials SET failed_attempts = 0, updated_at = ? WHERE user_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, LocalDateTime.now().toString());
                ps.setString(2, userId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new OtogException("Failed to reset failed login attempts: " + e.getMessage(), e);
        }
    }

    public void update(Credential credential) {
        try (Connection conn = databaseManager.getConnection()) {
            String sql = "UPDATE credentials SET password_hash = ?, salt = ?, failed_attempts = ?, " +
                    "locked_until = ?, updated_at = ? WHERE user_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, credential.getPasswordHash());
                ps.setString(2, credential.getSalt());
                ps.setInt(3, credential.getFailedAttempts());
                ps.setString(4, credential.getLockedUntil() != null ? credential.getLockedUntil().toString() : null);
                ps.setString(5, LocalDateTime.now().toString());
                ps.setString(6, credential.getUserId());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new OtogException("Failed to update credentials: " + e.getMessage(), e);
        }
    }

    private Credential mapRow(ResultSet rs) throws SQLException {
        Credential c = new Credential();
        c.setUserId(rs.getString("user_id"));
        c.setPasswordHash(rs.getString("password_hash"));
        c.setSalt(rs.getString("salt"));
        c.setFailedAttempts(rs.getInt("failed_attempts"));
        String lockedUntilStr = rs.getString("locked_until");
        if (lockedUntilStr != null && !lockedUntilStr.isEmpty()) {
            c.setLockedUntil(LocalDateTime.parse(lockedUntilStr));
        }
        c.setUpdatedAt(LocalDateTime.parse(rs.getString("updated_at")));
        return c;
    }
}
