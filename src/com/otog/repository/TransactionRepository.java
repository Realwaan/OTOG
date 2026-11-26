package com.otog.repository;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.BillPaymentTransaction;
import com.otog.model.CashInOutTransaction;
import com.otog.model.InvestMoneyTransaction;
import com.otog.model.LoanTransaction;
import com.otog.model.SendMoneyTransaction;
import com.otog.model.Transaction;
import com.otog.model.TransactionFactory;
import com.otog.model.TransactionFilter;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
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
        String channel = null;
        String billerReference = null;
        if (txn instanceof CashInOutTransaction cio) {
            channel = cio.getChannel();
        } else if (txn instanceof BillPaymentTransaction bpt) {
            channel = bpt.getBillerName();
            billerReference = bpt.getBillerReference();
        }
        String product = null;
        String units = null;
        String loanId = null;
        String principal = null;
        String interestRate = null;
        Integer termMonths = null;
        String dueDate = null;
        if (txn instanceof InvestMoneyTransaction imt) {
            product = imt.getProduct();
            units = imt.getUnits() != null ? imt.getUnits().toPlainString() : null;
        } else if (txn instanceof LoanTransaction lt) {
            product = lt.getProductName();
            loanId = lt.getLoanId();
            principal = lt.getPrincipal() != null ? lt.getPrincipal().toPlainString() : null;
            interestRate = lt.getInterestRate() != null ? lt.getInterestRate().toPlainString() : null;
            termMonths = lt.getTermMonths() > 0 ? lt.getTermMonths() : null;
            dueDate = lt.getDueDate() != null ? lt.getDueDate().toString() : null;
        }
        String sql = "INSERT INTO transactions (transaction_id, reference_number, source_wallet_id, " +
                "target_wallet_id, amount, fee, total_amount, currency, type, status, description, " +
                "created_at, completed_at, provider_reference, provider_status, channel, biller_reference, " +
                "product, units, loan_id, principal, interest_rate, term_months, due_date) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
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
            ps.setString(14, txn.getProviderReference());
            ps.setString(15, txn.getProviderStatus());
            ps.setString(16, channel);
            ps.setString(17, billerReference);
            ps.setString(18, product);
            ps.setString(19, units);
            ps.setString(20, loanId);
            ps.setString(21, principal);
            ps.setString(22, interestRate);
            if (termMonths != null) {
                ps.setInt(23, termMonths);
            } else {
                ps.setNull(23, Types.INTEGER);
            }
            ps.setString(24, dueDate);
            ps.executeUpdate();
            return txn;
        }
    }

    public void updateLoanTransactionOutcome(String transactionId,
                                             TransactionStatus status,
                                             String providerStatus,
                                             LocalDate dueDate,
                                             LocalDateTime completedAt,
                                             Connection conn) throws SQLException {
        String sql = "UPDATE transactions SET status = ?, " +
                "provider_status = COALESCE(?, provider_status), " +
                "due_date = COALESCE(?, due_date), " +
                "completed_at = ? WHERE transaction_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, providerStatus);
            ps.setString(3, dueDate != null ? dueDate.toString() : null);
            ps.setString(4, completedAt != null ? completedAt.toString() : null);
            ps.setString(5, transactionId);
            ps.executeUpdate();
        }
    }

    public void updateStatus(String transactionId, TransactionStatus status, LocalDateTime completedAt, Connection conn) throws SQLException {
        updateStatus(transactionId, status, null, null, completedAt, conn);
    }

    public void updateStatus(String transactionId, TransactionStatus status, String providerReference, String providerStatus, LocalDateTime completedAt, Connection conn) throws SQLException {
        String sql = "UPDATE transactions SET status = ?, " +
                "provider_reference = COALESCE(?, provider_reference), " +
                "provider_status = COALESCE(?, provider_status), " +
                "completed_at = ? WHERE transaction_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, providerReference);
            ps.setString(3, providerStatus);
            ps.setString(4, completedAt != null ? completedAt.toString() : null);
            ps.setString(5, transactionId);
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
                "amount, fee, total_amount, currency, type, status, description, created_at, completed_at, " +
                "provider_reference, provider_status, channel, biller_reference, product, units, " +
                "loan_id, principal, interest_rate, term_months, due_date " +
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
                "amount, fee, total_amount, currency, type, status, description, created_at, completed_at, " +
                "provider_reference, provider_status, channel, biller_reference, product, units, " +
                "loan_id, principal, interest_rate, term_months, due_date " +
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
                "amount, fee, total_amount, currency, type, status, description, created_at, completed_at, " +
                "provider_reference, provider_status, channel, biller_reference, product, units, " +
                "loan_id, principal, interest_rate, term_months, due_date " +
                "FROM transactions WHERE source_wallet_id = ? OR target_wallet_id = ? " +
                "ORDER BY created_at DESC, transaction_id DESC, reference_number ASC";
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

    /**
     * Returns at most {@code limit} of the newest transactions touching a wallet.
     * The cap is applied by SQLite ({@code LIMIT ?}) so the full ledger is never loaded into memory.
     */
    public List<Transaction> findRecentByWalletId(String walletId, int limit) {
        try (Connection conn = databaseManager.getConnection()) {
            return findRecentByWalletId(walletId, limit, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query recent transactions for wallet: " + e.getMessage(), e);
        }
    }

    public List<Transaction> findRecentByWalletId(String walletId, int limit, Connection conn) throws SQLException {
        String sql = "SELECT transaction_id, reference_number, source_wallet_id, target_wallet_id, " +
                "amount, fee, total_amount, currency, type, status, description, created_at, completed_at, " +
                "provider_reference, provider_status, channel, biller_reference, product, units, " +
                "loan_id, principal, interest_rate, term_months, due_date " +
                "FROM transactions WHERE source_wallet_id = ? OR target_wallet_id = ? " +
                "ORDER BY created_at DESC, transaction_id DESC, reference_number ASC LIMIT ?";
        List<Transaction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, walletId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    /**
     * Queries owned transactions for a wallet matching optional date range, type, status, and keyword filters.
     * Uses parameterized SQL predicates and deterministic newest-first ordering.
     */
    public List<Transaction> findByWalletIdFiltered(String walletId, TransactionFilter filter) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByWalletIdFiltered(walletId, filter, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query filtered transactions for wallet: " + e.getMessage(), e);
        }
    }

    public List<Transaction> findByWalletIdFiltered(String walletId, TransactionFilter filter, Connection conn)
            throws SQLException {
        TransactionFilter effective = filter != null ? filter : TransactionFilter.empty();
        StringBuilder sql = new StringBuilder(
                "SELECT transaction_id, reference_number, source_wallet_id, target_wallet_id, " +
                        "amount, fee, total_amount, currency, type, status, description, created_at, completed_at, " +
                        "provider_reference, provider_status, channel, biller_reference, product, units, " +
                "loan_id, principal, interest_rate, term_months, due_date " +
                        "FROM transactions WHERE (source_wallet_id = ? OR target_wallet_id = ?)"
        );
        List<Object> params = new ArrayList<>();
        params.add(walletId);
        params.add(walletId);

        if (effective.getType() != null) {
            sql.append(" AND type = ?");
            params.add(effective.getType().name());
        }
        if (effective.getStatus() != null) {
            sql.append(" AND status = ?");
            params.add(effective.getStatus().name());
        }
        if (effective.getFromDate() != null) {
            sql.append(" AND created_at >= ?");
            params.add(effective.getFromDate().atStartOfDay().toString());
        }
        if (effective.getToDate() != null) {
            sql.append(" AND created_at < ?");
            params.add(effective.getToDate().plusDays(1).atStartOfDay().toString());
        }
        if (effective.getKeyword() != null) {
            sql.append(" AND (LOWER(reference_number) LIKE ? ESCAPE '\\' " +
                    "OR LOWER(COALESCE(provider_reference, '')) LIKE ? ESCAPE '\\' " +
                    "OR LOWER(COALESCE(description, '')) LIKE ? ESCAPE '\\' " +
                    "OR LOWER(COALESCE(biller_reference, '')) LIKE ? ESCAPE '\\' " +
                    "OR LOWER(COALESCE(channel, '')) LIKE ? ESCAPE '\\' " +
                    "OR LOWER(COALESCE(product, '')) LIKE ? ESCAPE '\\')");
            String escapedKeyword = effective.getKeyword()
                    .toLowerCase(java.util.Locale.ROOT)
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            String likePattern = "%" + escapedKeyword + "%";
            params.add(likePattern);
            params.add(likePattern);
            params.add(likePattern);
            params.add(likePattern);
            params.add(likePattern);
            params.add(likePattern);
        }

        sql.append(" ORDER BY created_at DESC, transaction_id DESC, reference_number ASC");
        if (effective.getLimit() > 0) {
            sql.append(" LIMIT ?");
            params.add(effective.getLimit());
        }

        List<Transaction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof Integer intVal) {
                    ps.setInt(i + 1, intVal);
                } else {
                    ps.setString(i + 1, param.toString());
                }
            }
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
                "amount, fee, total_amount, currency, type, status, description, created_at, completed_at, " +
                "provider_reference, provider_status, channel, biller_reference, product, units, " +
                "loan_id, principal, interest_rate, term_months, due_date " +
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
        TransactionType type = TransactionType.valueOf(rs.getString("type"));
        Transaction t = TransactionFactory.createEmptyForType(type);
        if (t instanceof CashInOutTransaction cio) {
            cio.setCashOperationType(type == TransactionType.CASH_IN ? "CASH_IN" : "CASH_OUT");
            cio.setChannel(rs.getString("channel"));
        } else if (t instanceof SendMoneyTransaction smt) {
            smt.setRecipientNumber(rs.getString("target_wallet_id"));
            smt.setNote(rs.getString("description"));
        } else if (t instanceof InvestMoneyTransaction imt) {
            String product = rs.getString("product");
            if (product != null && !product.isBlank()) {
                imt.setProduct(product);
            }
            String unitsStr = rs.getString("units");
            if (unitsStr != null && !unitsStr.isBlank()) {
                imt.setUnits(new BigDecimal(unitsStr));
            }
        } else if (t instanceof BillPaymentTransaction bpt) {
            bpt.setBillerName(rs.getString("channel"));
            String billerRef = rs.getString("biller_reference");
            if (billerRef == null || billerRef.trim().isEmpty()) {
                billerRef = extractBillerReference(rs.getString("description"));
            }
            bpt.setBillerReference(billerRef != null ? billerRef.trim() : null);
        } else if (t instanceof LoanTransaction lt) {
            String product = rs.getString("product");
            if (product != null && !product.isBlank()) {
                lt.setProductName(product);
            }
            lt.setLoanId(rs.getString("loan_id"));
            String principalStr = rs.getString("principal");
            if (principalStr != null && !principalStr.isBlank()) {
                lt.setPrincipal(new BigDecimal(principalStr).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
            } else {
                lt.setPrincipal(new BigDecimal(rs.getString("amount")).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
            }
            String rateStr = rs.getString("interest_rate");
            if (rateStr != null && !rateStr.isBlank()) {
                lt.setInterestRate(new BigDecimal(rateStr));
            }
            int term = rs.getInt("term_months");
            if (!rs.wasNull()) {
                lt.setTermMonths(term);
            }
            String dueStr = rs.getString("due_date");
            if (dueStr != null && !dueStr.isBlank()) {
                lt.setDueDate(LocalDate.parse(dueStr));
            }
        }
        t.setTransactionId(rs.getString("transaction_id"));
        t.setReferenceNumber(rs.getString("reference_number"));
        t.setSourceWalletId(rs.getString("source_wallet_id"));
        t.setTargetWalletId(rs.getString("target_wallet_id"));
        t.setAmount(new BigDecimal(rs.getString("amount")).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        t.setFee(new BigDecimal(rs.getString("fee")).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        t.setTotalAmount(new BigDecimal(rs.getString("total_amount")).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        t.setCurrency(rs.getString("currency"));
        t.setType(type);
        t.setStatus(TransactionStatus.valueOf(rs.getString("status")));
        t.setDescription(rs.getString("description"));
        t.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        String comp = rs.getString("completed_at");
        if (comp != null) {
            t.setCompletedAt(LocalDateTime.parse(comp));
        }
        t.setProviderReference(rs.getString("provider_reference"));
        t.setProviderStatus(rs.getString("provider_status"));
        return t;
    }

    private String extractBillerReference(String description) {
        if (description == null) {
            return null;
        }
        int start = description.lastIndexOf('[');
        int end = description.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return description.substring(start + 1, end).trim();
        }
        return description;
    }
}
