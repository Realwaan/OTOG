package com.otog.repository;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.LedgerEntry;
import com.otog.model.LedgerEntryType;
import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for persistent accounting ledger entries in SQLite.
 */
public class LedgerRepository {
    private final DatabaseManager databaseManager;

    public LedgerRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public LedgerEntry save(LedgerEntry entry) {
        try (Connection conn = databaseManager.getConnection()) {
            return save(entry, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to save ledger entry: " + e.getMessage(), e);
        }
    }

    public LedgerEntry save(LedgerEntry entry, Connection conn) throws SQLException {
        String sql = "INSERT INTO ledger_entries (ledger_entry_id, transaction_id, wallet_id, " +
                "entry_type, amount, balance_before, balance_after, currency, description, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.getLedgerEntryId());
            ps.setString(2, entry.getTransactionId());
            ps.setString(3, entry.getWalletId());
            ps.setString(4, entry.getEntryType().name());
            ps.setString(5, entry.getAmount().toPlainString());
            ps.setString(6, entry.getBalanceBefore().toPlainString());
            ps.setString(7, entry.getBalanceAfter().toPlainString());
            ps.setString(8, entry.getCurrency());
            ps.setString(9, entry.getDescription());
            ps.setString(10, entry.getCreatedAt().toString());
            ps.executeUpdate();
            return entry;
        }
    }

    public Optional<LedgerEntry> findById(String ledgerEntryId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findById(ledgerEntryId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query ledger entry by ID: " + e.getMessage(), e);
        }
    }

    public Optional<LedgerEntry> findById(String ledgerEntryId, Connection conn) throws SQLException {
        String sql = "SELECT ledger_entry_id, transaction_id, wallet_id, entry_type, amount, " +
                "balance_before, balance_after, currency, description, created_at " +
                "FROM ledger_entries WHERE ledger_entry_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ledgerEntryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<LedgerEntry> findByWalletId(String walletId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByWalletId(walletId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query ledger entries for wallet: " + e.getMessage(), e);
        }
    }

    public List<LedgerEntry> findByWalletId(String walletId, Connection conn) throws SQLException {
        String sql = "SELECT ledger_entry_id, transaction_id, wallet_id, entry_type, amount, " +
                "balance_before, balance_after, currency, description, created_at " +
                "FROM ledger_entries WHERE wallet_id = ? ORDER BY created_at ASC";
        List<LedgerEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<LedgerEntry> findByTransactionId(String transactionId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByTransactionId(transactionId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query ledger entries for transaction: " + e.getMessage(), e);
        }
    }

    public List<LedgerEntry> findByTransactionId(String transactionId, Connection conn) throws SQLException {
        String sql = "SELECT ledger_entry_id, transaction_id, wallet_id, entry_type, amount, " +
                "balance_before, balance_after, currency, description, created_at " +
                "FROM ledger_entries WHERE transaction_id = ? ORDER BY created_at ASC";
        List<LedgerEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public List<LedgerEntry> findAll() {
        try (Connection conn = databaseManager.getConnection()) {
            return findAll(conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query all ledger entries: " + e.getMessage(), e);
        }
    }

    public List<LedgerEntry> findAll(Connection conn) throws SQLException {
        String sql = "SELECT ledger_entry_id, transaction_id, wallet_id, entry_type, amount, " +
                "balance_before, balance_after, currency, description, created_at " +
                "FROM ledger_entries ORDER BY created_at ASC";
        List<LedgerEntry> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    /**
     * Calculates net wallet balance directly from ledger entries (sum of credits minus sum of debits).
     */
    public BigDecimal calculateBalanceFromLedger(String walletId) {
        try (Connection conn = databaseManager.getConnection()) {
            return calculateBalanceFromLedger(walletId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to calculate ledger balance: " + e.getMessage(), e);
        }
    }

    public BigDecimal calculateBalanceFromLedger(String walletId, Connection conn) throws SQLException {
        List<LedgerEntry> entries = findByWalletId(walletId, conn);
        BigDecimal net = MoneyUtil.ZERO;
        for (LedgerEntry entry : entries) {
            if (entry.getEntryType() == LedgerEntryType.CREDIT) {
                net = net.add(entry.getAmount());
            } else if (entry.getEntryType() == LedgerEntryType.DEBIT) {
                net = net.subtract(entry.getAmount());
            }
        }
        return net.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
    }

    private LedgerEntry mapRow(ResultSet rs) throws SQLException {
        LedgerEntry le = new LedgerEntry();
        le.setLedgerEntryId(rs.getString("ledger_entry_id"));
        le.setTransactionId(rs.getString("transaction_id"));
        le.setWalletId(rs.getString("wallet_id"));
        le.setEntryType(LedgerEntryType.valueOf(rs.getString("entry_type")));
        le.setAmount(new BigDecimal(rs.getString("amount")).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        le.setBalanceBefore(new BigDecimal(rs.getString("balance_before")).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        le.setBalanceAfter(new BigDecimal(rs.getString("balance_after")).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        le.setCurrency(rs.getString("currency"));
        le.setDescription(rs.getString("description"));
        le.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        return le;
    }
}
