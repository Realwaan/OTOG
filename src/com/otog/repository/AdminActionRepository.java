package com.otog.repository;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.AdminAction;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting and querying traceable administrative actions in SQLite.
 */
public class AdminActionRepository {
    private final DatabaseManager databaseManager;

    public AdminActionRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public AdminAction save(AdminAction action) {
        try (Connection conn = databaseManager.getConnection()) {
            return save(action, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to save admin action: " + e.getMessage(), e);
        }
    }

    public AdminAction save(AdminAction action, Connection conn) throws SQLException {
        String sql = "INSERT INTO admin_actions (action_id, admin_user_id, action_type, target_user_id, reason, details, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, action.getActionId());
            ps.setString(2, action.getAdminUserId());
            ps.setString(3, action.getActionType());
            ps.setString(4, action.getTargetUserId());
            ps.setString(5, action.getReason());
            ps.setString(6, action.getDetails());
            ps.setString(7, action.getCreatedAt().toString());
            ps.executeUpdate();
            return action;
        }
    }

    public Optional<AdminAction> findById(String actionId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findById(actionId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to find admin action by ID: " + e.getMessage(), e);
        }
    }

    public Optional<AdminAction> findById(String actionId, Connection conn) throws SQLException {
        String sql = "SELECT action_id, admin_user_id, action_type, target_user_id, reason, details, created_at FROM admin_actions WHERE action_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, actionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<AdminAction> findAll() {
        try (Connection conn = databaseManager.getConnection()) {
            return findAll(conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query all admin actions: " + e.getMessage(), e);
        }
    }

    public List<AdminAction> findAll(Connection conn) throws SQLException {
        String sql = "SELECT action_id, admin_user_id, action_type, target_user_id, reason, details, created_at FROM admin_actions ORDER BY created_at DESC";
        List<AdminAction> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public List<AdminAction> findByAdminUserId(String adminUserId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByAdminUserId(adminUserId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query admin actions by admin ID: " + e.getMessage(), e);
        }
    }

    public List<AdminAction> findByAdminUserId(String adminUserId, Connection conn) throws SQLException {
        String sql = "SELECT action_id, admin_user_id, action_type, target_user_id, reason, details, created_at FROM admin_actions WHERE admin_user_id = ? ORDER BY created_at DESC";
        List<AdminAction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, adminUserId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<AdminAction> findByTargetUserId(String targetUserId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByTargetUserId(targetUserId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query admin actions by target user ID: " + e.getMessage(), e);
        }
    }

    public List<AdminAction> findByTargetUserId(String targetUserId, Connection conn) throws SQLException {
        String sql = "SELECT action_id, admin_user_id, action_type, target_user_id, reason, details, created_at FROM admin_actions WHERE target_user_id = ? ORDER BY created_at DESC";
        List<AdminAction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetUserId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    private AdminAction mapRow(ResultSet rs) throws SQLException {
        AdminAction act = new AdminAction();
        act.setActionId(rs.getString("action_id"));
        act.setAdminUserId(rs.getString("admin_user_id"));
        act.setActionType(rs.getString("action_type"));
        act.setTargetUserId(rs.getString("target_user_id"));
        act.setReason(rs.getString("reason"));
        act.setDetails(rs.getString("details"));
        act.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        return act;
    }
}
