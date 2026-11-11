package com.otog.repository;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.UserStatus;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for managing persistent User records in SQLite.
 */
public class UserRepository {
    private final DatabaseManager databaseManager;

    public UserRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public User save(User user) {
        try (Connection conn = databaseManager.getConnection()) {
            return save(user, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to save user: " + e.getMessage(), e);
        }
    }

    public User save(User user, Connection conn) throws SQLException {
        String sql = "INSERT INTO users (user_id, name, mobile_number, email, role, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user.getUserId());
            ps.setString(2, user.getName());
            ps.setString(3, user.getMobileNumber());
            ps.setString(4, user.getEmail());
            ps.setString(5, user.getRole().name());
            ps.setString(6, user.getStatus().name());
            ps.setString(7, user.getCreatedAt().toString());
            ps.setString(8, user.getUpdatedAt().toString());
            ps.executeUpdate();
            return user;
        }
    }

    public void update(User user) {
        try (Connection conn = databaseManager.getConnection()) {
            update(user, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to update user: " + e.getMessage(), e);
        }
    }

    public void update(User user, Connection conn) throws SQLException {
        String sql = "UPDATE users SET name = ?, mobile_number = ?, email = ?, role = ?, status = ?, updated_at = ? " +
                "WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user.getName());
            ps.setString(2, user.getMobileNumber());
            ps.setString(3, user.getEmail());
            ps.setString(4, user.getRole().name());
            ps.setString(5, user.getStatus().name());
            ps.setString(6, LocalDateTime.now().toString());
            ps.setString(7, user.getUserId());
            ps.executeUpdate();
        }
    }

    public Optional<User> findById(String userId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findById(userId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query user by ID: " + e.getMessage(), e);
        }
    }

    public Optional<User> findById(String userId, Connection conn) throws SQLException {
        String sql = "SELECT user_id, name, mobile_number, email, role, status, created_at, updated_at " +
                "FROM users WHERE user_id = ?";
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

    public Optional<User> findByMobileNumber(String mobileNumber) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByMobileNumber(mobileNumber, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query user by mobile: " + e.getMessage(), e);
        }
    }

    public Optional<User> findByMobileNumber(String mobileNumber, Connection conn) throws SQLException {
        String sql = "SELECT user_id, name, mobile_number, email, role, status, created_at, updated_at " +
                "FROM users WHERE mobile_number = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, mobileNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<User> findAll() {
        try (Connection conn = databaseManager.getConnection()) {
            return findAll(conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query all users: " + e.getMessage(), e);
        }
    }

    public List<User> findAll(Connection conn) throws SQLException {
        String sql = "SELECT user_id, name, mobile_number, email, role, status, created_at, updated_at FROM users";
        List<User> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    private User mapRow(ResultSet rs) throws SQLException {
        User u = new User();
        u.setUserId(rs.getString("user_id"));
        u.setName(rs.getString("name"));
        u.setMobileNumber(rs.getString("mobile_number"));
        u.setEmail(rs.getString("email"));
        u.setRole(UserRole.valueOf(rs.getString("role")));
        u.setStatus(UserStatus.valueOf(rs.getString("status")));
        u.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        u.setUpdatedAt(LocalDateTime.parse(rs.getString("updated_at")));
        return u;
    }
}
