package com.otog.model;

import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Specialized transaction for simulated loans (CAP-12).
 * Matches Loan in the domain class diagram and captures application,
 * single-disbursement approval/rejection, and full lump-sum repayment metadata.
 */
public class LoanTransaction extends Transaction {
    private String loanId;
    private String borrowerUserId;
    private String borrowerName;
    private String productCode;
    private String productName;
    private BigDecimal principal;
    private BigDecimal interestRate;
    private BigDecimal interestAmount;
    private BigDecimal processingFee;
    private BigDecimal totalRepaymentDue;
    private BigDecimal amountDue;
    private int termMonths;
    private LocalDate dueDate;
    private LoanStatus loanStatus;
    private String reviewedByAdminId;
    private String decisionReason;
    private LocalDateTime disbursedAt;
    private LocalDateTime repaidAt;
    private String repaymentTransactionId;
    private String linkedLoanTransactionId;

    public LoanTransaction() {
        super();
        setType(TransactionType.LOAN_DISBURSEMENT);
        this.loanStatus = LoanStatus.PENDING_APPROVAL;
        this.interestAmount = MoneyUtil.ZERO;
        this.processingFee = MoneyUtil.ZERO;
        this.totalRepaymentDue = MoneyUtil.ZERO;
        this.amountDue = MoneyUtil.ZERO;
    }

    public LoanTransaction(String transactionId, String referenceNumber, String walletId,
                           BigDecimal principal, BigDecimal interestRate, int termMonths, LocalDate dueDate) {
        super(transactionId, referenceNumber, null, walletId, principal, MoneyUtil.ZERO,
                TransactionType.LOAN_DISBURSEMENT, "Loan disbursement of " + principal);
        this.principal = principal != null ? MoneyUtil.validatePositiveAmount(principal) : MoneyUtil.ZERO;
        this.interestRate = interestRate != null ? interestRate : BigDecimal.ZERO;
        this.termMonths = termMonths;
        this.dueDate = dueDate;
        this.loanStatus = LoanStatus.PENDING_APPROVAL;
        this.interestAmount = calculateInterestAmount(this.principal, this.interestRate, Math.max(0, termMonths));
        this.processingFee = MoneyUtil.ZERO;
        this.totalRepaymentDue = this.principal.add(this.interestAmount)
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        this.amountDue = MoneyUtil.ZERO;
    }

    /**
     * Documented CAP-12 simulated flat simple interest formula:
     * {@code interest = round_HALF_EVEN_2(principal * interestRate * termMonths)}.
     */
    public static BigDecimal calculateInterestAmount(BigDecimal principal, BigDecimal interestRate, int termMonths) {
        if (principal == null || interestRate == null || termMonths <= 0) {
            return MoneyUtil.ZERO;
        }
        return principal.multiply(interestRate)
                .multiply(BigDecimal.valueOf(termMonths))
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
    }

    public String getLoanId() {
        return loanId;
    }

    public void setLoanId(String loanId) {
        this.loanId = loanId;
    }

    public String getBorrowerUserId() {
        return borrowerUserId;
    }

    public void setBorrowerUserId(String borrowerUserId) {
        this.borrowerUserId = borrowerUserId;
    }

    public String getBorrowerName() {
        return borrowerName;
    }

