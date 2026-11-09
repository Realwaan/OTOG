package com.otog.repository;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.Wallet;
import com.otog.model.WalletStatus;
import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for persistent Wallet entities in SQLite.
 */
public class WalletRepository {
    private final DatabaseManager databaseManager;

    public WalletRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Wallet save(Wallet wallet) {
        try (Connection conn = databaseManager.getConnection()) {
            return save(wallet, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to save wallet: " + e.getMessage(), e);
        }
    }

    public Wallet save(Wallet wallet, Connection conn) throws SQLException {
        String sql = "INSERT INTO wallets (wallet_id, user_id, balance, currency, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, wallet.getWalletId());
            ps.setString(2, wallet.getUserId());
            ps.setString(3, wallet.getBalance().toPlainString());
            ps.setString(4, wallet.getCurrency());
            ps.setString(5, wallet.getStatus().name());
            ps.setString(6, wallet.getCreatedAt().toString());
            ps.setString(7, wallet.getUpdatedAt().toString());
            ps.executeUpdate();
            return wallet;
        }
    }

    public void update(Wallet wallet) {
        try (Connection conn = databaseManager.getConnection()) {
            update(wallet, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to update wallet: " + e.getMessage(), e);
        }
    }

    public void update(Wallet wallet, Connection conn) throws SQLException {
        String sql = "UPDATE wallets SET balance = ?, currency = ?, status = ?, updated_at = ? WHERE wallet_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, wallet.getBalance().toPlainString());
            ps.setString(2, wallet.getCurrency());
            ps.setString(3, wallet.getStatus().name());
            ps.setString(4, LocalDateTime.now().toString());
            ps.setString(5, wallet.getWalletId());
            ps.executeUpdate();
        }
    }

    public void updateBalance(String walletId, BigDecimal newBalance, Connection conn) throws SQLException {
        String sql = "UPDATE wallets SET balance = ?, updated_at = ? WHERE wallet_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            BigDecimal scaled = newBalance.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
            ps.setString(1, scaled.toPlainString());
            ps.setString(2, LocalDateTime.now().toString());
            ps.setString(3, walletId);
            ps.executeUpdate();
        }
    }

    public Optional<Wallet> findById(String walletId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findById(walletId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query wallet by ID: " + e.getMessage(), e);
        }
    }

    public Optional<Wallet> findById(String walletId, Connection conn) throws SQLException {
        String sql = "SELECT wallet_id, user_id, balance, currency, status, created_at, updated_at " +
                "FROM wallets WHERE wallet_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Wallet> findByUserId(String userId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByUserId(userId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query wallet by user ID: " + e.getMessage(), e);
        }
    }

    public Optional<Wallet> findByUserId(String userId, Connection conn) throws SQLException {
        String sql = "SELECT wallet_id, user_id, balance, currency, status, created_at, updated_at " +
                "FROM wallets WHERE user_id = ?";
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

    public List<Wallet> findAll() {
        try (Connection conn = databaseManager.getConnection()) {
            return findAll(conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query all wallets: " + e.getMessage(), e);
        }
    }

    public List<Wallet> findAll(Connection conn) throws SQLException {
        String sql = "SELECT wallet_id, user_id, balance, currency, status, created_at, updated_at FROM wallets";
        List<Wallet> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    private Wallet mapRow(ResultSet rs) throws SQLException {
        Wallet w = new Wallet();
        w.setWalletId(rs.getString("wallet_id"));
        w.setUserId(rs.getString("user_id"));
        w.setBalance(new BigDecimal(rs.getString("balance")).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        w.setCurrency(rs.getString("currency"));
        w.setStatus(WalletStatus.valueOf(rs.getString("status")));
        w.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        w.setUpdatedAt(LocalDateTime.parse(rs.getString("updated_at")));
        return w;
    }
}
