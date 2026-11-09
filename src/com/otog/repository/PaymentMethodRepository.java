package com.otog.repository;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.PaymentMethod;
import com.otog.model.PaymentMethodStatus;
import com.otog.model.PaymentMethodType;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for managing persistent PaymentMethod records in SQLite.
 */
public class PaymentMethodRepository {
    private final DatabaseManager databaseManager;

    public PaymentMethodRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public PaymentMethod save(PaymentMethod pm) {
        try (Connection conn = databaseManager.getConnection()) {
            return save(pm, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to save payment method: " + e.getMessage(), e);
        }
    }

    public PaymentMethod save(PaymentMethod pm, Connection conn) throws SQLException {
        String sql = "INSERT INTO payment_methods (payment_method_id, user_id, type, provider_name, " +
                "account_name, account_number, is_default, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pm.getPaymentMethodId());
            ps.setString(2, pm.getUserId());
            ps.setString(3, pm.getType().name());
            ps.setString(4, pm.getProviderName());
            ps.setString(5, pm.getAccountName());
            ps.setString(6, pm.getAccountNumber());
            ps.setInt(7, pm.isDefault() ? 1 : 0);
            ps.setString(8, pm.getStatus().name());
            ps.setString(9, pm.getCreatedAt().toString());
            ps.executeUpdate();
            return pm;
        }
    }

    public void update(PaymentMethod pm) {
        try (Connection conn = databaseManager.getConnection()) {
            update(pm, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to update payment method: " + e.getMessage(), e);
        }
    }

    public void update(PaymentMethod pm, Connection conn) throws SQLException {
        String sql = "UPDATE payment_methods SET type = ?, provider_name = ?, account_name = ?, " +
                "account_number = ?, is_default = ?, status = ? WHERE payment_method_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pm.getType().name());
            ps.setString(2, pm.getProviderName());
            ps.setString(3, pm.getAccountName());
            ps.setString(4, pm.getAccountNumber());
            ps.setInt(5, pm.isDefault() ? 1 : 0);
            ps.setString(6, pm.getStatus().name());
            ps.setString(7, pm.getPaymentMethodId());
            ps.executeUpdate();
        }
    }

    public Optional<PaymentMethod> findById(String paymentMethodId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findById(paymentMethodId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query payment method by ID: " + e.getMessage(), e);
        }
    }

    public Optional<PaymentMethod> findById(String paymentMethodId, Connection conn) throws SQLException {
        String sql = "SELECT payment_method_id, user_id, type, provider_name, account_name, account_number, " +
                "is_default, status, created_at FROM payment_methods WHERE payment_method_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, paymentMethodId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<PaymentMethod> findByUserId(String userId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByUserId(userId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query payment methods by user ID: " + e.getMessage(), e);
        }
    }

    public List<PaymentMethod> findByUserId(String userId, Connection conn) throws SQLException {
        String sql = "SELECT payment_method_id, user_id, type, provider_name, account_name, account_number, " +
                "is_default, status, created_at FROM payment_methods WHERE user_id = ?";
        List<PaymentMethod> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public Optional<PaymentMethod> findDefaultByUserId(String userId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findDefaultByUserId(userId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query default payment method: " + e.getMessage(), e);
        }
    }

    public Optional<PaymentMethod> findDefaultByUserId(String userId, Connection conn) throws SQLException {
        String sql = "SELECT payment_method_id, user_id, type, provider_name, account_name, account_number, " +
                "is_default, status, created_at FROM payment_methods WHERE user_id = ? AND is_default = 1";
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

    private PaymentMethod mapRow(ResultSet rs) throws SQLException {
        PaymentMethod pm = new PaymentMethod();
        pm.setPaymentMethodId(rs.getString("payment_method_id"));
        pm.setUserId(rs.getString("user_id"));
        pm.setType(PaymentMethodType.valueOf(rs.getString("type")));
        pm.setProviderName(rs.getString("provider_name"));
        pm.setAccountName(rs.getString("account_name"));
        pm.setAccountNumber(rs.getString("account_number"));
        pm.setDefault(rs.getInt("is_default") == 1);
        pm.setStatus(PaymentMethodStatus.valueOf(rs.getString("status")));
        pm.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        return pm;
    }
}
