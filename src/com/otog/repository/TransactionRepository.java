package com.otog.repository;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.Transaction;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for persistent Transaction records in SQLite.
 */
public class TransactionRepository {
    private final DatabaseManager databaseManager;

    public TransactionRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Transaction save(Transaction txn) {
        try (Connection conn = databaseManager.getConnection()) {
            return save(txn, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to save transaction: " + e.getMessage(), e);
        }
    }

    public Transaction save(Transaction txn, Connection conn) throws SQLException {
        String sql = "INSERT INTO transactions (transaction_id, reference_number, source_wallet_id, " +
                "target_wallet_id, amount, fee, total_amount, currency, type, status, description, " +
                "created_at, completed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, txn.getTransactionId());
            ps.setString(2, txn.getReferenceNumber());
            ps.setString(3, txn.getSourceWalletId());
            ps.setString(4, txn.getTargetWalletId());
            ps.setString(5, txn.getAmount().toPlainString());
            ps.setString(6, txn.getFee().toPlainString());
            ps.setString(7, txn.getTotalAmount().toPlainString());
            ps.setString(8, txn.getCurrency());
            ps.setString(9, txn.getType().name());
            ps.setString(10, txn.getStatus().name());
            ps.setString(11, txn.getDescription());
            ps.setString(12, txn.getCreatedAt().toString());
            ps.setString(13, txn.getCompletedAt() != null ? txn.getCompletedAt().toString() : null);
            ps.executeUpdate();
            return txn;
        }
    }

    public void updateStatus(String transactionId, TransactionStatus status, LocalDateTime completedAt, Connection conn) throws SQLException {
        String sql = "UPDATE transactions SET status = ?, completed_at = ? WHERE transaction_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, completedAt != null ? completedAt.toString() : null);
            ps.setString(3, transactionId);
            ps.executeUpdate();
        }
    }

    public Optional<Transaction> findById(String transactionId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findById(transactionId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query transaction by ID: " + e.getMessage(), e);
        }
    }

    public Optional<Transaction> findById(String transactionId, Connection conn) throws SQLException {
        String sql = "SELECT transaction_id, reference_number, source_wallet_id, target_wallet_id, " +
                "amount, fee, total_amount, currency, type, status, description, created_at, completed_at " +
                "FROM transactions WHERE transaction_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Transaction> findByReferenceNumber(String refNum) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByReferenceNumber(refNum, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query transaction by reference number: " + e.getMessage(), e);
        }
    }

    public Optional<Transaction> findByReferenceNumber(String refNum, Connection conn) throws SQLException {
        String sql = "SELECT transaction_id, reference_number, source_wallet_id, target_wallet_id, " +
                "amount, fee, total_amount, currency, type, status, description, created_at, completed_at " +
                "FROM transactions WHERE reference_number = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, refNum);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public boolean existsByReferenceNumber(String refNum) {
        try (Connection conn = databaseManager.getConnection()) {
            return existsByReferenceNumber(refNum, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to check reference number: " + e.getMessage(), e);
        }
    }

    public boolean existsByReferenceNumber(String refNum, Connection conn) throws SQLException {
        String sql = "SELECT 1 FROM transactions WHERE reference_number = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, refNum);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<Transaction> findByWalletId(String walletId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByWalletId(walletId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query transactions for wallet: " + e.getMessage(), e);
        }
    }

    public List<Transaction> findByWalletId(String walletId, Connection conn) throws SQLException {
        String sql = "SELECT transaction_id, reference_number, source_wallet_id, target_wallet_id, " +
                "amount, fee, total_amount, currency, type, status, description, created_at, completed_at " +
                "FROM transactions WHERE source_wallet_id = ? OR target_wallet_id = ? ORDER BY created_at DESC";
        List<Transaction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<Transaction> findAll() {
        try (Connection conn = databaseManager.getConnection()) {
            return findAll(conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query all transactions: " + e.getMessage(), e);
        }
    }

    public List<Transaction> findAll(Connection conn) throws SQLException {
        String sql = "SELECT transaction_id, reference_number, source_wallet_id, target_wallet_id, " +
                "amount, fee, total_amount, currency, type, status, description, created_at, completed_at " +
                "FROM transactions ORDER BY created_at DESC";
        List<Transaction> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    private Transaction mapRow(ResultSet rs) throws SQLException {
        Transaction t = new Transaction();
        t.setTransactionId(rs.getString("transaction_id"));
        t.setReferenceNumber(rs.getString("reference_number"));
        t.setSourceWalletId(rs.getString("source_wallet_id"));
        t.setTargetWalletId(rs.getString("target_wallet_id"));
        t.setAmount(new BigDecimal(rs.getString("amount")).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        t.setFee(new BigDecimal(rs.getString("fee")).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        t.setTotalAmount(new BigDecimal(rs.getString("total_amount")).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        t.setCurrency(rs.getString("currency"));
        t.setType(TransactionType.valueOf(rs.getString("type")));
        t.setStatus(TransactionStatus.valueOf(rs.getString("status")));
        t.setDescription(rs.getString("description"));
        t.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        String comp = rs.getString("completed_at");
        if (comp != null) {
            t.setCompletedAt(LocalDateTime.parse(comp));
        }
        return t;
    }
}
