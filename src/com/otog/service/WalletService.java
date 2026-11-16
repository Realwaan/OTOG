package com.otog.service;

import com.otog.database.DatabaseManager;
import com.otog.exception.*;
import com.otog.model.*;
import com.otog.repository.*;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service orchestrating wallet domain logic, transaction correctness, atomic commits,
 * and concurrency protection against double-spending.
 */
public class WalletService implements WalletOperations {
    private final DatabaseManager databaseManager;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerRepository ledgerRepository;

    private final ConcurrentHashMap<String, ReentrantLock> walletLocks = new ConcurrentHashMap<>();
    private final AuthService authService;
    private final boolean requirePinAuthorization;
    private BankApiClient bankApiClient;

    public WalletService(DatabaseManager databaseManager) {
        this(databaseManager,
                new UserRepository(databaseManager),
                new WalletRepository(databaseManager),
                new PaymentMethodRepository(databaseManager),
                new TransactionRepository(databaseManager),
                new LedgerRepository(databaseManager),
                null,
                false);
    }

    public WalletService(DatabaseManager databaseManager, BankApiClient bankApiClient) {
        this(databaseManager);
        this.bankApiClient = bankApiClient != null ? bankApiClient : new BankApiClient();
    }

    public WalletService(DatabaseManager databaseManager, PaymentProviderFactory factory) {
        this(databaseManager);
        this.bankApiClient = factory != null ? factory.createBankApiClient() : new BankApiClient();
    }

    public WalletService(DatabaseManager databaseManager, AuthService authService, BankApiClient bankApiClient) {
        this(databaseManager, authService);
        this.bankApiClient = bankApiClient != null ? bankApiClient : new BankApiClient();
    }

    public WalletService(DatabaseManager databaseManager, AuthService authService, PaymentProviderFactory factory) {
        this(databaseManager, authService);
        this.bankApiClient = factory != null ? factory.createBankApiClient() : new BankApiClient();
    }

    public WalletService(DatabaseManager databaseManager, AuthService authService) {
        this(databaseManager,
                new UserRepository(databaseManager),
                new WalletRepository(databaseManager),
                new PaymentMethodRepository(databaseManager),
                new TransactionRepository(databaseManager),
                new LedgerRepository(databaseManager),
                authService,
                true);
    }

    public WalletService(DatabaseManager databaseManager,
                         UserRepository userRepository,
                         WalletRepository walletRepository,
                         PaymentMethodRepository paymentMethodRepository,
                         TransactionRepository transactionRepository,
                         LedgerRepository ledgerRepository) {
        this(databaseManager, userRepository, walletRepository, paymentMethodRepository,
                transactionRepository, ledgerRepository, null, false);
    }

    public WalletService(DatabaseManager databaseManager,
                         UserRepository userRepository,
                         WalletRepository walletRepository,
                         PaymentMethodRepository paymentMethodRepository,
                         TransactionRepository transactionRepository,
                         LedgerRepository ledgerRepository,
                         AuthService authService,
                         boolean requirePinAuthorization) {
        this.databaseManager = databaseManager;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerRepository = ledgerRepository;
        this.authService = authService;
        this.requirePinAuthorization = requirePinAuthorization;
        this.bankApiClient = new BankApiClient();
    }

    public BankApiClient getBankApiClient() {
        return bankApiClient;
    }

    public void setBankApiClient(BankApiClient bankApiClient) {
        this.bankApiClient = bankApiClient != null ? bankApiClient : new BankApiClient();
    }

    public void setPaymentProviderFactory(PaymentProviderFactory factory) {
        this.bankApiClient = factory != null ? factory.createBankApiClient() : new BankApiClient();
    }

    public PaymentMethod linkPaymentMethod(PaymentMethod paymentMethod) {
        if (paymentMethod == null) {
            throw new IllegalArgumentException("Payment method cannot be null");
        }
        if (paymentMethod.getPaymentMethodId() == null) {
            paymentMethod.setPaymentMethodId(IdGenerator.nextPaymentMethodId());
        }
        return paymentMethodRepository.save(paymentMethod);
    }

    public PaymentMethod linkPaymentMethod(String userId, PaymentMethodType type, String providerName,
                                           String accountName, String accountNumber) {
        if (userId == null || providerName == null) {
            throw new IllegalArgumentException("User ID and provider name cannot be null");
        }
        String pmId = IdGenerator.nextPaymentMethodId();
        PaymentMethod pm = new PaymentMethod(pmId, userId, type, providerName, accountName, accountNumber);
        List<PaymentMethod> existing = paymentMethodRepository.findByUserId(userId);
        if (existing.isEmpty()) {
            pm.setDefault(true);
        }
        return paymentMethodRepository.save(pm);
    }

