package com.otog.service;

import com.otog.model.LoanTransaction;
import com.otog.model.Transaction;
import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Shared service contract for CAP-12 simulated loan applications, admin approvals/rejections,
 * single disbursement, and full lump-sum repayment.
 * Installment scheduling, partial repayment, penalties, and advanced eligibility scoring are deferred.
 */
public interface LoanService {

    LoanProduct getConfiguredLoanProduct();

    LoanPreview previewLoanApplication(String walletId, BigDecimal principal);

    LoanTransaction applyForLoan(String referenceNumber, String walletId, BigDecimal principal);

    LoanTransaction applyLoan(String referenceNumber, String walletId, BigDecimal principal,
                              BigDecimal interestRate, int termMonths);

    LoanTransaction approveLoan(String loanTransactionId);

    LoanTransaction approveLoan(String adminUserId, String loanIdOrTxnId, String reason);

    LoanTransaction rejectLoan(String adminUserId, String loanIdOrTxnId, String reason);

    Transaction repayLoan(String referenceNumber, String walletId, String loanTransactionId, BigDecimal amount);

    LoanTransaction repayLoanWithPin(String referenceNumber, String walletId, String loanIdOrTxnId,
                                     BigDecimal amount, String rawPin);

    LoanTransaction repayLoanFull(String referenceNumber, String walletId, String loanIdOrTxnId);

    LoanTransaction repayLoanFullWithPin(String referenceNumber, String walletId, String loanIdOrTxnId,
                                         String rawPin);

    List<LoanTransaction> getLoansByWalletId(String walletId);

    List<LoanTransaction> getAllLoanApplications(String adminUserId);

    BigDecimal getOutstandingAmountDue(String walletId);

    /**
     * Immutable descriptor of the single configured simulated loan product.
     */
    final class LoanProduct {
        private final String productCode;
        private final String productName;
        private final BigDecimal minPrincipal;
        private final BigDecimal maxPrincipal;
        private final BigDecimal monthlyInterestRate;
        private final int termMonths;
        private final BigDecimal processingFeeFlat;
        private final BigDecimal processingFeePercent;
        private final String currency;
        private final String calculationFormula;
        private final String scopeDisclaimer;

        public LoanProduct(String productCode,
                           String productName,
                           BigDecimal minPrincipal,
                           BigDecimal maxPrincipal,
                           BigDecimal monthlyInterestRate,
                           int termMonths,
                           BigDecimal processingFeeFlat,
                           BigDecimal processingFeePercent,
                           String currency,
                           String calculationFormula,
                           String scopeDisclaimer) {
            this.productCode = productCode;
            this.productName = productName;
            this.minPrincipal = minPrincipal;
            this.maxPrincipal = maxPrincipal;
            this.monthlyInterestRate = monthlyInterestRate;
            this.termMonths = termMonths;
            this.processingFeeFlat = processingFeeFlat;
            this.processingFeePercent = processingFeePercent;
            this.currency = currency;
            this.calculationFormula = calculationFormula;
            this.scopeDisclaimer = scopeDisclaimer;
        }

        public String getProductCode() {
            return productCode;
        }

        public String getProductName() {
            return productName;
        }

        public BigDecimal getMinPrincipal() {
            return minPrincipal;
        }

        public BigDecimal getMaxPrincipal() {
            return maxPrincipal;
        }

        public BigDecimal getMonthlyInterestRate() {
            return monthlyInterestRate;
        }

        public int getTermMonths() {
            return termMonths;
        }

        public BigDecimal getProcessingFeeFlat() {
            return processingFeeFlat;
        }

        public BigDecimal getProcessingFeePercent() {
            return processingFeePercent;
        }

        public String getCurrency() {
            return currency;
        }

        public String getCalculationFormula() {
            return calculationFormula;
        }

        public String getScopeDisclaimer() {
            return scopeDisclaimer;
        }
    }

    /**
     * Immutable pre-application calculation summary showing principal, interest,
     * processing fee, total lump-sum repayment due, and maturity due date.
     */
    final class LoanPreview {
        private final String walletId;
        private final String productCode;
        private final String productName;
        private final BigDecimal principal;
        private final BigDecimal monthlyInterestRate;
        private final int termMonths;
        private final BigDecimal interestAmount;
        private final BigDecimal processingFee;
        private final BigDecimal totalRepaymentDue;
        private final LocalDate estimatedDueDate;
        private final String currency;
        private final String referenceNumber;
        private final String calculationFormula;

        public LoanPreview(String walletId,
                           String productCode,
                           String productName,
                           BigDecimal principal,
                           BigDecimal monthlyInterestRate,
                           int termMonths,
                           BigDecimal interestAmount,
                           BigDecimal processingFee,
                           BigDecimal totalRepaymentDue,
                           LocalDate estimatedDueDate,
                           String currency,
                           String referenceNumber,
                           String calculationFormula) {
            this.walletId = walletId;
            this.productCode = productCode;
            this.productName = productName;
            this.principal = principal;
            this.monthlyInterestRate = monthlyInterestRate;
            this.termMonths = termMonths;
            this.interestAmount = interestAmount;
            this.processingFee = processingFee;
            this.totalRepaymentDue = totalRepaymentDue;
            this.estimatedDueDate = estimatedDueDate;
            this.currency = currency;
            this.referenceNumber = referenceNumber;
            this.calculationFormula = calculationFormula;
        }

        public String getWalletId() {
            return walletId;
        }

        public String getProductCode() {
            return productCode;
        }

        public String getProductName() {
            return productName;
        }

        public BigDecimal getPrincipal() {
            return principal;
        }

        public BigDecimal getMonthlyInterestRate() {
            return monthlyInterestRate;
        }

        public int getTermMonths() {
            return termMonths;
        }

        public BigDecimal getInterestAmount() {
            return interestAmount;
        }

        public BigDecimal getProcessingFee() {
            return processingFee;
        }

        public BigDecimal getTotalRepaymentDue() {
            return totalRepaymentDue;
        }

        public LocalDate getEstimatedDueDate() {
            return estimatedDueDate;
        }

        public String getCurrency() {
            return currency;
        }

        public String getReferenceNumber() {
            return referenceNumber;
        }

        public String getCalculationFormula() {
            return calculationFormula;
        }

        public String toConfirmationText() {
            BigDecimal ratePct = monthlyInterestRate.multiply(new BigDecimal("100"))
                    .setScale(2, MoneyUtil.DEFAULT_ROUNDING);
            return new StringBuilder()
                    .append("Loan Product: ").append(productName).append(" (").append(productCode).append(") [SIMULATED]\n")
                    .append("Principal (Disbursed on Approval): ").append(MoneyUtil.format(principal, currency)).append("\n")
                    .append("Interest Rate: ").append(ratePct.toPlainString()).append("% / month x ")
                    .append(termMonths).append(" month").append(termMonths == 1 ? "" : "s").append("\n")
                    .append("Calculated Interest: ").append(MoneyUtil.format(interestAmount, currency)).append("\n")
                    .append("Processing Fee: ").append(MoneyUtil.format(processingFee, currency)).append("\n")
                    .append("Total Full Repayment Due: ").append(MoneyUtil.format(totalRepaymentDue, currency)).append("\n")
                    .append("Estimated Due Date: ").append(estimatedDueDate).append("\n")
                    .append("Formula: ").append(calculationFormula).append("\n")
                    .append("Repayment Policy: Single full lump-sum repayment (installments, partial payments, penalties deferred)\n")
                    .append("Application Reference: ").append(referenceNumber)
                    .toString();
        }
    }
}