    public void setBorrowerName(String borrowerName) {
        this.borrowerName = borrowerName;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public BigDecimal getPrincipal() {
        return principal;
    }

    public void setPrincipal(BigDecimal principal) {
        this.principal = principal;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
    }

    public BigDecimal getInterestAmount() {
        return interestAmount;
    }

    public void setInterestAmount(BigDecimal interestAmount) {
        this.interestAmount = interestAmount != null
                ? MoneyUtil.validateNonNegativeAmount(interestAmount)
                : MoneyUtil.ZERO;
    }

    public BigDecimal getProcessingFee() {
        return processingFee;
    }

    public void setProcessingFee(BigDecimal processingFee) {
        this.processingFee = processingFee != null
                ? MoneyUtil.validateNonNegativeAmount(processingFee)
                : MoneyUtil.ZERO;
    }

    public BigDecimal getTotalRepaymentDue() {
        return totalRepaymentDue;
    }

    public void setTotalRepaymentDue(BigDecimal totalRepaymentDue) {
        this.totalRepaymentDue = totalRepaymentDue != null
                ? MoneyUtil.validateNonNegativeAmount(totalRepaymentDue)
                : MoneyUtil.ZERO;
    }

    public BigDecimal getAmountDue() {
        return amountDue;
    }

    public void setAmountDue(BigDecimal amountDue) {
        this.amountDue = amountDue != null
                ? MoneyUtil.validateNonNegativeAmount(amountDue)
                : MoneyUtil.ZERO;
    }

    public int getTermMonths() {
        return termMonths;
    }

    public void setTermMonths(int termMonths) {
        this.termMonths = termMonths;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public LoanStatus getLoanStatus() {
        return loanStatus;
    }

    public void setLoanStatus(LoanStatus loanStatus) {
        this.loanStatus = loanStatus != null ? loanStatus : LoanStatus.PENDING_APPROVAL;
    }

    public String getReviewedByAdminId() {
        return reviewedByAdminId;
    }

    public void setReviewedByAdminId(String reviewedByAdminId) {
        this.reviewedByAdminId = reviewedByAdminId;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public LocalDateTime getDisbursedAt() {
        return disbursedAt;
    }

    public void setDisbursedAt(LocalDateTime disbursedAt) {
        this.disbursedAt = disbursedAt;
    }

    public LocalDateTime getRepaidAt() {
        return repaidAt;
    }

    public void setRepaidAt(LocalDateTime repaidAt) {
        this.repaidAt = repaidAt;
    }

    public String getRepaymentTransactionId() {
        return repaymentTransactionId;
    }

    public void setRepaymentTransactionId(String repaymentTransactionId) {
        this.repaymentTransactionId = repaymentTransactionId;
    }

    public String getLinkedLoanTransactionId() {
        return linkedLoanTransactionId;
    }

    public void setLinkedLoanTransactionId(String linkedLoanTransactionId) {
        this.linkedLoanTransactionId = linkedLoanTransactionId;
    }

    /**
     * Convenience accessor resolving the owning borrower wallet regardless of whether this record
     * represents a disbursement ({@code targetWalletId}) or a repayment ({@code sourceWalletId}).
     */
    public String getBorrowerWalletId() {
        if (getType() == TransactionType.LOAN_REPAYMENT && getSourceWalletId() != null) {
            return getSourceWalletId();
        }
        return getTargetWalletId() != null ? getTargetWalletId() : getSourceWalletId();
    }

    public void apply() {
        setStatus(TransactionStatus.PENDING);
        this.loanStatus = LoanStatus.PENDING_APPROVAL;
        this.amountDue = MoneyUtil.ZERO;
    }

    public void approve() {
        execute();
        this.loanStatus = LoanStatus.DISBURSED;
        this.disbursedAt = getCompletedAt() != null ? getCompletedAt() : LocalDateTime.now();
        this.amountDue = this.totalRepaymentDue != null ? this.totalRepaymentDue : MoneyUtil.ZERO;
    }

    public void reject(String adminUserId, String reason) {
        setStatus(TransactionStatus.FAILED);
        setCompletedAt(LocalDateTime.now());
        this.loanStatus = LoanStatus.REJECTED;
        this.reviewedByAdminId = adminUserId;
        this.decisionReason = reason;
        this.amountDue = MoneyUtil.ZERO;
    }

    public void repay() {
        setType(TransactionType.LOAN_REPAYMENT);
        execute();
        this.loanStatus = LoanStatus.PAID;
        this.repaidAt = getCompletedAt() != null ? getCompletedAt() : LocalDateTime.now();
        this.amountDue = MoneyUtil.ZERO;
    }

    @Override
    protected void appendSubtypeReceiptLines(ReceiptBuilder builder) {
        builder.optionalLine("Loan ID", loanId)
                .optionalLine("Loan Product", productName != null && !productName.isBlank() ? productName : null)
                .optionalLine("Loan Status", loanStatus != null ? loanStatus.name() : null)
                .optionalLine("Principal", principal != null ? MoneyUtil.format(principal, getCurrency()) : null)
                .optionalLine("Interest", interestRate != null ? interestRate.toPlainString() : null)
                .optionalLine("Interest Amt", interestAmount != null ? MoneyUtil.format(interestAmount, getCurrency()) : null)
                .optionalLine("Proc. Fee", processingFee != null && processingFee.compareTo(BigDecimal.ZERO) > 0
                        ? MoneyUtil.format(processingFee, getCurrency()) : null)
                .optionalLine("Total Due", totalRepaymentDue != null && totalRepaymentDue.compareTo(BigDecimal.ZERO) > 0
                        ? MoneyUtil.format(totalRepaymentDue, getCurrency()) : null)
                .optionalLine("Amount Due", amountDue != null ? MoneyUtil.format(amountDue, getCurrency()) : null)
                .optionalLine("Term", termMonths > 0 ? termMonths + " month" + (termMonths == 1 ? "" : "s") : null)
                .optionalLine("Due Date", dueDate != null ? dueDate.toString() : null)
                .optionalLine("Linked Loan", linkedLoanTransactionId)
                .optionalLine("Decision", decisionReason);
    }

    @Override
    public LoanTransaction copyAsDraft(String newReferenceNumber) {
        LoanTransaction draft = new LoanTransaction();
        copyBaseDraftFieldsTo(draft, newReferenceNumber);
        draft.setProductCode(this.productCode);
        draft.setProductName(this.productName);
        draft.setPrincipal(this.principal);
        draft.setInterestRate(this.interestRate);
        draft.setInterestAmount(this.interestAmount);
        draft.setProcessingFee(this.processingFee);
        draft.setTotalRepaymentDue(this.totalRepaymentDue);
        draft.setAmountDue(MoneyUtil.ZERO);
        draft.setTermMonths(this.termMonths);
        draft.setDueDate(this.dueDate);
        draft.setLoanStatus(LoanStatus.PENDING_APPROVAL);
        draft.setReviewedByAdminId(null);
        draft.setDecisionReason(null);
        draft.setDisbursedAt(null);
        draft.setRepaidAt(null);
        draft.setRepaymentTransactionId(null);
        draft.setLinkedLoanTransactionId(null);
        return draft;
    }
}
