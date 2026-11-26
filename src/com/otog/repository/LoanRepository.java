package com.otog.repository;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.LoanStatus;
import com.otog.model.LoanTransaction;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.service.LoanService;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite repository for the single configured simulated loan product and persisted
 * loan application / approval / single-disbursement / full-repayment lifecycle records.
 */
public class LoanRepository {

    private final DatabaseManager databaseManager;

    public LoanRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public LoanService.LoanProduct findConfiguredProduct() {
        try (Connection conn = databaseManager.getConnection()) {
            return findConfiguredProduct(conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to load configured loan product: " + e.getMessage(), e);
        }
    }

    public LoanService.LoanProduct findConfiguredProduct(Connection conn) throws SQLException {
        OtogConfig config = OtogConfig.getInstance();
        String configuredCode = config.getLoanProductCode();

        Optional<ProductRow> dbRow = queryProductByCode(configuredCode, conn);
        if (dbRow.isEmpty()) {
            dbRow = queryFirstProduct(conn);
        }

        String productCode = dbRow.map(ProductRow::productCode).orElse(configuredCode);
        String productName = dbRow.map(ProductRow::productName).orElse(config.getLoanProductName());
        BigDecimal minPrincipal = dbRow.map(ProductRow::minPrincipal).orElse(config.getLoanMinPrincipal());
        BigDecimal maxPrincipal = dbRow.map(ProductRow::maxPrincipal).orElse(config.getLoanMaxPrincipal());
        BigDecimal interestRate = dbRow.map(ProductRow::monthlyInterestRate).orElse(config.getLoanMonthlyInterestRate());
        int termMonths = dbRow.map(ProductRow::termMonths).orElse(config.getLoanTermMonths());
        BigDecimal feeFlat = dbRow.map(ProductRow::processingFeeFlat).orElse(config.getLoanProcessingFeeFlat());
        BigDecimal feePercent = dbRow.map(ProductRow::processingFeePercent).orElse(config.getLoanProcessingFeePercent());
        String currency = dbRow.map(ProductRow::currency).orElse(config.getDefaultCurrency());

        // Honor explicit runtime/test overrides on top of the persisted row while keeping product_code immutable
        if (config.hasOverrideOrSysProp("loan.product.name")) {
            productName = config.getLoanProductName();
        }
        if (config.hasOverrideOrSysProp("loan.min.principal")) {
            minPrincipal = config.getLoanMinPrincipal();
        }
        if (config.hasOverrideOrSysProp("loan.max.principal")) {
            maxPrincipal = config.getLoanMaxPrincipal();
        }
        if (config.hasOverrideOrSysProp("loan.monthly.interest.rate")) {
            interestRate = config.getLoanMonthlyInterestRate();
        }
        if (config.hasOverrideOrSysProp("loan.term.months")) {
            termMonths = config.getLoanTermMonths();
        }
        if (config.hasOverrideOrSysProp("loan.processing.fee.flat")) {
            feeFlat = config.getLoanProcessingFeeFlat();
        }
        if (config.hasOverrideOrSysProp("loan.processing.fee.percent")) {
            feePercent = config.getLoanProcessingFeePercent();
        }
        if (config.hasOverrideOrSysProp("currency.default")) {
            currency = config.getDefaultCurrency();
        }

        BigDecimal scaledMin = minPrincipal.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        BigDecimal scaledMax = maxPrincipal.max(scaledMin).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        BigDecimal scaledFlat = feeFlat.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        String validCurrency = MoneyUtil.validateCurrency(currency);
        BigDecimal ratePct = interestRate.multiply(new BigDecimal("100"))
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        BigDecimal feePct = feePercent.multiply(new BigDecimal("100"))
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);

        String formula = "Interest = Principal x " + ratePct.toPlainString() + "%/mo x "
                + termMonths + " mo (HALF_EVEN); Processing Fee = "
                + MoneyUtil.format(scaledFlat, validCurrency) + " + " + feePct.toPlainString()
                + "% of Principal; Total Repayment = Principal + Interest + Processing Fee";
        String disclaimer = "SIMULATED SINGLE LOAN PRODUCT — Single disbursement on admin approval, "
                + "single full lump-sum repayment (installment scheduling, partial repayment, penalties & advanced eligibility scoring deferred)";

        return new LoanService.LoanProduct(
                productCode,
                productName,
                scaledMin,
                scaledMax,
                interestRate,
                Math.max(1, termMonths),
                scaledFlat,
                feePercent,
                validCurrency,
                formula,
                disclaimer
        );
    }

