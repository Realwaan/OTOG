package com.otog.model;

import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Creational Factory Method hub for producing specialized {@link Transaction} instances
 * across all wallet domains and hydrating persisted rows by {@link TransactionType}.
 * Delegates construction and invariant checks to {@link TransactionBuilder}.
 */
public final class TransactionFactory {

    private TransactionFactory() {
        // Factory class
    }

    public static SendMoneyTransaction createSendMoney(String referenceNumber,
                                                       String sourceWalletId,
                                                       String targetWalletId,
                                                       BigDecimal amount,
                                                       BigDecimal fee,
                                                       String recipientNumber,
                                                       String note) {
        return new TransactionBuilder()
                .referenceNumber(referenceNumber)
                .from(sourceWalletId)
                .to(targetWalletId)
                .amount(amount)
                .fee(fee)
                .recipientNumber(recipientNumber)
                .note(note)
                .buildSendMoney();
    }

    public static SendMoneyTransaction createSendMoney(String referenceNumber,
                                                       String sourceWalletId,
                                                       String targetWalletId,
                                                       BigDecimal amount,
                                                       String recipientNumber,
                                                       String note) {
        return createSendMoney(referenceNumber, sourceWalletId, targetWalletId,
                amount, MoneyUtil.ZERO, recipientNumber, note);
    }

    public static CashInOutTransaction createCashIn(String transactionId,
                                                    String referenceNumber,
                                                    String targetWalletId,
                                                    BigDecimal amount,
                                                    String channel,
                                                    String providerReference,
                                                    String description) {
        return new TransactionBuilder()
                .transactionId(transactionId)
                .referenceNumber(referenceNumber)
                .to(targetWalletId)
                .amount(amount)
                .fee(MoneyUtil.ZERO)
                .cashOperationType("CASH_IN")
                .channel(channel)
                .providerReference(providerReference)
                .description(description)
                .type(TransactionType.CASH_IN)
                .buildCashInOut();
    }

    public static CashInOutTransaction createCashOut(String transactionId,
                                                     String referenceNumber,
                                                     String sourceWalletId,
                                                     BigDecimal amount,
                                                     BigDecimal fee,
                                                     String channel,
                                                     String providerReference,
                                                     String description) {
        return new TransactionBuilder()
                .transactionId(transactionId)
                .referenceNumber(referenceNumber)
                .from(sourceWalletId)
                .amount(amount)
                .fee(fee)
                .cashOperationType("CASH_OUT")
                .channel(channel)
                .providerReference(providerReference)
                .description(description)
                .type(TransactionType.CASH_OUT)
                .buildCashInOut();
    }

    public static CashInOutTransaction createCashInOut(String transactionId,
                                                       String referenceNumber,
                                                       String walletId,
                                                       BigDecimal amount,
                                                       BigDecimal fee,
                                                       String cashOperationType,
                                                       String channel,
                                                       String description) {
        TransactionBuilder builder = new TransactionBuilder()
                .transactionId(transactionId)
                .referenceNumber(referenceNumber)
                .amount(amount)
                .fee(fee)
                .cashOperationType(cashOperationType)
                .channel(channel)
                .description(description);
        if ("CASH_OUT".equalsIgnoreCase(cashOperationType)) {
            builder.from(walletId).type(TransactionType.CASH_OUT);
        } else {
            builder.to(walletId).type(TransactionType.CASH_IN);
        }
        return builder.buildCashInOut();
    }

    public static LoanTransaction createLoan(String referenceNumber,
                                             String walletId,
                                             BigDecimal principal,
                                             BigDecimal interestRate,
                                             int termMonths,
                                             LocalDate dueDate,
                                             String description) {
        return new TransactionBuilder()
                .referenceNumber(referenceNumber)
                .to(walletId)
                .principal(principal)
                .interestRate(interestRate)
                .termMonths(termMonths)
                .dueDate(dueDate)
                .description(description)
                .buildLoan();
    }

    public static InvestMoneyTransaction createInvestment(String referenceNumber,
                                                        String walletId,
                                                        BigDecimal amount,
                                                        BigDecimal fee,
                                                        String product,
                                                        BigDecimal units,
                                                        String description) {
        return new TransactionBuilder()
                .referenceNumber(referenceNumber)
                .from(walletId)
                .amount(amount)
                .fee(fee)
                .product(product)
                .units(units)
                .description(description)
                .buildInvestment();
    }

    /**
     * Factory Method creating the appropriate specialized subclass shell for repository hydration.
     */
    public static Transaction createEmptyForType(TransactionType type) {
        if (type == null) {
            return new Transaction();
        }
        return switch (type) {
            case CASH_IN, CASH_OUT -> {
                CashInOutTransaction cio = new CashInOutTransaction();
                cio.setCashOperationType(type.name());
                cio.setType(type);
                yield cio;
            }
            case SEND_MONEY -> new SendMoneyTransaction();
            case LOAN_DISBURSEMENT, LOAN_REPAYMENT -> {
                LoanTransaction loan = new LoanTransaction();
                loan.setType(type);
                yield loan;
            }
            case INVESTMENT_BUY, INVESTMENT_REDEEM -> {
                InvestMoneyTransaction inv = new InvestMoneyTransaction();
                inv.setType(type);
                yield inv;
            }
            default -> {
                Transaction txn = new Transaction();
                txn.setType(type);
                yield txn;
            }
        };
    }
}
