package com.otog.service;

import com.otog.database.DatabaseManager;
import com.otog.exception.*;
import com.otog.model.*;
import com.otog.repository.*;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service orchestrating wallet domain logic, transaction correctness, atomic commits,
 * and concurrency protection against double-spending.
 */
public class WalletService implements WalletOperations, InvestmentService {
    private final DatabaseManager databaseManager;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerRepository ledgerRepository;
    private final InvestmentRepository investmentRepository;

    private final ConcurrentHashMap<String, ReentrantLock> walletLocks = new ConcurrentHashMap<>();
    private final AuthService authService;
    private final boolean requirePinAuthorization;
    private BankApiClient bankApiClient;
    private final AdminActionRepository adminActionRepository;
    private final AdminService adminService;
    private NotificationService notificationService;

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
        this.investmentRepository = new InvestmentRepository(databaseManager);
        this.authService = authService;
        this.requirePinAuthorization = requirePinAuthorization;
        this.bankApiClient = new BankApiClient();
        this.adminActionRepository = new AdminActionRepository(databaseManager);
        this.adminService = new AdminServiceImpl(databaseManager, this.userRepository,
                this.walletRepository, this.transactionRepository, this.adminActionRepository,
                new CredentialRepository(databaseManager));
        this.notificationService = new NotificationServiceImpl(databaseManager);
    }

    @Override
    public NotificationService getNotificationService() {
        return notificationService;
    }

    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService != null
                ? notificationService
                : new NotificationServiceImpl(databaseManager);
    }

    @Override
    public AdminService getAdminService() {
        return adminService;
    }

    @Override
    public SendMoneyService createSendMoneyService(AuthService effectiveAuthService) {
        return new SendMoneyService(this, this.userRepository, effectiveAuthService);
    }

    public AdminActionRepository getAdminActionRepository() {
        return adminActionRepository;
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

    @Override
    public BankResponseStatus getCurrentProviderMode() {
        if (bankApiClient != null) {
            Optional<MockBankAdapter> mock = bankApiClient.findAdapter(MockBankAdapter.class);
            if (mock.isPresent()) {
                return mock.get().getMode();
            }
        }
        return BankResponseStatus.SUCCESS;
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
        if (userId == null || userId.trim().isEmpty() || type == null
                || providerName == null || providerName.trim().isEmpty()) {
            throw new IllegalArgumentException("User ID, payment method type, and provider name cannot be null or empty");
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
        List<PaymentMethod> existing = paymentMethodRepository.findByUserId(userId);
        if (!existing.isEmpty()) {
            return existing;
        }
        List<PaymentMethod> list = new ArrayList<>();
        for (OtogConfig.DemoBankAccount demo : OtogConfig.getInstance().getDemoPaymentMethods()) {
            PaymentMethod pm = linkPaymentMethod(
                    userId,
                    PaymentMethodType.BANK_ACCOUNT,
                    demo.providerName(),
                    "Demo " + demo.providerName() + " Account",
                    demo.maskedNumber()
            );
            list.add(pm);
        }
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

    public InvestmentRepository getInvestmentRepository() {
        return investmentRepository;
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
    public List<Transaction> getTransactionHistory(String walletId, TransactionFilter filter) {
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        String cleanWalletId = walletId.trim();
        getWallet(cleanWalletId);
        return Collections.unmodifiableList(transactionRepository.findByWalletIdFiltered(cleanWalletId, filter));
    }

    @Override
    public String getTransactionReceipt(String walletId, String transactionIdOrRef) {
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        if (transactionIdOrRef == null || transactionIdOrRef.trim().isEmpty()) {
            throw new IllegalArgumentException("Transaction ID or reference number must not be null or empty");
        }
        String cleanWalletId = walletId.trim();
        String key = transactionIdOrRef.trim();
        getWallet(cleanWalletId);

        Transaction txn = transactionRepository.findById(key)
                .or(() -> transactionRepository.findByReferenceNumber(key))
                .orElseThrow(() -> new EntityNotFoundException("Transaction", key));

        if (!isWalletParticipant(cleanWalletId, txn)) {
            throw new SecurityException(
                    "Access denied: Transaction " + key + " does not belong to wallet " + cleanWalletId);
        }

        String counterpartyDisplay = resolveMaskedCounterpartyForReceipt(cleanWalletId, txn);
        return txn.getReceipt(cleanWalletId, counterpartyDisplay);
    }

    @Override
    public Transaction createRepeatTransactionDraft(String walletId, String transactionIdOrRef,
                                                    String newReferenceNumber) {
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        if (transactionIdOrRef == null || transactionIdOrRef.trim().isEmpty()) {
            throw new IllegalArgumentException("Transaction ID or reference number must not be null or empty");
        }
        String cleanWalletId = walletId.trim();
        String key = transactionIdOrRef.trim();
        getWallet(cleanWalletId);

        Transaction txn = transactionRepository.findById(key)
                .or(() -> transactionRepository.findByReferenceNumber(key))
                .orElseThrow(() -> new EntityNotFoundException("Transaction", key));

        if (!isWalletParticipant(cleanWalletId, txn)) {
            throw new SecurityException(
                    "Access denied: Cannot clone another wallet's transaction " + key);
        }

        return ReceiptBuilder.createRepeatDraft(txn, newReferenceNumber);
    }

    @Override
    public List<Notification> getNotifications(String userId, boolean unreadOnly) {
        return notificationService.getNotificationsForUser(userId, unreadOnly);
    }

    @Override
    public int getUnreadNotificationCount(String userId) {
        return notificationService.getUnreadCount(userId);
    }

    @Override
    public void markNotificationRead(String userId, String notificationId) {
        notificationService.markNotificationRead(userId, notificationId);
    }

    @Override
    public void markNotificationUnread(String userId, String notificationId) {
        notificationService.markNotificationUnread(userId, notificationId);
    }

    @Override
    public int markAllNotificationsRead(String userId) {
        return notificationService.markAllNotificationsRead(userId);
    }

    @Override
    public int retryPendingNotifications() {
        return notificationService.retryPendingNotifications();
    }

    private boolean isWalletParticipant(String walletId, Transaction txn) {
        return walletId.equals(txn.getSourceWalletId()) || walletId.equals(txn.getTargetWalletId());
    }

    private String resolveMaskedCounterpartyForReceipt(String viewingWalletId, Transaction txn) {
        if (txn instanceof CashInOutTransaction cio) {
            return (cio.getChannel() != null && !cio.getChannel().trim().isEmpty())
                    ? cio.getChannel().trim()
                    : "External Bank / OTC";
        }
        String counterpartyWalletId = null;
        if (viewingWalletId.equals(txn.getSourceWalletId())) {
            counterpartyWalletId = txn.getTargetWalletId();
        } else if (viewingWalletId.equals(txn.getTargetWalletId())) {
            counterpartyWalletId = txn.getSourceWalletId();
        }
        if (counterpartyWalletId != null && !counterpartyWalletId.trim().isEmpty()) {
            Optional<Wallet> counterpartyWallet = walletRepository.findById(counterpartyWalletId);
            if (counterpartyWallet.isPresent() && counterpartyWallet.get().getUserId() != null) {
                User counterpartyUser = userRepository.findById(counterpartyWallet.get().getUserId()).orElse(null);
                if (counterpartyUser != null) {
                    return NotificationServiceImpl.formatMaskedCounterparty(counterpartyUser, counterpartyWalletId);
                }
            }
            return "Wallet " + ReceiptBuilder.maskIdentifier(counterpartyWalletId);
        }
        return null;
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

        CommittedTransfer committed;
        lock1.lock();
        try {
            lock2.lock();
            try {
                committed = databaseManager.executeInTransaction(conn -> {
                    // Check duplicate request reference
                    if (transactionRepository.existsByReferenceNumber(referenceNumber, conn)) {
                        throw new DuplicateTransactionException(referenceNumber,
                                "Duplicate transaction request reference: " + referenceNumber);
                    }

                    Wallet sourceWallet = walletRepository.findById(sourceWalletId, conn)
                            .orElseThrow(() -> new EntityNotFoundException("Source wallet", sourceWalletId));
                    User senderUser = validateAccountNotSuspended(sourceWallet.getUserId(), conn);
                    authorizeOutgoingPayment(sourceWallet.getUserId());
                    Wallet targetWallet = walletRepository.findById(targetWalletId, conn)
                            .orElseThrow(() -> new EntityNotFoundException("Target wallet", targetWalletId));
                    User recipientUser = targetWallet.getUserId() != null
                            ? userRepository.findById(targetWallet.getUserId(), conn).orElse(null)
                            : null;

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

                    String txnDesc = description != null ? description : "Transfer to wallet " + targetWalletId;
                    SendMoneyTransaction txn = TransactionFactory.createSendMoney(
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
                            txn.getTransactionId(),
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
                            txn.getTransactionId(),
                            targetWalletId,
                            LedgerEntryType.CREDIT,
                            validAmount,
                            targetBalance,
                            newTargetBalance,
                            targetWallet.getCurrency(),
                            "Transfer credit from wallet " + sourceWalletId
                    );
                    ledgerRepository.save(creditEntry, conn);

                    return new CommittedTransfer(txn, senderUser, recipientUser);
                });
                // Persist sender & recipient notifications strictly AFTER commit while holding locks to avoid SQLite lock contention
                dispatchTransferNotificationsPostCommit(committed);
                return committed.txn();
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
        if (targetWalletId == null || targetWalletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Target wallet ID must not be null or empty");
        }
        BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amount);

        ReentrantLock lock = getLock(targetWalletId);
        CommittedCashOp committed;
        lock.lock();
        try {
            committed = databaseManager.executeInTransaction(conn -> {
                if (transactionRepository.existsByReferenceNumber(referenceNumber, conn)) {
                    throw new DuplicateTransactionException(referenceNumber,
                            "Duplicate transaction request reference: " + referenceNumber);
                }

                Wallet wallet = walletRepository.findById(targetWalletId, conn)
                        .orElseThrow(() -> new EntityNotFoundException("Wallet", targetWalletId));
                validateAccountNotSuspended(wallet.getUserId(), conn);

                if (wallet.getStatus() != WalletStatus.ACTIVE) {
                    throw new WalletException("Wallet is not active: " + wallet.getStatus());
                }

                PaymentMethod pm = null;
                PaymentMethod bankPm = null;
                String channel;
                if (paymentMethodId != null) {
                    pm = paymentMethodRepository.findById(paymentMethodId, conn)
                            .orElseThrow(() -> new EntityNotFoundException("PaymentMethod", paymentMethodId));
                    if (!pm.getUserId().equals(wallet.getUserId())) {
                        throw new WalletException("Payment method does not belong to wallet owner: " + paymentMethodId);
                    }
                    if (pm.getStatus() != PaymentMethodStatus.ACTIVE) {
                        throw new WalletException("Payment method is not active: " + pm.getStatus());
                    }
                    channel = (pm.getProviderName() != null) ? pm.getProviderName() : "Bank";
                    bankPm = pm;
                } else {
                    String explicitChannel = resolveChannelFromDescription(description, "Cash In via ");
                    if (explicitChannel != null) {
                        channel = explicitChannel;
                        bankPm = new PaymentMethod(null, wallet.getUserId(), PaymentMethodType.BANK_ACCOUNT,
                                channel, wallet.getUserId(), "N/A");
                    } else {
                        pm = paymentMethodRepository.findDefaultByUserId(wallet.getUserId(), conn).orElse(null);
                        if (pm != null && pm.getStatus() != PaymentMethodStatus.ACTIVE) {
                            pm = null;
                        }
                        channel = (pm != null && pm.getProviderName() != null) ? pm.getProviderName() : "Bank";
                        bankPm = pm;
                    }
                }

                String txnId = IdGenerator.nextTransactionId();
                String desc = description != null ? description : "Cash In via " + channel;

                BankApiResponse response = bankApiClient.cashIn(referenceNumber, bankPm, validAmount);

                CashInOutTransaction txn = TransactionFactory.createCashIn(
                        txnId,
                        referenceNumber,
                        targetWalletId,
                        validAmount,
                        channel,
                        response != null ? response.getProviderReference() : null,
                        desc
                );
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

                return new CommittedCashOp(txn, wallet.getUserId(), channel, true);
            });
            dispatchCashNotificationPostCommit(committed);
            return committed.txn();
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
        if (sourceWalletId == null || sourceWalletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Source wallet ID must not be null or empty");
        }
        BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amount);
        BigDecimal validFee = fee != null ? MoneyUtil.validateNonNegativeAmount(fee) : MoneyUtil.ZERO;
        BigDecimal totalDebit = validAmount.add(validFee).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);

        if (requirePinAuthorization) {
            SessionManager.getInstance().requirePinAuthorization(null);
        }

        ReentrantLock lock = getLock(sourceWalletId);
        CommittedCashOp committed;
        lock.lock();
        try {
            committed = databaseManager.executeInTransaction(conn -> {
                if (transactionRepository.existsByReferenceNumber(referenceNumber, conn)) {
                    throw new DuplicateTransactionException(referenceNumber,
                            "Duplicate transaction request reference: " + referenceNumber);
                }

                Wallet wallet = walletRepository.findById(sourceWalletId, conn)
                        .orElseThrow(() -> new EntityNotFoundException("Wallet", sourceWalletId));
                validateAccountNotSuspended(wallet.getUserId(), conn);
                authorizeOutgoingPayment(wallet.getUserId());

                if (wallet.getStatus() != WalletStatus.ACTIVE) {
                    throw new WalletException("Wallet is not active: " + wallet.getStatus());
                }

                BigDecimal balance = wallet.getBalance();
                if (balance.compareTo(totalDebit) < 0) {
                    throw new InsufficientFundsException(sourceWalletId, balance, totalDebit);
                }

                PaymentMethod pm = null;
                PaymentMethod bankPm = null;
                String channel;
                if (paymentMethodId != null) {
                    pm = paymentMethodRepository.findById(paymentMethodId, conn)
                            .orElseThrow(() -> new EntityNotFoundException("PaymentMethod", paymentMethodId));
                    if (!pm.getUserId().equals(wallet.getUserId())) {
                        throw new WalletException("Payment method does not belong to wallet owner: " + paymentMethodId);
                    }
                    if (pm.getStatus() != PaymentMethodStatus.ACTIVE) {
                        throw new WalletException("Payment method is not active: " + pm.getStatus());
                    }
                    channel = (pm.getProviderName() != null) ? pm.getProviderName() : "Bank";
                    bankPm = pm;
                } else {
                    String explicitChannel = resolveChannelFromDescription(description, "Cash Out to ");
                    if (explicitChannel != null) {
                        channel = explicitChannel;
                        bankPm = new PaymentMethod(null, wallet.getUserId(), PaymentMethodType.BANK_ACCOUNT,
                                channel, wallet.getUserId(), "N/A");
                    } else {
                        pm = paymentMethodRepository.findDefaultByUserId(wallet.getUserId(), conn).orElse(null);
                        if (pm != null && pm.getStatus() != PaymentMethodStatus.ACTIVE) {
                            pm = null;
                        }
                        channel = (pm != null && pm.getProviderName() != null) ? pm.getProviderName() : "Bank";
                        bankPm = pm;
                    }
                }

                String txnId = IdGenerator.nextTransactionId();
                String desc = description != null ? description : "Cash Out to " + channel;

                CashInOutTransaction txn = TransactionFactory.createCashOut(
                        txnId,
                        referenceNumber,
                        sourceWalletId,
                        validAmount,
                        validFee,
                        channel,
                        null,
                        desc
                );
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

                BankApiResponse response = bankApiClient.cashOut(referenceNumber, bankPm, validAmount);

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

                return new CommittedCashOp(txn, wallet.getUserId(), channel, true);
            });
            dispatchCashNotificationPostCommit(committed);
            return committed.txn();
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

        if (existingTxn.getType() != TransactionType.CASH_IN && existingTxn.getType() != TransactionType.CASH_OUT) {
            throw new WalletException("Bank status lookup is only supported for CASH_IN and CASH_OUT transactions: "
                    + existingTxn.getType());
        }

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
        CommittedCashOp committed;
        lock.lock();
        try {
            committed = databaseManager.executeInTransaction(conn -> {
                Transaction currentTxn = transactionRepository.findByReferenceNumber(referenceNumber, conn)
                        .orElseThrow(() -> new EntityNotFoundException("Transaction with reference", referenceNumber));

                String channel = (currentTxn instanceof CashInOutTransaction cio && cio.getChannel() != null)
                        ? cio.getChannel()
                        : "Bank";
                String ownerUserId = walletRepository.findById(walletId, conn)
                        .map(Wallet::getUserId)
                        .orElse(null);

                // Re-check after acquiring lock and transaction boundary
                if (currentTxn.getStatus() == TransactionStatus.COMPLETED || currentTxn.getStatus() == TransactionStatus.FAILED) {
                    return new CommittedCashOp(currentTxn, ownerUserId, channel, false);
                }

                BankApiResponse response = directResponse != null
                        ? directResponse
                        : bankApiClient.checkStatus(referenceNumber, currentTxn.getProviderReference());

                if (response == null) {
                    return new CommittedCashOp(currentTxn, ownerUserId, channel, false);
                }

                if (response.getProviderReference() != null) {
                    currentTxn.setProviderReference(response.getProviderReference());
                }
                if (response.getStatus() != null) {
                    currentTxn.setProviderStatus(response.getStatus().name());
                }

                boolean transitioned = false;
                if (currentTxn.getType() == TransactionType.CASH_IN) {
                    if (response.isSuccess()) {
                        Wallet wallet = walletRepository.findById(currentTxn.getTargetWalletId(), conn)
                                .orElseThrow(() -> new EntityNotFoundException("Wallet", currentTxn.getTargetWalletId()));
                        ownerUserId = wallet.getUserId();

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
                        transitioned = true;
                    } else if (response.isFailure()) {
                        // Credit cash-in only on success; failed cash-in does not credit wallet
                        currentTxn.setStatus(TransactionStatus.FAILED);
                        currentTxn.setCompletedAt(LocalDateTime.now());
                        transactionRepository.updateStatus(currentTxn.getTransactionId(), TransactionStatus.FAILED,
                                currentTxn.getProviderReference(), currentTxn.getProviderStatus(), currentTxn.getCompletedAt(), conn);
                        transitioned = true;
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
                        transitioned = true;
                    } else if (response.isFailure()) {
                        // Confirmed failure: release reserved funds back to source wallet
                        Wallet wallet = walletRepository.findById(currentTxn.getSourceWalletId(), conn)
                                .orElseThrow(() -> new EntityNotFoundException("Wallet", currentTxn.getSourceWalletId()));
                        ownerUserId = wallet.getUserId();

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
                        transitioned = true;
                    } else {
                        // Unknown / pending / timeout outcomes stay pending; funds remain reserved
                        transactionRepository.updateStatus(currentTxn.getTransactionId(), TransactionStatus.PENDING,
                                currentTxn.getProviderReference(), currentTxn.getProviderStatus(), null, conn);
                    }
                }

                return new CommittedCashOp(currentTxn, ownerUserId, channel, transitioned);
            });
            if (committed.shouldNotify()) {
                dispatchCashNotificationPostCommit(committed);
            }
            return committed.txn();
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

    private User validateAccountNotSuspended(String userId, Connection conn) throws java.sql.SQLException {
        if (userId == null) {
            return null;
        }
        User user = userRepository.findById(userId, conn)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new WalletException("Suspended accounts cannot initiate payments: User " + userId + " is suspended.");
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new WalletException("Account is locked: User " + userId + " is locked.");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new WalletException("Account is not active: User " + userId + " status is " + user.getStatus());
        }
        return user;
    }

    private void authorizeOutgoingPayment(String walletOwnerUserId) {
        if (walletOwnerUserId != null) {
            userRepository.findById(walletOwnerUserId).ifPresent(user -> {
                if (user.getStatus() == UserStatus.SUSPENDED) {
                    throw new WalletException("Suspended accounts cannot initiate payments: User " + user.getUserId() + " is suspended.");
                }
                if (user.getStatus() != UserStatus.ACTIVE) {
                    throw new WalletException("Account is not active: User " + user.getUserId() + " status is " + user.getStatus());
                }
            });
        }
        SessionManager session = SessionManager.getInstance();
        if (requirePinAuthorization || session.isLoggedIn()
                || session.isExpiredDueToInactivity() || session.isLockedOutByPin()) {
            session.requirePinAuthorization(walletOwnerUserId);
        }
    }

    public void suspendUser(String adminUserId, String targetUserId, String reason) {
        adminService.suspendUser(adminUserId, targetUserId, reason);
    }

    public void reactivateUser(String adminUserId, String targetUserId, String reason) {
        adminService.reactivateUser(adminUserId, targetUserId, reason);
    }

    public List<User> getAllUsers(String requesterUserId) {
        return adminService.getAllUsers(requesterUserId);
    }

    public List<Wallet> getAllWallets(String requesterUserId) {
        return adminService.getAllWallets(requesterUserId);
    }

    public List<Transaction> getAllTransactions(String requesterUserId) {
        return adminService.getAllTransactions(requesterUserId);
    }

    public List<Transaction> getUserTransactions(String requesterUserId, String targetUserId) {
        return adminService.getUserTransactions(requesterUserId, targetUserId);
    }

    public List<Transaction> getTransactionHistory(String requesterUserId, String walletId) {
        return getTransactionHistory(requesterUserId, walletId, TransactionFilter.empty());
    }

    public List<Transaction> getTransactionHistory(String requesterUserId, String walletId, TransactionFilter filter) {
        SessionManager session = SessionManager.getInstance();
        session.requireAuthentication();

        if (requesterUserId != null && !requesterUserId.trim().isEmpty()) {
            session.requireActiveSessionFor(requesterUserId.trim());
        }

        User sessionUser = session.getCurrentUser();
        User requester = userRepository.findById(sessionUser.getUserId())
                .orElseThrow(() -> new SecurityException("Access denied: Unknown user " + sessionUser.getUserId()));
        if (requester.getStatus() != UserStatus.ACTIVE) {
            throw new SecurityException("Access denied: Requester account is " + requester.getStatus() + ".");
        }
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        Wallet wallet = getWallet(walletId.trim());
        if (requester.getRole() != UserRole.ADMIN && !wallet.getUserId().equals(requester.getUserId())) {
            throw new SecurityException("Access denied: Ordinary users cannot inspect another user's transaction history.");
        }
        List<Transaction> txns = transactionRepository.findByWalletIdFiltered(wallet.getWalletId(), filter);
        if (requester.getRole() == UserRole.ADMIN && !wallet.getUserId().equals(requester.getUserId())) {
            AdminAction action = new AdminAction(
                    IdGenerator.nextAdminActionId(),
                    requester.getUserId(),
                    "INSPECT_USER_HISTORY",
                    wallet.getUserId(),
                    "Administrative inspection",
                    "Inspected transaction history of wallet " + wallet.getWalletId()
            );
            adminActionRepository.save(action);
        }
        return txns;
    }

    private void dispatchTransferNotificationsPostCommit(CommittedTransfer committed) {
        if (committed == null || committed.txn() == null || notificationService == null) {
            return;
        }
        try {
            notificationService.notifyTransferCommitted(committed.txn(), committed.sender(), committed.recipient());
        } catch (RuntimeException ex) {
            System.err.println("[WARN] Post-commit transfer notification dispatch encountered an error for ref "
                    + committed.txn().getReferenceNumber() + ": " + ex.getMessage());
        }
    }

    private void dispatchCashNotificationPostCommit(CommittedCashOp committed) {
        if (committed == null || committed.txn() == null || notificationService == null) {
            return;
        }
        try {
            notificationService.notifyCashOperationCommitted(
                    committed.txn(), committed.ownerUserId(), committed.channel());
        } catch (RuntimeException ex) {
            System.err.println("[WARN] Post-commit cash notification dispatch encountered an error for ref "
                    + committed.txn().getReferenceNumber() + ": " + ex.getMessage());
        }
    }

    private record CommittedTransfer(SendMoneyTransaction txn, User sender, User recipient) {
    }

    private record CommittedCashOp(Transaction txn, String ownerUserId, String channel, boolean shouldNotify) {
    }

    public List<AdminAction> getAdminActions(String requesterUserId) {
        return adminService.getAdminActions(requesterUserId);
    }

    public void directSetBalance(String requesterUserId, String walletId, BigDecimal newBalance) {
        adminService.directSetBalance(requesterUserId, walletId, newBalance);
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
        CashInOutTransaction txn = TransactionFactory.createCashInOut(
                txnId, referenceNumber, walletId, amount, fee, opType, channel, desc);
        txn.execute();
        return txn;
    }

    private String resolveChannelFromDescription(String description, String prefix) {
        if (description == null || description.trim().isEmpty()) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.startsWith(prefix)) {
            String rest = trimmed.substring(prefix.length()).trim();
            int dashIdx = rest.indexOf(" - ");
            String candidate = dashIdx >= 0 ? rest.substring(0, dashIdx).trim() : rest;
            return candidate.isEmpty() ? null : candidate;
        }
        int dashIdx = trimmed.indexOf(" - ");
        if (dashIdx > 0) {
            String candidate = trimmed.substring(0, dashIdx).trim();
            return candidate.isEmpty() ? null : candidate;
        }
        return null;
    }

    private void validateReferenceNumber(String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            throw new IllegalArgumentException("Transaction reference number cannot be null or empty");
        }
    }

    // --- CAP-13: Basic Simulated Investment Workflow ---

    @Override
    public SimulatedProduct getSeededProduct() {
        return investmentRepository.findSeededProduct();
    }

    @Override
    public InvestmentHolding getHolding(String walletId) {
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        String cleanWalletId = walletId.trim();
        Wallet wallet = getWallet(cleanWalletId);
        SimulatedProduct product = getSeededProduct();
        InvestmentRepository.HoldingRecord rec =
                investmentRepository.findHolding(cleanWalletId, product.getProductCode());
        BigDecimal units = rec.units().setScale(InvestmentService.UNIT_SCALE, RoundingMode.HALF_UP);
        BigDecimal marketValue = units.multiply(product.getSimulatedUnitPrice())
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        return new InvestmentHolding(
                cleanWalletId,
                product.getProductName(),
                units,
                product.getSimulatedUnitPrice(),
                marketValue,
                rec.totalInvested().setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING),
                wallet.getCurrency()
        );
    }

    @Override
    public InvestmentPreview previewBuy(String walletId, BigDecimal unitsInput, BigDecimal amountInput) {
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        String cleanWalletId = walletId.trim();
        Wallet wallet = getWallet(cleanWalletId);
        validateUserStatusActive(wallet.getUserId());
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Wallet is not active: " + wallet.getStatus());
        }
        SimulatedProduct product = getSeededProduct();
        UnitsAndAmount resolved = resolveBuyUnitsAndAmount(
                unitsInput, amountInput, product.getSimulatedUnitPrice(), wallet.getCurrency());
        if (wallet.getBalance().compareTo(resolved.amount()) < 0) {
            throw new InsufficientFundsException(cleanWalletId, wallet.getBalance(), resolved.amount());
        }
        InvestmentHolding holding = getHolding(cleanWalletId);
        BigDecimal projectedUnits = holding.getUnits().add(resolved.units())
                .setScale(InvestmentService.UNIT_SCALE, RoundingMode.HALF_UP);
        return new InvestmentPreview(
                "BUY",
                cleanWalletId,
                product.getProductName(),
                resolved.units(),
                product.getSimulatedUnitPrice(),
                resolved.amount(),
                MoneyUtil.ZERO,
                resolved.amount(),
                holding.getUnits(),
                projectedUnits,
                wallet.getCurrency(),
                IdGenerator.nextReferenceNumber()
        );
    }

    @Override
    public InvestmentPreview previewRedeem(String walletId, BigDecimal unitsToRedeem) {
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        String cleanWalletId = walletId.trim();
        Wallet wallet = getWallet(cleanWalletId);
        validateUserStatusActive(wallet.getUserId());
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Wallet is not active: " + wallet.getStatus());
        }
        BigDecimal units = validatePositiveUnits(unitsToRedeem);
        SimulatedProduct product = getSeededProduct();
        BigDecimal proceeds = units.multiply(product.getSimulatedUnitPrice())
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        if (proceeds.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Redemption proceeds must be positive.");
        }
        InvestmentHolding holding = getHolding(cleanWalletId);
        if (holding.getUnits().compareTo(units) < 0) {
            throw new WalletException("Insufficient investment holdings to redeem "
                    + units.toPlainString() + " units (available: "
                    + holding.getUnits().toPlainString() + " units). Overselling is not allowed.");
        }
        BigDecimal projectedUnits = holding.getUnits().subtract(units)
                .setScale(InvestmentService.UNIT_SCALE, RoundingMode.HALF_UP);
        return new InvestmentPreview(
                "REDEEM",
                cleanWalletId,
                product.getProductName(),
                units,
                product.getSimulatedUnitPrice(),
                proceeds,
                MoneyUtil.ZERO,
                proceeds,
                holding.getUnits(),
                projectedUnits,
                wallet.getCurrency(),
                IdGenerator.nextReferenceNumber()
        );
    }

    @Override
    public InvestMoneyTransaction invest(String referenceNumber, String walletId, String product,
                                         BigDecimal amount, BigDecimal units) {
        validateReferenceNumber(referenceNumber);
        String cleanRef = referenceNumber.trim();
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        String cleanWalletId = walletId.trim();
        SimulatedProduct seeded = getSeededProduct();
        String effectiveProduct = validateSimulatedProduct(product, seeded);
        UnitsAndAmount resolved = resolveBuyUnitsAndAmount(
                units, amount, seeded.getSimulatedUnitPrice(), seeded.getCurrency());

        if (requirePinAuthorization) {
            SessionManager.getInstance().requirePinAuthorization(null);
        }

        ReentrantLock lock = getLock(cleanWalletId);
        CommittedInvestmentOp committed;
        lock.lock();
        try {
            committed = databaseManager.executeInTransaction(conn -> {
                if (transactionRepository.existsByReferenceNumber(cleanRef, conn)) {
                    throw new DuplicateTransactionException(cleanRef,
                            "Duplicate transaction request reference: " + cleanRef);
                }

                Wallet wallet = walletRepository.findById(cleanWalletId, conn)
                        .orElseThrow(() -> new EntityNotFoundException("Wallet", cleanWalletId));
                validateAccountNotSuspended(wallet.getUserId(), conn);
                authorizeOutgoingPayment(wallet.getUserId());

                if (wallet.getStatus() != WalletStatus.ACTIVE) {
                    throw new WalletException("Wallet is not active: " + wallet.getStatus());
                }
                BigDecimal balanceBefore = wallet.getBalance();
                if (balanceBefore.compareTo(resolved.amount()) < 0) {
                    throw new InsufficientFundsException(cleanWalletId, balanceBefore, resolved.amount());
                }

                String txnId = IdGenerator.nextTransactionId();
                String desc = "Buy " + resolved.units().toPlainString() + " units of " + effectiveProduct
                        + " @ " + MoneyUtil.format(seeded.getSimulatedUnitPrice(), wallet.getCurrency()) + " [SIMULATED]";
                InvestMoneyTransaction txn = new TransactionBuilder()
                        .transactionId(txnId)
                        .referenceNumber(cleanRef)
                        .from(cleanWalletId)
                        .amount(resolved.amount())
                        .fee(MoneyUtil.ZERO)
                        .currency(wallet.getCurrency())
                        .product(effectiveProduct)
                        .units(resolved.units())
                        .type(TransactionType.INVESTMENT_BUY)
                        .description(desc)
                        .buildInvestment();
                txn.invest();
                transactionRepository.save(txn, conn);

                BigDecimal balanceAfter = balanceBefore.subtract(resolved.amount())
                        .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                walletRepository.updateBalance(cleanWalletId, balanceAfter, conn);
                wallet.setBalance(balanceAfter);

                LedgerEntry debitEntry = new LedgerEntry(
                        IdGenerator.nextLedgerEntryId(),
                        txnId,
                        cleanWalletId,
                        LedgerEntryType.DEBIT,
                        resolved.amount(),
                        balanceBefore,
                        balanceAfter,
                        wallet.getCurrency(),
                        txn.getDescription()
                );
                ledgerRepository.save(debitEntry, conn);

                InvestmentRepository.HoldingRecord current =
                        investmentRepository.findHolding(cleanWalletId, seeded.getProductCode(), conn);
                BigDecimal newUnits = current.units().add(resolved.units())
                        .setScale(InvestmentService.UNIT_SCALE, RoundingMode.HALF_UP);
                BigDecimal newTotalInvested = current.totalInvested().add(resolved.amount())
                        .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                investmentRepository.upsertHolding(
                        cleanWalletId, seeded.getProductCode(), newUnits, newTotalInvested, conn);

                return new CommittedInvestmentOp(txn, wallet.getUserId());
            });
            dispatchInvestmentNotificationPostCommit(committed);
            return committed.txn();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public InvestMoneyTransaction buyInvestment(String referenceNumber, String walletId,
                                                BigDecimal units, BigDecimal amount) {
        return invest(referenceNumber, walletId, getSeededProduct().getProductName(), amount, units);
    }

    @Override
    public InvestMoneyTransaction buyInvestmentWithPin(String referenceNumber, String walletId,
                                                       BigDecimal units, BigDecimal amount, String rawPin) {
        authorizeWithPin(walletId, rawPin);
        return buyInvestment(referenceNumber, walletId, units, amount);
    }

    @Override
    public InvestMoneyTransaction redeemInvestment(String referenceNumber, String walletId, BigDecimal unitsToRedeem) {
        validateReferenceNumber(referenceNumber);
        String cleanRef = referenceNumber.trim();
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        String cleanWalletId = walletId.trim();
        BigDecimal units = validatePositiveUnits(unitsToRedeem);
        SimulatedProduct seeded = getSeededProduct();
        BigDecimal proceeds = units.multiply(seeded.getSimulatedUnitPrice())
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        if (proceeds.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Redemption proceeds must be positive.");
        }

        if (requirePinAuthorization) {
            SessionManager.getInstance().requirePinAuthorization(null);
        }

        ReentrantLock lock = getLock(cleanWalletId);
        CommittedInvestmentOp committed;
        lock.lock();
        try {
            committed = databaseManager.executeInTransaction(conn -> {
                if (transactionRepository.existsByReferenceNumber(cleanRef, conn)) {
                    throw new DuplicateTransactionException(cleanRef,
                            "Duplicate transaction request reference: " + cleanRef);
                }

                Wallet wallet = walletRepository.findById(cleanWalletId, conn)
                        .orElseThrow(() -> new EntityNotFoundException("Wallet", cleanWalletId));
                validateAccountNotSuspended(wallet.getUserId(), conn);
                authorizeOutgoingPayment(wallet.getUserId());

                if (wallet.getStatus() != WalletStatus.ACTIVE) {
                    throw new WalletException("Wallet is not active: " + wallet.getStatus());
                }

                InvestmentRepository.HoldingRecord current =
                        investmentRepository.findHolding(cleanWalletId, seeded.getProductCode(), conn);
                if (current.units().compareTo(units) < 0) {
                    throw new WalletException("Insufficient investment holdings to redeem "
                            + units.toPlainString() + " units (available: "
                            + current.units().toPlainString() + " units). Overselling is not allowed.");
                }

                String txnId = IdGenerator.nextTransactionId();
                String desc = "Redeem " + units.toPlainString() + " units of " + seeded.getProductName()
                        + " @ " + MoneyUtil.format(seeded.getSimulatedUnitPrice(), wallet.getCurrency()) + " [SIMULATED]";
                InvestMoneyTransaction txn = new TransactionBuilder()
                        .transactionId(txnId)
                        .referenceNumber(cleanRef)
                        .to(cleanWalletId)
                        .amount(proceeds)
                        .fee(MoneyUtil.ZERO)
                        .currency(wallet.getCurrency())
                        .product(seeded.getProductName())
                        .units(units)
                        .type(TransactionType.INVESTMENT_REDEEM)
                        .description(desc)
                        .buildInvestment();
                txn.withdraw();
                transactionRepository.save(txn, conn);

                BigDecimal balanceBefore = wallet.getBalance();
                BigDecimal balanceAfter = balanceBefore.add(proceeds)
                        .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                walletRepository.updateBalance(cleanWalletId, balanceAfter, conn);
                wallet.setBalance(balanceAfter);

                LedgerEntry creditEntry = new LedgerEntry(
                        IdGenerator.nextLedgerEntryId(),
                        txnId,
                        cleanWalletId,
                        LedgerEntryType.CREDIT,
                        proceeds,
                        balanceBefore,
                        balanceAfter,
                        wallet.getCurrency(),
                        txn.getDescription()
                );
                ledgerRepository.save(creditEntry, conn);

                BigDecimal newUnits = current.units().subtract(units)
                        .setScale(InvestmentService.UNIT_SCALE, RoundingMode.HALF_UP);
                BigDecimal newTotalInvested;
                if (newUnits.compareTo(BigDecimal.ZERO) <= 0) {
                    newUnits = BigDecimal.ZERO.setScale(InvestmentService.UNIT_SCALE, RoundingMode.HALF_UP);
                    newTotalInvested = MoneyUtil.ZERO;
                } else {
                    newTotalInvested = current.totalInvested()
                            .multiply(newUnits)
                            .divide(current.units(), MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                }
                investmentRepository.upsertHolding(
                        cleanWalletId, seeded.getProductCode(), newUnits, newTotalInvested, conn);

                return new CommittedInvestmentOp(txn, wallet.getUserId());
            });
            dispatchInvestmentNotificationPostCommit(committed);
            return committed.txn();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public InvestMoneyTransaction redeemInvestmentWithPin(String referenceNumber, String walletId,
                                                          BigDecimal unitsToRedeem, String rawPin) {
        authorizeWithPin(walletId, rawPin);
        return redeemInvestment(referenceNumber, walletId, unitsToRedeem);
    }

    @Override
    public Transaction withdrawInvestment(String referenceNumber, String walletId, String investmentTransactionId) {
        if (investmentTransactionId == null || investmentTransactionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Investment transaction ID must not be null or empty");
        }
        String key = investmentTransactionId.trim();
        Transaction found = transactionRepository.findById(key)
                .or(() -> transactionRepository.findByReferenceNumber(key))
                .orElseThrow(() -> new EntityNotFoundException("Investment transaction", key));
        if (!(found instanceof InvestMoneyTransaction imt) || found.getType() != TransactionType.INVESTMENT_BUY) {
            throw new WalletException("Referenced transaction is not an investment purchase: " + key);
        }
        if (walletId != null && !walletId.trim().equals(found.getSourceWalletId())) {
            throw new SecurityException("Investment transaction does not belong to wallet " + walletId);
        }
        return redeemInvestment(referenceNumber, walletId != null ? walletId : found.getSourceWalletId(), imt.getUnits());
    }

    @Override
    public BigDecimal calculateReturns(String investmentTransactionId) {
        if (investmentTransactionId == null || investmentTransactionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Investment transaction ID must not be null or empty");
        }
        String key = investmentTransactionId.trim();
        Transaction found = transactionRepository.findById(key)
                .or(() -> transactionRepository.findByReferenceNumber(key))
                .orElseThrow(() -> new EntityNotFoundException("Investment transaction", key));

        SessionManager session = SessionManager.getInstance();
        if (session.isLoggedIn()) {
            User activeUser = session.getCurrentUser();
            if (activeUser != null && !activeUser.isAdmin()) {
                Optional<Wallet> activeWalletOpt = walletRepository.findByUserId(activeUser.getUserId());
                String activeWalletId = activeWalletOpt.map(Wallet::getWalletId).orElse(null);
                if (activeWalletId == null || !isWalletParticipant(activeWalletId, found)) {
                    throw new SecurityException("Access denied: Transaction " + key
                            + " does not belong to active user's wallet.");
                }
            }
        }

        if (found instanceof InvestMoneyTransaction imt) {
            return imt.getReturns();
        }
        return MoneyUtil.ZERO;
    }

    @Override
    public List<InvestMoneyTransaction> getInvestmentsByWalletId(String walletId) {
        List<Transaction> txns = getTransactionsByWalletId(walletId);
        List<InvestMoneyTransaction> result = new ArrayList<>();
        for (Transaction txn : txns) {
            if (txn instanceof InvestMoneyTransaction imt
                    && (txn.getType() == TransactionType.INVESTMENT_BUY
                    || txn.getType() == TransactionType.INVESTMENT_REDEEM)) {
                result.add(imt);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private void validateUserStatusActive(String userId) {
        if (userId == null) {
            return;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new WalletException("Suspended accounts cannot initiate payments: User " + userId + " is suspended.");
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new WalletException("Account is locked: User " + userId + " is locked.");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new WalletException("Account is not active: User " + userId + " status is " + user.getStatus());
        }
    }

    private void dispatchInvestmentNotificationPostCommit(CommittedInvestmentOp committed) {
        if (committed == null || committed.txn() == null || notificationService == null) {
            return;
        }
        try {
            notificationService.notifyInvestmentCommitted(committed.txn(), committed.ownerUserId());
        } catch (RuntimeException ex) {
            System.err.println("[WARN] Post-commit investment notification dispatch encountered an error for ref "
                    + committed.txn().getReferenceNumber() + ": " + ex.getMessage());
        }
    }

    private String validateSimulatedProduct(String product, SimulatedProduct seeded) {
        if (product == null || product.trim().isEmpty()) {
            return seeded.getProductName();
        }
        String trimmed = product.trim();
        if (trimmed.equalsIgnoreCase(seeded.getProductName()) || trimmed.equalsIgnoreCase(seeded.getProductCode())) {
            return seeded.getProductName();
        }
        throw new WalletException("Unsupported investment product '" + trimmed
                + "'. Only the seeded simulated product '" + seeded.getProductName() + "' is supported.");
    }

    private BigDecimal validatePositiveUnits(BigDecimal rawUnits) {
        if (rawUnits == null || rawUnits.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Investment units must be greater than zero.");
        }
        BigDecimal scaled = rawUnits.setScale(InvestmentService.UNIT_SCALE, RoundingMode.HALF_UP);
        if (scaled.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Investment units must be at least 0.0001.");
        }
        return scaled;
    }

    private UnitsAndAmount resolveBuyUnitsAndAmount(BigDecimal unitsInput, BigDecimal amountInput,
                                                    BigDecimal unitPrice, String currency) {
        String effectiveCurrency = (currency != null && !currency.trim().isEmpty())
                ? currency.trim()
                : OtogConfig.getInstance().getDefaultCurrency();
        if (unitsInput == null && amountInput == null) {
            throw new InvalidAmountException("Specify either units or " + effectiveCurrency + " amount to invest.");
        }
        if (unitsInput != null) {
            BigDecimal validUnits = validatePositiveUnits(unitsInput);
            BigDecimal computedAmount = validUnits.multiply(unitPrice)
                    .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
            if (computedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidAmountException("Investment amount must be positive.");
            }
            if (amountInput != null) {
                BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amountInput);
                if (validAmount.compareTo(computedAmount) != 0) {
                    throw new InvalidAmountException("Entered amount (" + validAmount.toPlainString()
                            + ") does not match units * simulated price (" + computedAmount.toPlainString() + ").");
                }
            }
            return new UnitsAndAmount(validUnits, computedAmount);
        }
        BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amountInput);
        BigDecimal computedUnits = validAmount.divide(unitPrice, InvestmentService.UNIT_SCALE, RoundingMode.DOWN);
        if (computedUnits.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Investment amount is too small to purchase at least 0.0001 units.");
        }
        BigDecimal exactCost = computedUnits.multiply(unitPrice)
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        if (exactCost.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Computed investment cost must be positive.");
        }
        return new UnitsAndAmount(computedUnits, exactCost);
    }

    private record UnitsAndAmount(BigDecimal units, BigDecimal amount) {
    }

    private record CommittedInvestmentOp(InvestMoneyTransaction txn, String ownerUserId) {
    }
}