    public void save(LoanTransaction loan, Connection conn) throws SQLException {
        String sql = "INSERT INTO loans (" +
                "loan_id, application_transaction_id, reference_number, user_id, wallet_id, " +
                "product_code, product_name, principal, interest_rate, interest_amount, processing_fee, " +
                "total_repayment_due, amount_due, term_months, due_date, currency, loan_status, disbursed, " +
                "reviewed_by_admin_id, decision_reason, disbursed_at, repaid_at, repayment_transaction_id, " +
                "created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        LocalDateTime now = loan.getCreatedAt() != null ? loan.getCreatedAt() : LocalDateTime.now();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, loan.getLoanId());
            ps.setString(2, loan.getTransactionId());
            ps.setString(3, loan.getReferenceNumber());
            ps.setString(4, loan.getBorrowerUserId());
            ps.setString(5, loan.getBorrowerWalletId());
            ps.setString(6, loan.getProductCode());
            ps.setString(7, loan.getProductName());
            ps.setString(8, loan.getPrincipal().toPlainString());
            ps.setString(9, loan.getInterestRate().toPlainString());
            ps.setString(10, loan.getInterestAmount().toPlainString());
            ps.setString(11, loan.getProcessingFee().toPlainString());
            ps.setString(12, loan.getTotalRepaymentDue().toPlainString());
            ps.setString(13, loan.getAmountDue().toPlainString());
            ps.setInt(14, loan.getTermMonths());
            ps.setString(15, loan.getDueDate() != null ? loan.getDueDate().toString() : null);
            ps.setString(16, loan.getCurrency());
            ps.setString(17, loan.getLoanStatus().name());
            ps.setInt(18, loan.getLoanStatus() == LoanStatus.DISBURSED || loan.getLoanStatus() == LoanStatus.PAID ? 1 : 0);
            ps.setString(19, loan.getReviewedByAdminId());
            ps.setString(20, loan.getDecisionReason());
            ps.setString(21, loan.getDisbursedAt() != null ? loan.getDisbursedAt().toString() : null);
            ps.setString(22, loan.getRepaidAt() != null ? loan.getRepaidAt().toString() : null);
            ps.setString(23, loan.getRepaymentTransactionId());
            ps.setString(24, now.toString());
            ps.setString(25, now.toString());
            ps.executeUpdate();
        }
    }

    public Optional<LoanTransaction> findByKey(String loanIdOrTxnOrRef) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByKey(loanIdOrTxnOrRef, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to load loan record: " + e.getMessage(), e);
        }
    }

    public Optional<LoanTransaction> findByKey(String loanIdOrTxnOrRef, Connection conn) throws SQLException {
        String sql = BASE_SELECT + " WHERE l.loan_id = ? OR l.application_transaction_id = ? OR l.reference_number = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, loanIdOrTxnOrRef);
            ps.setString(2, loanIdOrTxnOrRef);
            ps.setString(3, loanIdOrTxnOrRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapLoanRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<LoanTransaction> findByWalletId(String walletId) {
        try (Connection conn = databaseManager.getConnection()) {
            return findByWalletId(walletId, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query loans for wallet " + walletId + ": " + e.getMessage(), e);
        }
    }

    public List<LoanTransaction> findByWalletId(String walletId, Connection conn) throws SQLException {
        String sql = BASE_SELECT + " WHERE l.wallet_id = ? ORDER BY l.created_at DESC, l.loan_id DESC";
        List<LoanTransaction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapLoanRow(rs));
                }
            }
        }
        return list;
    }

    public List<LoanTransaction> findAll() {
        try (Connection conn = databaseManager.getConnection()) {
            return findAll(conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to query all loan applications: " + e.getMessage(), e);
        }
    }

    public List<LoanTransaction> findAll(Connection conn) throws SQLException {
        String sql = BASE_SELECT +
                " ORDER BY CASE l.loan_status WHEN 'PENDING_APPROVAL' THEN 0 WHEN 'DISBURSED' THEN 1 ELSE 2 END, " +
                "l.created_at DESC, l.loan_id DESC";
        List<LoanTransaction> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapLoanRow(rs));
            }
        }
        return list;
    }

    /**
     * Atomically transitions a PENDING_APPROVAL, non-disbursed loan to DISBURSED.
     * Guarded by {@code WHERE loan_id = ? AND loan_status = 'PENDING_APPROVAL' AND disbursed = 0}
     * so duplicate disbursement is impossible even under concurrent approval attempts.
     */
    public int markDisbursedIfPending(String loanId,
                                      BigDecimal amountDue,
                                      LocalDate dueDate,
                                      String adminUserId,
                                      String reason,
                                      LocalDateTime now,
                                      Connection conn) throws SQLException {
        String sql = "UPDATE loans SET loan_status = 'DISBURSED', disbursed = 1, amount_due = ?, due_date = ?, " +
                "reviewed_by_admin_id = ?, decision_reason = ?, disbursed_at = ?, updated_at = ? " +
                "WHERE loan_id = ? AND loan_status = 'PENDING_APPROVAL' AND disbursed = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, amountDue.toPlainString());
            ps.setString(2, dueDate != null ? dueDate.toString() : null);
            ps.setString(3, adminUserId);
            ps.setString(4, reason);
            ps.setString(5, now.toString());
            ps.setString(6, now.toString());
            ps.setString(7, loanId);
            return ps.executeUpdate();
        }
    }

    /**
     * Atomically transitions a PENDING_APPROVAL, non-disbursed loan to REJECTED.
     */
    public int markRejectedIfPending(String loanId,
                                     String adminUserId,
                                     String reason,
                                     LocalDateTime now,
                                     Connection conn) throws SQLException {
        String sql = "UPDATE loans SET loan_status = 'REJECTED', disbursed = 0, amount_due = '0.00', " +
                "reviewed_by_admin_id = ?, decision_reason = ?, updated_at = ? " +
                "WHERE loan_id = ? AND loan_status = 'PENDING_APPROVAL' AND disbursed = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, adminUserId);
            ps.setString(2, reason);
            ps.setString(3, now.toString());
            ps.setString(4, loanId);
            return ps.executeUpdate();
        }
    }

    /**
     * Atomically transitions a DISBURSED loan to PAID with zero amount due.
     * Guarded by {@code WHERE loan_id = ? AND loan_status = 'DISBURSED' AND disbursed = 1}
     * so repeat repayment of an already-paid loan is impossible.
     */
    public int markPaidIfDisbursed(String loanId,
                                   String repaymentTransactionId,
                                   LocalDateTime now,
                                   Connection conn) throws SQLException {
        String sql = "UPDATE loans SET loan_status = 'PAID', amount_due = '0.00', " +
                "repayment_transaction_id = ?, repaid_at = ?, updated_at = ? " +
                "WHERE loan_id = ? AND loan_status = 'DISBURSED' AND disbursed = 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repaymentTransactionId);
            ps.setString(2, now.toString());
            ps.setString(3, now.toString());
            ps.setString(4, loanId);
            return ps.executeUpdate();
        }
    }

    private static final String BASE_SELECT =
            "SELECT l.loan_id, l.application_transaction_id, l.reference_number, l.user_id, l.wallet_id, " +
            "l.product_code, l.product_name, l.principal, l.interest_rate, l.interest_amount, l.processing_fee, " +
            "l.total_repayment_due, l.amount_due, l.term_months, l.due_date, l.currency, l.loan_status, " +
            "l.disbursed, l.reviewed_by_admin_id, l.decision_reason, l.disbursed_at, l.repaid_at, " +
            "l.repayment_transaction_id, l.created_at, t.status AS txn_status, t.description AS txn_desc, " +
            "t.completed_at AS txn_completed_at, t.provider_reference AS txn_prov_ref, " +
            "t.provider_status AS txn_prov_status, u.name AS borrower_name " +
            "FROM loans l " +
            "LEFT JOIN transactions t ON t.transaction_id = l.application_transaction_id " +
            "LEFT JOIN users u ON u.user_id = l.user_id";

    private static LoanTransaction mapLoanRow(ResultSet rs) throws SQLException {
        LoanTransaction loan = new LoanTransaction();
        loan.setLoanId(rs.getString("loan_id"));
        loan.setTransactionId(rs.getString("application_transaction_id"));
        loan.setReferenceNumber(rs.getString("reference_number"));
        loan.setBorrowerUserId(rs.getString("user_id"));
        loan.setBorrowerName(rs.getString("borrower_name"));
        loan.setSourceWalletId(null);
        loan.setTargetWalletId(rs.getString("wallet_id"));
        loan.setProductCode(rs.getString("product_code"));
        loan.setProductName(rs.getString("product_name"));

        BigDecimal principal = new BigDecimal(rs.getString("principal"))
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        loan.setPrincipal(principal);
        loan.setAmount(principal);
        loan.setFee(MoneyUtil.ZERO);
        loan.setTotalAmount(principal);

        loan.setInterestRate(new BigDecimal(rs.getString("interest_rate")));
        loan.setInterestAmount(new BigDecimal(rs.getString("interest_amount"))
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        loan.setProcessingFee(new BigDecimal(rs.getString("processing_fee"))
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        loan.setTotalRepaymentDue(new BigDecimal(rs.getString("total_repayment_due"))
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        loan.setAmountDue(new BigDecimal(rs.getString("amount_due"))
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING));
        loan.setTermMonths(rs.getInt("term_months"));

        String dueDateStr = rs.getString("due_date");
        if (dueDateStr != null && !dueDateStr.isBlank()) {
            loan.setDueDate(LocalDate.parse(dueDateStr));
        }

        loan.setCurrency(rs.getString("currency"));
        loan.setType(TransactionType.LOAN_DISBURSEMENT);

        LoanStatus loanStatus = LoanStatus.valueOf(rs.getString("loan_status"));
        loan.setLoanStatus(loanStatus);

        String txnStatusStr = rs.getString("txn_status");
        if (txnStatusStr != null && !txnStatusStr.isBlank()) {
            loan.setStatus(TransactionStatus.valueOf(txnStatusStr));
        } else {
            loan.setStatus(switch (loanStatus) {
                case PENDING_APPROVAL -> TransactionStatus.PENDING;
                case DISBURSED, PAID -> TransactionStatus.COMPLETED;
                case REJECTED -> TransactionStatus.FAILED;
            });
        }

        loan.setDescription(rs.getString("txn_desc"));
        loan.setProviderReference(rs.getString("txn_prov_ref"));
        loan.setProviderStatus(rs.getString("txn_prov_status"));
        loan.setReviewedByAdminId(rs.getString("reviewed_by_admin_id"));
        loan.setDecisionReason(rs.getString("decision_reason"));

        String disbursedAt = rs.getString("disbursed_at");
        if (disbursedAt != null && !disbursedAt.isBlank()) {
            loan.setDisbursedAt(LocalDateTime.parse(disbursedAt));
        }
        String repaidAt = rs.getString("repaid_at");
        if (repaidAt != null && !repaidAt.isBlank()) {
            loan.setRepaidAt(LocalDateTime.parse(repaidAt));
        }
        loan.setRepaymentTransactionId(rs.getString("repayment_transaction_id"));
        String createdAt = rs.getString("created_at");
        if (createdAt != null && !createdAt.isBlank()) {
            loan.setCreatedAt(LocalDateTime.parse(createdAt));
        }
        String completedAt = rs.getString("txn_completed_at");
        if (completedAt != null && !completedAt.isBlank()) {
            loan.setCompletedAt(LocalDateTime.parse(completedAt));
        }
        return loan;
    }

    private Optional<ProductRow> queryProductByCode(String productCode, Connection conn) throws SQLException {
        String sql = "SELECT product_code, product_name, min_principal, max_principal, monthly_interest_rate, " +
                "term_months, processing_fee_flat, processing_fee_percent, currency " +
                "FROM loan_products WHERE product_code = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapProductRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<ProductRow> queryFirstProduct(Connection conn) throws SQLException {
        String sql = "SELECT product_code, product_name, min_principal, max_principal, monthly_interest_rate, " +
                "term_months, processing_fee_flat, processing_fee_percent, currency " +
                "FROM loan_products ORDER BY product_code ASC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(mapProductRow(rs));
            }
        }
        return Optional.empty();
    }

    private static ProductRow mapProductRow(ResultSet rs) throws SQLException {
        return new ProductRow(
                rs.getString("product_code"),
                rs.getString("product_name"),
                new BigDecimal(rs.getString("min_principal")),
                new BigDecimal(rs.getString("max_principal")),
                new BigDecimal(rs.getString("monthly_interest_rate")),
                rs.getInt("term_months"),
                new BigDecimal(rs.getString("processing_fee_flat")),
                new BigDecimal(rs.getString("processing_fee_percent")),
                rs.getString("currency")
        );
    }

    private record ProductRow(String productCode,
                              String productName,
                              BigDecimal minPrincipal,
                              BigDecimal maxPrincipal,
                              BigDecimal monthlyInterestRate,
                              int termMonths,
                              BigDecimal processingFeeFlat,
                              BigDecimal processingFeePercent,
                              String currency) {
    }
}
