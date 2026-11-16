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
    private final AdminActionRepository adminActionRepository;
    private final AdminService adminService;

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
        this.adminActionRepository = new AdminActionRepository(databaseManager);
        this.adminService = new AdminServiceImpl(databaseManager, this.userRepository,
                this.walletRepository, this.transactionRepository, this.adminActionRepository,
                new CredentialRepository(databaseManager));
    }

    public AdminService getAdminService() {
        return adminService;
    }

    public AdminActionRepository getAdminActionRepository() {
        return adminActionRepository;
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
                    validateAccountNotSuspended(sourceWallet.getUserId(), conn);
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
                validateAccountNotSuspended(wallet.getUserId(), conn);

                if (wallet.getStatus() != WalletStatus.ACTIVE) {
                    throw new WalletException("Wallet is not active: " + wallet.getStatus());
                }

                PaymentMethod pm = null;
                if (paymentMethodId != null) {
                    pm = paymentMethodRepository.findById(paymentMethodId, conn)
                            .orElseThrow(() -> new EntityNotFoundException("PaymentMethod", paymentMethodId));
                }

                String txnId = IdGenerator.nextTransactionId();
                String desc = description != null ? description :
                        (pm != null ? "Cash In via " + pm.getProviderName() : "Cash In deposit");

                CashInOutTransaction txn = buildCashInOutTransaction(
                        txnId, referenceNumber, targetWalletId, validAmount, MoneyUtil.ZERO, "CASH_IN", pm, desc);
                transactionRepository.save(txn, conn);

                BigDecimal balanceBefore = wallet.getBalance();
                BigDecimal balanceAfter = balanceBefore.add(validAmount)
                        .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                walletRepository.updateBalance(targetWalletId, balanceAfter, conn);

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

                return txn;
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * Executes an atomic Cash-Out operation, debiting a wallet to a destination payment method.
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
                if (paymentMethodId != null) {
                    pm = paymentMethodRepository.findById(paymentMethodId, conn)
                            .orElseThrow(() -> new EntityNotFoundException("PaymentMethod", paymentMethodId));
                }

                String txnId = IdGenerator.nextTransactionId();
                String desc = description != null ? description :
                        (pm != null ? "Cash Out to " + pm.getProviderName() : "Cash Out withdrawal");

                CashInOutTransaction txn = buildCashInOutTransaction(
                        txnId, referenceNumber, sourceWalletId, validAmount, validFee, "CASH_OUT", pm, desc);
                transactionRepository.save(txn, conn);

                BigDecimal balanceAfter = balance.subtract(totalDebit)
                        .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                walletRepository.updateBalance(sourceWalletId, balanceAfter, conn);

                LedgerEntry debitEntry = new LedgerEntry(
                        IdGenerator.nextLedgerEntryId(),
                        txnId,
                        sourceWalletId,
                        LedgerEntryType.DEBIT,
                        totalDebit,
                        balance,
                        balanceAfter,
                        wallet.getCurrency(),
                        desc
                );
                ledgerRepository.save(debitEntry, conn);

                return txn;
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

    private void validateAccountNotSuspended(String userId, Connection conn) throws java.sql.SQLException {
        if (userId == null) {
            return;
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
        List<Transaction> txns = transactionRepository.findByWalletId(wallet.getWalletId());
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