    public List<PaymentMethod> seedDemoBankAccountsForUser(String userId) {
        List<PaymentMethod> list = new ArrayList<>();
        list.add(linkPaymentMethod(userId, PaymentMethodType.BANK_ACCOUNT, "BDO Unibank", "Demo BDO Account", "•••• 1234"));
        list.add(linkPaymentMethod(userId, PaymentMethodType.BANK_ACCOUNT, "Bank of the Philippine Islands (BPI)", "Demo BPI Account", "•••• 5678"));
        list.add(linkPaymentMethod(userId, PaymentMethodType.BANK_ACCOUNT, "Metrobank", "Demo Metrobank Account", "•••• 9012"));
        return list;
    }

    public List<PaymentMethod> getPaymentMethodsByUserId(String userId) {
        return paymentMethodRepository.findByUserId(userId);
    }

    public Optional<PaymentMethod> getDefaultPaymentMethod(String userId) {
        return paymentMethodRepository.findDefaultByUserId(userId);
    }

    public UserRepository getUserRepository() {
        return userRepository;
    }

    public WalletRepository getWalletRepository() {
        return walletRepository;
    }

    public PaymentMethodRepository getPaymentMethodRepository() {
        return paymentMethodRepository;
    }

    public TransactionRepository getTransactionRepository() {
        return transactionRepository;
    }

    public LedgerRepository getLedgerRepository() {
        return ledgerRepository;
    }

    private ReentrantLock getLock(String walletId) {
        return walletLocks.computeIfAbsent(walletId, k -> new ReentrantLock(true));
    }

