package com.otog.model;

import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Fluent Creational Builder for constructing specialized {@link Transaction} instances
 * ({@link SendMoneyTransaction}, {@link CashInOutTransaction}, {@link LoanTransaction},
 * and {@link InvestMoneyTransaction}) with invariant validation.
 */
public class TransactionBuilder {
    private String transactionId;
    private String referenceNumber;
    private String sourceWalletId;
    private String targetWalletId;
    private BigDecimal amount;
    private BigDecimal fee;
    private String currency;
    private String recipientNumber;
    private String note;
    private String cashOperationType;
    private String channel;
    private String providerReference;
    private String providerStatus;
    private BigDecimal principal;
    private BigDecimal interestRate;
    private int termMonths;
    private LocalDate dueDate;
    private String product;
    private BigDecimal units;
    private String billerName;
    private String billerReference;
    private TransactionType type = TransactionType.SEND_MONEY;
    private TransactionStatus status = TransactionStatus.PENDING;

    public TransactionBuilder() {
    }

    public TransactionBuilder transactionId(String transactionId) {
        this.transactionId = transactionId;
        return this;
    }

    public TransactionBuilder referenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
        return this;
    }

    public TransactionBuilder from(String sourceWalletId) {
        this.sourceWalletId = sourceWalletId;
        return this;
    }

    public TransactionBuilder sourceWalletId(String sourceWalletId) {
        this.sourceWalletId = sourceWalletId;
        return this;
    }

    public TransactionBuilder to(String targetWalletId) {
        this.targetWalletId = targetWalletId;
        return this;
    }

    public TransactionBuilder targetWalletId(String targetWalletId) {
        this.targetWalletId = targetWalletId;
        return this;
    }

    public TransactionBuilder amount(BigDecimal amount) {
        this.amount = amount;
        return this;
    }

    public TransactionBuilder fee(BigDecimal fee) {
        this.fee = fee;
        return this;
    }

    public TransactionBuilder currency(String currency) {
        this.currency = currency;
        return this;
    }

    public TransactionBuilder recipientNumber(String recipientNumber) {
        this.recipientNumber = recipientNumber;
        return this;
    }

    public TransactionBuilder note(String note) {
        this.note = note;
        return this;
    }

    public TransactionBuilder description(String description) {
        this.note = description;
        return this;
    }

    public TransactionBuilder cashOperationType(String cashOperationType) {
        this.cashOperationType = cashOperationType;
        return this;
    }

    public TransactionBuilder channel(String channel) {
        this.channel = channel;
        return this;
    }

    public TransactionBuilder providerReference(String providerReference) {
        this.providerReference = providerReference;
        return this;
    }

    public TransactionBuilder providerStatus(String providerStatus) {
        this.providerStatus = providerStatus;
        return this;
    }

    public TransactionBuilder principal(BigDecimal principal) {
        this.principal = principal;
        return this;
    }

    public TransactionBuilder interestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
        return this;
    }

    public TransactionBuilder termMonths(int termMonths) {
        this.termMonths = termMonths;
        return this;
    }

    public TransactionBuilder dueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
        return this;
    }

    public TransactionBuilder product(String product) {
        this.product = product;
        return this;
    }

    public TransactionBuilder units(BigDecimal units) {
        this.units = units;
        return this;
    }

    public TransactionBuilder biller(String billerName) {
        this.billerName = billerName;
        return this;
    }

    public TransactionBuilder billerName(String billerName) {
        this.billerName = billerName;
        return this;
    }

    public TransactionBuilder billerReference(String billerReference) {
        this.billerReference = billerReference;
        return this;
    }

    public TransactionBuilder type(TransactionType type) {
        this.type = type;
        return this;
    }

    public TransactionBuilder status(TransactionStatus status) {
        this.status = status;
        return this;
    }

    public SendMoneyTransaction build() {
        return buildSendMoney();
    }

    public SendMoneyTransaction buildSendMoney() {
        if (referenceNumber == null || referenceNumber.trim().isEmpty()) {
            throw new IllegalStateException("Reference number is required for transaction construction");
        }
        if (sourceWalletId == null || sourceWalletId.trim().isEmpty()) {
            throw new IllegalStateException("Source wallet ID (from) is required for transaction construction");
        }
        if (targetWalletId == null || targetWalletId.trim().isEmpty()) {
            throw new IllegalStateException("Target wallet ID (to) is required for transaction construction");
        }
        if (amount == null) {
            throw new IllegalStateException("Amount is required for transaction construction");
        }

        String effectiveTxnId = resolveTransactionId();
        BigDecimal effectiveFee = (fee != null) ? fee : MoneyUtil.ZERO;
        String effectiveNote = (note != null)
                ? note
                : "Transfer to " + (recipientNumber != null ? recipientNumber : targetWalletId);

        SendMoneyTransaction txn = new SendMoneyTransaction(
                effectiveTxnId,
                referenceNumber.trim(),
                sourceWalletId.trim(),
                targetWalletId.trim(),
                amount,
                effectiveFee,
                recipientNumber,
                effectiveNote
        );
        applyCommonOptionalFields(txn, type != null ? type : TransactionType.SEND_MONEY);
        return txn;
    }

    public CashInOutTransaction buildCashInOut() {
        if (referenceNumber == null || referenceNumber.trim().isEmpty()) {
            throw new IllegalStateException("Reference number is required for cash transaction construction");
        }
        String effectiveOp = (cashOperationType != null && !cashOperationType.trim().isEmpty())
                ? cashOperationType.trim().toUpperCase()
                : (type == TransactionType.CASH_OUT ? "CASH_OUT" : "CASH_IN");
        String walletId = "CASH_OUT".equals(effectiveOp)
                ? (sourceWalletId != null ? sourceWalletId : targetWalletId)
                : (targetWalletId != null ? targetWalletId : sourceWalletId);
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalStateException("Wallet ID is required for cash transaction construction");
        }
        if (amount == null) {
            throw new IllegalStateException("Amount is required for cash transaction construction");
        }

        String effectiveChannel = (channel != null && !channel.trim().isEmpty()) ? channel.trim() : "Bank";
        BigDecimal effectiveFee = (fee != null) ? fee : MoneyUtil.ZERO;

        CashInOutTransaction txn = new CashInOutTransaction(
                resolveTransactionId(),
                referenceNumber.trim(),
                walletId.trim(),
                amount,
                effectiveFee,
                effectiveOp,
                effectiveChannel,
                providerReference
        );
        if (note != null) {
            txn.setDescription(note);
        }
        TransactionType expectedType = "CASH_OUT".equals(effectiveOp)
                ? TransactionType.CASH_OUT
                : TransactionType.CASH_IN;
        applyCommonOptionalFields(txn, type == TransactionType.CASH_OUT || type == TransactionType.CASH_IN ? type : expectedType);
        return txn;
    }

    public LoanTransaction buildLoan() {
        if (referenceNumber == null || referenceNumber.trim().isEmpty()) {
            throw new IllegalStateException("Reference number is required for loan transaction construction");
        }
        String walletId = targetWalletId != null ? targetWalletId : sourceWalletId;
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalStateException("Wallet ID is required for loan transaction construction");
        }
        BigDecimal effectivePrincipal = principal != null ? principal : amount;
        if (effectivePrincipal == null) {
            throw new IllegalStateException("Loan principal amount is required");
        }

        LoanTransaction txn = new LoanTransaction(
                resolveTransactionId(),
                referenceNumber.trim(),
                walletId.trim(),
                effectivePrincipal,
                interestRate != null ? interestRate : BigDecimal.ZERO,
                termMonths,
                dueDate
        );
        if (note != null) {
            txn.setDescription(note);
        }
        TransactionType loanType = (type == TransactionType.LOAN_REPAYMENT)
                ? TransactionType.LOAN_REPAYMENT
                : TransactionType.LOAN_DISBURSEMENT;
        applyCommonOptionalFields(txn, loanType);
        return txn;
    }

    public InvestMoneyTransaction buildInvestment() {
        if (referenceNumber == null || referenceNumber.trim().isEmpty()) {
            throw new IllegalStateException("Reference number is required for investment transaction construction");
        }
        String walletId = sourceWalletId != null ? sourceWalletId : targetWalletId;
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalStateException("Wallet ID is required for investment transaction construction");
        }
        if (amount == null) {
            throw new IllegalStateException("Investment amount is required");
        }

        String effectiveProduct = (product != null && !product.trim().isEmpty()) ? product.trim() : "OTOG Fund";
        BigDecimal effectiveFee = (fee != null) ? fee : MoneyUtil.ZERO;
        TransactionType invType = (type == TransactionType.INVESTMENT_REDEEM)
                ? TransactionType.INVESTMENT_REDEEM
                : TransactionType.INVESTMENT_BUY;
        InvestMoneyTransaction txn = new InvestMoneyTransaction(
                resolveTransactionId(),
                referenceNumber.trim(),
                walletId.trim(),
                amount,
                effectiveFee,
                effectiveProduct,
                units != null ? units : BigDecimal.ONE,
                invType
        );
        if (note != null) {
            txn.setDescription(note);
        }
        applyCommonOptionalFields(txn, invType);
        return txn;
    }

    public BillPaymentTransaction buildBillPayment() {
        if (referenceNumber == null || referenceNumber.trim().isEmpty()) {
            throw new IllegalStateException("Reference number is required for bill payment construction");
        }
        if (sourceWalletId == null || sourceWalletId.trim().isEmpty()) {
            throw new IllegalStateException("Source wallet ID is required for bill payment construction");
        }
        if (amount == null) {
            throw new IllegalStateException("Amount is required for bill payment construction");
        }

        String effectiveBiller = (billerName != null && !billerName.trim().isEmpty()) ? billerName.trim() : "Biller";
        String effectiveBillerRef = (billerReference != null && !billerReference.trim().isEmpty()) ? billerReference.trim() : null;
        BigDecimal effectiveFee = (fee != null) ? fee : MoneyUtil.ZERO;
        String effectiveDesc = (note != null && !note.trim().isEmpty())
                ? note.trim()
                : "Bill Payment to " + effectiveBiller
                        + (effectiveBillerRef != null ? " [" + ReceiptBuilder.maskIdentifier(effectiveBillerRef) + "]" : "");

        BillPaymentTransaction txn = new BillPaymentTransaction(
                resolveTransactionId(),
                referenceNumber.trim(),
                sourceWalletId.trim(),
                amount,
                effectiveFee,
                effectiveBiller,
                effectiveBillerRef,
                effectiveDesc
        );
        applyCommonOptionalFields(txn, TransactionType.BILL_PAYMENT);
        return txn;
    }

    private String resolveTransactionId() {
        return (transactionId != null && !transactionId.trim().isEmpty())
                ? transactionId.trim()
                : IdGenerator.nextTransactionId();
    }

    private void applyCommonOptionalFields(Transaction txn, TransactionType resolvedType) {
        String effectiveCurrency = (currency != null && !currency.trim().isEmpty())
                ? currency.trim()
                : OtogConfig.getInstance().getDefaultCurrency();
        txn.setCurrency(effectiveCurrency);
        if (resolvedType != null) {
            txn.setType(resolvedType);
        }
        if (providerReference != null) {
            txn.setProviderReference(providerReference);
        }
        if (providerStatus != null) {
            txn.setProviderStatus(providerStatus);
        }
        if (status != null) {
            txn.setStatus(status);
        }
    }
}