    /**
     * Creates and persists a wallet for an existing user.
     * If an initial balance > 0 is provided, records the funding transaction and ledger credit.
     */
    public Wallet createWallet(String userId, BigDecimal initialBalance, String currency) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));

        if (walletRepository.findByUserId(userId).isPresent()) {
            throw new WalletException("User already possesses a wallet: " + userId);
        }

        BigDecimal validatedBalance = initialBalance != null ?
                MoneyUtil.validateNonNegativeAmount(initialBalance) : MoneyUtil.ZERO;
        String validatedCurrency = MoneyUtil.validateCurrency(currency);

        String walletId = IdGenerator.nextWalletId();
        Wallet wallet = new Wallet(walletId, userId, MoneyUtil.ZERO, validatedCurrency);

        return databaseManager.executeInTransaction(conn -> {
            walletRepository.save(wallet, conn);

            if (validatedBalance.compareTo(BigDecimal.ZERO) > 0) {
                String txId = IdGenerator.nextTransactionId();
                String refNum = IdGenerator.nextReferenceNumber();
                CashInOutTransaction initTxn = buildCashInOutTransaction(
                        txId, refNum, walletId, validatedBalance, MoneyUtil.ZERO,
                        "CASH_IN", null, "Initial wallet funding");
                transactionRepository.save(initTxn, conn);

                walletRepository.updateBalance(walletId, validatedBalance, conn);
                wallet.setBalance(validatedBalance);

                LedgerEntry entry = new LedgerEntry(
                        IdGenerator.nextLedgerEntryId(),
                        txId,
                        walletId,
                        LedgerEntryType.CREDIT,
                        validatedBalance,
                        MoneyUtil.ZERO,
                        validatedBalance,
                        validatedCurrency,
                        "Initial deposit / balance setup"
                );
                ledgerRepository.save(entry, conn);
            }
            return wallet;
        });
    }

    public Wallet getWallet(String walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new EntityNotFoundException("Wallet", walletId));
    }

    public Wallet getWalletByUserId(String userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException("Wallet for user", userId));
    }

    public BigDecimal getBalance(String walletId) {
        return getWallet(walletId).getBalance();
    }

    @Override
    public List<Transaction> getTransactionsByWalletId(String walletId) {
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        String cleanWalletId = walletId.trim();
        getWallet(cleanWalletId);
        return transactionRepository.findByWalletId(cleanWalletId);
    }

    @Override
    public List<Transaction> getRecentTransactions(String walletId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Transaction limit must be greater than zero");
        }
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        String cleanWalletId = walletId.trim();
        getWallet(cleanWalletId);
        return transactionRepository.findRecentByWalletId(cleanWalletId, limit);
    }

    @Override
    public Transaction sendMoneyToRecipient(String referenceNumber, String sourceWalletId,
                                            String recipientIdentifier, BigDecimal amount,
                                            BigDecimal fee, String description) {
        RecipientResolution resolved = resolveRecipient(recipientIdentifier, description);
        return transfer(referenceNumber, sourceWalletId, resolved.targetWalletId, amount, fee, resolved.description);
    }

    @Override
    public Transaction sendMoneyToRecipientWithPin(String referenceNumber, String sourceWalletId,
                                                   String recipientIdentifier, BigDecimal amount,
                                                   BigDecimal fee, String description, String rawPin) {
        RecipientResolution resolved = resolveRecipient(recipientIdentifier, description);
        return transferWithPin(referenceNumber, sourceWalletId, resolved.targetWalletId,
                amount, fee, resolved.description, rawPin);
    }

    private RecipientResolution resolveRecipient(String recipientIdentifier, String description) {
        if (recipientIdentifier == null || recipientIdentifier.trim().isEmpty()) {
            throw new IllegalArgumentException("Recipient identifier must not be null or empty");
        }
        String cleanRecipient = recipientIdentifier.trim();
        String targetWalletId;
        String recipientLabel;

        Optional<Wallet> directWallet = walletRepository.findById(cleanRecipient);
        if (directWallet.isPresent()) {
            targetWalletId = directWallet.get().getWalletId();
            recipientLabel = cleanRecipient;
        } else {
            User recipientUser = userRepository.findByIdentifier(cleanRecipient)
                    .orElseThrow(() -> new EntityNotFoundException("Recipient user", cleanRecipient));
            Wallet recipientWallet = walletRepository.findByUserId(recipientUser.getUserId())
                    .orElseThrow(() -> new EntityNotFoundException("Recipient wallet for user", recipientUser.getUserId()));
            targetWalletId = recipientWallet.getWalletId();
            recipientLabel = (recipientUser.getName() != null && !recipientUser.getName().trim().isEmpty())
                    ? recipientUser.getName()
                    : cleanRecipient;
        }

        String effectiveDescription = (description != null && !description.trim().isEmpty())
                ? description.trim()
                : "Transfer to " + recipientLabel;
        return new RecipientResolution(targetWalletId, effectiveDescription);
    }

    private static final class RecipientResolution {
        private final String targetWalletId;
        private final String description;

        private RecipientResolution(String targetWalletId, String description) {
            this.targetWalletId = targetWalletId;
            this.description = description;
        }
    }

    /**
     * Executes an atomic transfer of funds between two wallets.
     * Enforces positive amounts, 2-decimal precision, duplicate-request rejection,
     * available balance check, wallet locking against concurrent race conditions,
     * and synchronous ledger double-entry bookkeeping.
     */
    public Transaction transfer(String referenceNumber, String sourceWalletId, String targetWalletId,
                                BigDecimal amount, BigDecimal fee, String description) {
        validateReferenceNumber(referenceNumber);

        if (sourceWalletId == null || targetWalletId == null) {
            throw new IllegalArgumentException("Source and target wallet IDs must not be null");
        }
        if (sourceWalletId.equals(targetWalletId)) {
            throw new WalletException("Self-transfer is not permitted for demo scenario");
        }

        BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amount);
        BigDecimal validFee = fee != null ? MoneyUtil.validateNonNegativeAmount(fee) : MoneyUtil.ZERO;
        BigDecimal totalDebit = validAmount.add(validFee).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);

        if (requirePinAuthorization) {
            SessionManager.getInstance().requirePinAuthorization(null);
        }

        // Acquire wallet locks in canonical order to prevent deadlocks
        String firstLockId = sourceWalletId.compareTo(targetWalletId) < 0 ? sourceWalletId : targetWalletId;
        String secondLockId = sourceWalletId.compareTo(targetWalletId) < 0 ? targetWalletId : sourceWalletId;

        ReentrantLock lock1 = getLock(firstLockId);
        ReentrantLock lock2 = getLock(secondLockId);

        lock1.lock();
        try {
            lock2.lock();
            try {
                return databaseManager.executeInTransaction(conn -> {
                    // Check duplicate request reference
                    if (transactionRepository.existsByReferenceNumber(referenceNumber, conn)) {
                        throw new DuplicateTransactionException(referenceNumber,
                                "Duplicate transaction request reference: " + referenceNumber);
                    }

                    Wallet sourceWallet = walletRepository.findById(sourceWalletId, conn)
                            .orElseThrow(() -> new EntityNotFoundException("Source wallet", sourceWalletId));
                    authorizeOutgoingPayment(sourceWallet.getUserId());
                    Wallet targetWallet = walletRepository.findById(targetWalletId, conn)
                            .orElseThrow(() -> new EntityNotFoundException("Target wallet", targetWalletId));

                    if (sourceWallet.getStatus() != WalletStatus.ACTIVE) {
                        throw new WalletException("Source wallet is not active: " + sourceWallet.getStatus());
                    }
                    if (targetWallet.getStatus() != WalletStatus.ACTIVE) {
                        throw new WalletException("Target wallet is not active: " + targetWallet.getStatus());
                    }

                    // Check sufficient funds
                    BigDecimal sourceBalance = sourceWallet.getBalance();
                    if (sourceBalance.compareTo(totalDebit) < 0) {
                        throw new InsufficientFundsException(sourceWalletId, sourceBalance, totalDebit);
                    }

                    String txnId = IdGenerator.nextTransactionId();
                    String txnDesc = description != null ? description : "Transfer to wallet " + targetWalletId;
                    SendMoneyTransaction txn = new SendMoneyTransaction(
                            txnId,
                            referenceNumber,
                            sourceWalletId,
                            targetWalletId,
                            validAmount,
                            validFee,
                            targetWalletId,
                            txnDesc
                    );
                    txn.send();
                    transactionRepository.save(txn, conn);

                    // Debit source wallet
                    BigDecimal newSourceBalance = sourceBalance.subtract(totalDebit)
                            .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                    walletRepository.updateBalance(sourceWalletId, newSourceBalance, conn);

                    LedgerEntry debitEntry = new LedgerEntry(
                            IdGenerator.nextLedgerEntryId(),
                            txnId,
                            sourceWalletId,
                            LedgerEntryType.DEBIT,
                            totalDebit,
                            sourceBalance,
                            newSourceBalance,
                            sourceWallet.getCurrency(),
                            "Transfer debit" + (validFee.compareTo(BigDecimal.ZERO) > 0 ? " (includes fee " + validFee + ")" : "")
                    );
                    ledgerRepository.save(debitEntry, conn);

                    // Credit target wallet
                    BigDecimal targetBalance = targetWallet.getBalance();
                    BigDecimal newTargetBalance = targetBalance.add(validAmount)
                            .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                    walletRepository.updateBalance(targetWalletId, newTargetBalance, conn);

                    LedgerEntry creditEntry = new LedgerEntry(
                            IdGenerator.nextLedgerEntryId(),
                            txnId,
                            targetWalletId,
                            LedgerEntryType.CREDIT,
                            validAmount,
                            targetBalance,
                            newTargetBalance,
                            targetWallet.getCurrency(),
                            "Transfer credit from wallet " + sourceWalletId
                    );
                    ledgerRepository.save(creditEntry, conn);

                    return txn;
                });
            } finally {
                lock2.unlock();
            }
        } finally {
            lock1.unlock();
        }
    }

    /**
     * Executes an atomic Cash-In operation, crediting a wallet from a funding payment method.
     * Credit cash-in only on confirmed success; unknown/timeout outcomes remain pending.
     */
    public Transaction cashIn(String referenceNumber, String targetWalletId, String paymentMethodId,
                              BigDecimal amount, String description) {
        validateReferenceNumber(referenceNumber);
        BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amount);

        ReentrantLock lock = getLock(targetWalletId);
        lock.lock();
        try {
            return databaseManager.executeInTransaction(conn -> {
                if (transactionRepository.existsByReferenceNumber(referenceNumber, conn)) {
                    throw new DuplicateTransactionException(referenceNumber,
                            "Duplicate transaction request reference: " + referenceNumber);
                }

                Wallet wallet = walletRepository.findById(targetWalletId, conn)
                        .orElseThrow(() -> new EntityNotFoundException("Wallet", targetWalletId));

                if (wallet.getStatus() != WalletStatus.ACTIVE) {
                    throw new WalletException("Wallet is not active: " + wallet.getStatus());
                }

                PaymentMethod pm = null;
                if (paymentMethodId != null) {
                    pm = paymentMethodRepository.findById(paymentMethodId, conn)
                            .orElseThrow(() -> new EntityNotFoundException("PaymentMethod", paymentMethodId));
                    if (!pm.getUserId().equals(wallet.getUserId())) {
                        throw new WalletException("Payment method does not belong to wallet owner: " + paymentMethodId);
                    }
                    if (pm.getStatus() != PaymentMethodStatus.ACTIVE) {
                        throw new WalletException("Payment method is not active: " + pm.getStatus());
                    }
                } else {
                    pm = paymentMethodRepository.findDefaultByUserId(wallet.getUserId(), conn).orElse(null);
                    if (pm != null && pm.getStatus() != PaymentMethodStatus.ACTIVE) {
                        pm = null;
                    }
                }

                String channel = (pm != null && pm.getProviderName() != null) ? pm.getProviderName() : "Bank";
                String txnId = IdGenerator.nextTransactionId();
                String desc = description != null ? description : "Cash In via " + channel;

                BankApiResponse response = bankApiClient.cashIn(referenceNumber, pm, validAmount);

                CashInOutTransaction txn = new CashInOutTransaction(
                        txnId,
                        referenceNumber,
                        targetWalletId,
                        validAmount,
                        MoneyUtil.ZERO,
                        "CASH_IN",
                        channel,
                        response != null ? response.getProviderReference() : null
                );
                txn.setDescription(desc);
                if (response != null && response.getStatus() != null) {
                    txn.setProviderStatus(response.getStatus().name());
                }

                if (response != null && response.isSuccess()) {
                    // Credit cash-in only on success
                    txn.setStatus(TransactionStatus.COMPLETED);
                    txn.setCompletedAt(LocalDateTime.now());
                    transactionRepository.save(txn, conn);

                    BigDecimal balanceBefore = wallet.getBalance();
                    BigDecimal balanceAfter = balanceBefore.add(validAmount)
                            .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                    walletRepository.updateBalance(targetWalletId, balanceAfter, conn);
                    wallet.setBalance(balanceAfter);

                    LedgerEntry creditEntry = new LedgerEntry(
                            IdGenerator.nextLedgerEntryId(),
                            txnId,
                            targetWalletId,
                            LedgerEntryType.CREDIT,
                            validAmount,
                            balanceBefore,
                            balanceAfter,
                            wallet.getCurrency(),
                            desc
                    );
                    ledgerRepository.save(creditEntry, conn);
                } else if (response != null && response.isFailure()) {
                    // Credit cash-in only on success; on confirmed failure, mark FAILED without crediting
                    txn.setStatus(TransactionStatus.FAILED);
                    txn.setCompletedAt(LocalDateTime.now());
                    transactionRepository.save(txn, conn);
                } else {
                    // Timeout or unknown/pending outcomes stay pending without moving funds
                    txn.setStatus(TransactionStatus.PENDING);
                    transactionRepository.save(txn, conn);
                }

                return txn;
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * Executes an atomic Cash-Out operation, debiting a wallet to a destination payment method.
     * Reserves cash-out funds immediately until settlement, releasing them on confirmed failure.
     */
    public Transaction cashOut(String referenceNumber, String sourceWalletId, String paymentMethodId,
                               BigDecimal amount, BigDecimal fee, String description) {
        validateReferenceNumber(referenceNumber);
        BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amount);
        BigDecimal validFee = fee != null ? MoneyUtil.validateNonNegativeAmount(fee) : MoneyUtil.ZERO;
        BigDecimal totalDebit = validAmount.add(validFee).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);

        if (requirePinAuthorization) {
            SessionManager.getInstance().requirePinAuthorization(null);
        }

        ReentrantLock lock = getLock(sourceWalletId);
        lock.lock();
        try {
            return databaseManager.executeInTransaction(conn -> {
                if (transactionRepository.existsByReferenceNumber(referenceNumber, conn)) {
                    throw new DuplicateTransactionException(referenceNumber,
                            "Duplicate transaction request reference: " + referenceNumber);
                }

                Wallet wallet = walletRepository.findById(sourceWalletId, conn)
                        .orElseThrow(() -> new EntityNotFoundException("Wallet", sourceWalletId));
                authorizeOutgoingPayment(wallet.getUserId());

                if (wallet.getStatus() != WalletStatus.ACTIVE) {
                    throw new WalletException("Wallet is not active: " + wallet.getStatus());
                }

                BigDecimal balance = wallet.getBalance();
                if (balance.compareTo(totalDebit) < 0) {
                    throw new InsufficientFundsException(sourceWalletId, balance, totalDebit);
                }

                PaymentMethod pm = null;
                if (paymentMethodId != null) {
                    pm = paymentMethodRepository.findById(paymentMethodId, conn)
                            .orElseThrow(() -> new EntityNotFoundException("PaymentMethod", paymentMethodId));
                    if (!pm.getUserId().equals(wallet.getUserId())) {
                        throw new WalletException("Payment method does not belong to wallet owner: " + paymentMethodId);
                    }
                    if (pm.getStatus() != PaymentMethodStatus.ACTIVE) {
                        throw new WalletException("Payment method is not active: " + pm.getStatus());
                    }
                } else {
                    pm = paymentMethodRepository.findDefaultByUserId(wallet.getUserId(), conn).orElse(null);
                    if (pm != null && pm.getStatus() != PaymentMethodStatus.ACTIVE) {
                        pm = null;
                    }
                }

                String channel = (pm != null && pm.getProviderName() != null) ? pm.getProviderName() : "Bank";
                String txnId = IdGenerator.nextTransactionId();
                String desc = description != null ? description : "Cash Out to " + channel;

                CashInOutTransaction txn = new CashInOutTransaction(
                        txnId,
                        referenceNumber,
                        sourceWalletId,
                        validAmount,
                        validFee,
                        "CASH_OUT",
                        channel,
                        null
                );
                txn.setDescription(desc);
                txn.setStatus(TransactionStatus.PENDING);
                transactionRepository.save(txn, conn);

                // Reserve cash-out funds until settlement, releasing them on confirmed failure
                BigDecimal balanceAfterReserve = balance.subtract(totalDebit)
                        .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                walletRepository.updateBalance(sourceWalletId, balanceAfterReserve, conn);
                wallet.setBalance(balanceAfterReserve);

                String reserveDesc = desc + " (reserved funds" + (validFee.compareTo(BigDecimal.ZERO) > 0 ? " including fee " + validFee : "") + ")";
                LedgerEntry debitEntry = new LedgerEntry(
                        IdGenerator.nextLedgerEntryId(),
                        txnId,
                        sourceWalletId,
                        LedgerEntryType.DEBIT,
                        totalDebit,
                        balance,
                        balanceAfterReserve,
                        wallet.getCurrency(),
                        reserveDesc
                );
                ledgerRepository.save(debitEntry, conn);

                BankApiResponse response = bankApiClient.cashOut(referenceNumber, pm, validAmount);

                if (response != null) {
                    txn.setProviderReference(response.getProviderReference());
                    if (response.getStatus() != null) {
                        txn.setProviderStatus(response.getStatus().name());
                    }
                }

                if (response != null && response.isSuccess()) {
                    // Confirmed settlement: funds remain debited/reserved
                    txn.setStatus(TransactionStatus.COMPLETED);
                    txn.setCompletedAt(LocalDateTime.now());
                    transactionRepository.updateStatus(txnId, TransactionStatus.COMPLETED,
                            txn.getProviderReference(), txn.getProviderStatus(), txn.getCompletedAt(), conn);
                } else if (response != null && response.isFailure()) {
                    // Confirmed failure: release reserved funds back to wallet balance
                    BigDecimal balanceBeforeRefund = balanceAfterReserve;
                    BigDecimal balanceAfterRefund = balanceBeforeRefund.add(totalDebit)
                            .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                    walletRepository.updateBalance(sourceWalletId, balanceAfterRefund, conn);
                    wallet.setBalance(balanceAfterRefund);

                    LedgerEntry refundEntry = new LedgerEntry(
                            IdGenerator.nextLedgerEntryId(),
                            txnId,
                            sourceWalletId,
                            LedgerEntryType.CREDIT,
                            totalDebit,
                            balanceBeforeRefund,
                            balanceAfterRefund,
                            wallet.getCurrency(),
                            "Cash Out failed - released reserved funds"
                    );
                    ledgerRepository.save(refundEntry, conn);

                    txn.setStatus(TransactionStatus.FAILED);
                    txn.setCompletedAt(LocalDateTime.now());
                    transactionRepository.updateStatus(txnId, TransactionStatus.FAILED,
                            txn.getProviderReference(), txn.getProviderStatus(), txn.getCompletedAt(), conn);
                } else {
                    // Timeout or unknown/pending: stays pending and funds remain reserved
                    txn.setStatus(TransactionStatus.PENDING);
                    transactionRepository.updateStatus(txnId, TransactionStatus.PENDING,
                            txn.getProviderReference(), txn.getProviderStatus(), null, conn);
                }

                return txn;
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * Looks up provider status for a pending transaction and applies deterministic settlement or release.
     * Repeated provider responses never duplicate money movement.
     */
    public Transaction statusLookup(String referenceNumber) {
        return applyProviderResponse(referenceNumber, null);
    }

    public Transaction queryTransactionStatus(String referenceNumber) {
        return statusLookup(referenceNumber);
    }

    public Transaction syncStatus(String referenceNumber) {
        return statusLookup(referenceNumber);
    }

    public Transaction handleProviderResponse(String referenceNumber, BankApiResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("Bank response cannot be null");
        }
        return applyProviderResponse(referenceNumber, response);
    }

    private Transaction applyProviderResponse(String referenceNumber, BankApiResponse directResponse) {
        validateReferenceNumber(referenceNumber);

        Transaction existingTxn = transactionRepository.findByReferenceNumber(referenceNumber)
                .orElseThrow(() -> new EntityNotFoundException("Transaction with reference", referenceNumber));

        // Repeated provider responses never duplicate money movement
        if (existingTxn.getStatus() == TransactionStatus.COMPLETED || existingTxn.getStatus() == TransactionStatus.FAILED) {
            return existingTxn;
        }

        String walletId = existingTxn.getType() == TransactionType.CASH_IN
                ? existingTxn.getTargetWalletId()
                : existingTxn.getSourceWalletId();

        if (walletId == null) {
            return existingTxn;
        }

        ReentrantLock lock = getLock(walletId);
        lock.lock();
        try {
            return databaseManager.executeInTransaction(conn -> {
                Transaction currentTxn = transactionRepository.findByReferenceNumber(referenceNumber, conn)
                        .orElseThrow(() -> new EntityNotFoundException("Transaction with reference", referenceNumber));

                // Re-check after acquiring lock and transaction boundary
                if (currentTxn.getStatus() == TransactionStatus.COMPLETED || currentTxn.getStatus() == TransactionStatus.FAILED) {
                    return currentTxn;
                }

                BankApiResponse response = directResponse != null
                        ? directResponse
                        : bankApiClient.checkStatus(referenceNumber, currentTxn.getProviderReference());

                if (response == null) {
                    return currentTxn;
                }

                if (response.getProviderReference() != null) {
                    currentTxn.setProviderReference(response.getProviderReference());
                }
                if (response.getStatus() != null) {
                    currentTxn.setProviderStatus(response.getStatus().name());
                }

                if (currentTxn.getType() == TransactionType.CASH_IN) {
                    if (response.isSuccess()) {
                        Wallet wallet = walletRepository.findById(currentTxn.getTargetWalletId(), conn)
                                .orElseThrow(() -> new EntityNotFoundException("Wallet", currentTxn.getTargetWalletId()));

                        BigDecimal balanceBefore = wallet.getBalance();
                        BigDecimal balanceAfter = balanceBefore.add(currentTxn.getAmount())
                                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                        walletRepository.updateBalance(wallet.getWalletId(), balanceAfter, conn);
                        wallet.setBalance(balanceAfter);

                        LedgerEntry creditEntry = new LedgerEntry(
                                IdGenerator.nextLedgerEntryId(),
                                currentTxn.getTransactionId(),
                                wallet.getWalletId(),
                                LedgerEntryType.CREDIT,
                                currentTxn.getAmount(),
                                balanceBefore,
                                balanceAfter,
                                wallet.getCurrency(),
                                "Settled Cash In: " + currentTxn.getDescription()
                        );
                        ledgerRepository.save(creditEntry, conn);

                        currentTxn.setStatus(TransactionStatus.COMPLETED);
                        currentTxn.setCompletedAt(LocalDateTime.now());
                        transactionRepository.updateStatus(currentTxn.getTransactionId(), TransactionStatus.COMPLETED,
                                currentTxn.getProviderReference(), currentTxn.getProviderStatus(), currentTxn.getCompletedAt(), conn);
                    } else if (response.isFailure()) {
                        // Credit cash-in only on success; failed cash-in does not credit wallet
                        currentTxn.setStatus(TransactionStatus.FAILED);
                        currentTxn.setCompletedAt(LocalDateTime.now());
                        transactionRepository.updateStatus(currentTxn.getTransactionId(), TransactionStatus.FAILED,
                                currentTxn.getProviderReference(), currentTxn.getProviderStatus(), currentTxn.getCompletedAt(), conn);
                    } else {
                        // Unknown / pending / timeout outcomes stay pending
                        transactionRepository.updateStatus(currentTxn.getTransactionId(), TransactionStatus.PENDING,
                                currentTxn.getProviderReference(), currentTxn.getProviderStatus(), null, conn);
                    }
                } else if (currentTxn.getType() == TransactionType.CASH_OUT) {
                    if (response.isSuccess()) {
                        // Confirmed settlement: funds were already reserved
                        currentTxn.setStatus(TransactionStatus.COMPLETED);
                        currentTxn.setCompletedAt(LocalDateTime.now());
                        transactionRepository.updateStatus(currentTxn.getTransactionId(), TransactionStatus.COMPLETED,
                                currentTxn.getProviderReference(), currentTxn.getProviderStatus(), currentTxn.getCompletedAt(), conn);
                    } else if (response.isFailure()) {
                        // Confirmed failure: release reserved funds back to source wallet
                        Wallet wallet = walletRepository.findById(currentTxn.getSourceWalletId(), conn)
                                .orElseThrow(() -> new EntityNotFoundException("Wallet", currentTxn.getSourceWalletId()));

                        BigDecimal balanceBefore = wallet.getBalance();
                        BigDecimal balanceAfter = balanceBefore.add(currentTxn.getTotalAmount())
                                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                        walletRepository.updateBalance(wallet.getWalletId(), balanceAfter, conn);
                        wallet.setBalance(balanceAfter);

                        LedgerEntry releaseEntry = new LedgerEntry(
                                IdGenerator.nextLedgerEntryId(),
                                currentTxn.getTransactionId(),
                                wallet.getWalletId(),
                                LedgerEntryType.CREDIT,
                                currentTxn.getTotalAmount(),
                                balanceBefore,
                                balanceAfter,
                                wallet.getCurrency(),
                                "Cash Out failed - released reserved funds"
                        );
                        ledgerRepository.save(releaseEntry, conn);

                        currentTxn.setStatus(TransactionStatus.FAILED);
                        currentTxn.setCompletedAt(LocalDateTime.now());
                        transactionRepository.updateStatus(currentTxn.getTransactionId(), TransactionStatus.FAILED,
                                currentTxn.getProviderReference(), currentTxn.getProviderStatus(), currentTxn.getCompletedAt(), conn);
                    } else {
                        // Unknown / pending / timeout outcomes stay pending; funds remain reserved
                        transactionRepository.updateStatus(currentTxn.getTransactionId(), TransactionStatus.PENDING,
                                currentTxn.getProviderReference(), currentTxn.getProviderStatus(), null, conn);
                    }
                }

                return currentTxn;
            });
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Transaction transferWithPin(String referenceNumber, String sourceWalletId, String targetWalletId,
                                       BigDecimal amount, BigDecimal fee, String description, String rawPin) {
        authorizeWithPin(sourceWalletId, rawPin);
        return transfer(referenceNumber, sourceWalletId, targetWalletId, amount, fee, description);
    }

    @Override
    public Transaction cashOutWithPin(String referenceNumber, String sourceWalletId, String paymentMethodId,
                                      BigDecimal amount, BigDecimal fee, String description, String rawPin) {
        authorizeWithPin(sourceWalletId, rawPin);
        return cashOut(referenceNumber, sourceWalletId, paymentMethodId, amount, fee, description);
    }

    private void authorizeWithPin(String sourceWalletId, String rawPin) {
        if (sourceWalletId == null) {
            throw new IllegalArgumentException("Source wallet ID must not be null");
        }
        SessionManager session = SessionManager.getInstance();
        if (!session.isLoggedIn()) {
            if (session.isExpiredDueToInactivity()) {
                throw new WalletException("Session expired due to inactivity. Please log in again.");
            }
            if (session.isLockedOutByPin()) {
                throw new WalletException("Session locked after excessive failed PIN attempts.");
            }
            throw new WalletException("Active authenticated session required for outgoing payments.");
        }

        Wallet sourceWallet = getWallet(sourceWalletId);
        if (!session.getCurrentUser().getUserId().equals(sourceWallet.getUserId())) {
            throw new WalletException("Wallet ownership mismatch: active session user cannot authorize this wallet.");
        }
        if (authService == null) {
            throw new WalletException("AuthService is required for PIN verification.");
        }
        try {
            if (!authService.verifyPin(sourceWallet.getUserId(), rawPin)) {
                throw new WalletException("Invalid transaction PIN. Outgoing payment blocked.");
            }
        } catch (SecurityException ex) {
            throw new WalletException(ex.getMessage(), ex);
        }
    }

    private void authorizeOutgoingPayment(String walletOwnerUserId) {
        SessionManager session = SessionManager.getInstance();
        if (requirePinAuthorization || session.isLoggedIn()
                || session.isExpiredDueToInactivity() || session.isLockedOutByPin()) {
            session.requirePinAuthorization(walletOwnerUserId);
        }
    }

    /**
     * Executes custom transactional logic inside an ACID transaction boundary.
     * Useful for executing complex workflows (e.g., loans, investments) with automatic rollback on error.
     */
    public <T> T executeInTransaction(com.otog.database.TransactionCallback<T> action) {
        return databaseManager.executeInTransaction(action);
    }

    private CashInOutTransaction buildCashInOutTransaction(String txnId, String referenceNumber, String walletId,
                                                             BigDecimal amount, BigDecimal fee, String opType,
                                                             PaymentMethod pm, String desc) {
        String channel = pm != null ? pm.getProviderName() : "Direct";
        CashInOutTransaction txn = new CashInOutTransaction(
                txnId, referenceNumber, walletId, amount, fee, opType, channel);
        txn.setDescription(desc);
        txn.execute();
        return txn;
    }

    private void validateReferenceNumber(String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            throw new IllegalArgumentException("Transaction reference number cannot be null or empty");
        }
    }
}
