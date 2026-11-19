package com.otog.service;

import com.otog.exception.EntityNotFoundException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.InvalidAmountException;
import com.otog.exception.WalletException;
import com.otog.model.CompositeFeePolicy;
import com.otog.model.FeeComponent;
import com.otog.model.ReceiptBuilder;
import com.otog.model.Transaction;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.model.WalletStatus;
import com.otog.repository.UserRepository;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Service orchestrating peer-to-peer Send Money workflows.
 * Enforces recipient resolution, configured limits, balance checks, pre-confirmation preview,
 * PIN authorization, idempotent atomic execution, and transaction persistence.
 */
public class SendMoneyService {

    public static final BigDecimal MIN_SEND_AMOUNT = OtogConfig.getInstance().getSendMinAmount();
    public static final BigDecimal MAX_SEND_AMOUNT = OtogConfig.getInstance().getSendMaxAmount();

    private final WalletOperations walletService;
    private final UserRepository userRepository;
    private final AuthService authService;

    public SendMoneyService(WalletOperations walletService, UserRepository userRepository, AuthService authService) {
        this.walletService = walletService;
        this.userRepository = userRepository;
        this.authService = authService;
    }

    public SendMoneyService(WalletService walletService, UserRepository userRepository, AuthService authService) {
        this((WalletOperations) walletService, userRepository, authService);
    }

    public SendMoneyService(WalletService walletService, AuthService authService) {
        this((WalletOperations) walletService, walletService.getUserRepository(), authService);
    }

    /**
     * Resolves a recipient user from a mobile number, user ID, email, name, or wallet ID.
     *
     * @param recipientIdentifier recipient identifier
     * @return the resolved {@link User}
     * @throws IllegalArgumentException if recipientIdentifier is null or blank
     * @throws EntityNotFoundException  if no user can be resolved
     */
    public User resolveRecipient(String recipientIdentifier) {
        if (recipientIdentifier == null || recipientIdentifier.trim().isEmpty()) {
            throw new IllegalArgumentException("Recipient identifier must not be null or empty");
        }
        String cleanId = recipientIdentifier.trim();
        Optional<User> userOpt = userRepository.findByIdentifier(cleanId);
        if (userOpt.isPresent()) {
            return userOpt.get();
        }

        // Try looking up via wallet ID if applicable
        try {
            Wallet wallet = walletService.getWallet(cleanId);
            if (wallet != null && wallet.getUserId() != null) {
                return userRepository.findById(wallet.getUserId())
                        .orElseThrow(() -> new EntityNotFoundException("Recipient user", cleanId));
            }
        } catch (EntityNotFoundException notFound) {
            throw new EntityNotFoundException("Recipient user", cleanId);
        }

        throw new EntityNotFoundException("Recipient user", cleanId);
    }

    /**
     * Validates transfer parameters and generates an immutable preview with a unique reference number.
     * Does not execute financial mutation or require PIN.
     *
     * @param senderUserId        sender user ID
     * @param recipientIdentifier recipient mobile number, username, email, or wallet ID
     * @param amount              amount to send
     * @param fee                 optional fee (defaults to 0.00)
     * @param note                optional description / note
     * @return SendMoneyPreview containing full transfer details and reference number
     */
    public SendMoneyPreview preview(String senderUserId, String recipientIdentifier,
                                    BigDecimal amount, BigDecimal fee, String note) {
        if (senderUserId == null || senderUserId.trim().isEmpty()) {
            throw new IllegalArgumentException("Sender user ID must not be null or empty");
        }
        String cleanSenderUserId = senderUserId.trim();

        // 1. Resolve recipient
        User recipient = resolveRecipient(recipientIdentifier);

        // 2. Reject self-transfer
        if (recipient.getUserId().equals(cleanSenderUserId)) {
            throw new WalletException("Self-transfer is not permitted.");
        }

        // 3. Sender wallet resolution & status check
        Wallet senderWallet = walletService.getWalletByUserId(cleanSenderUserId);
        if (senderWallet == null) {
            throw new EntityNotFoundException("Sender wallet for user", cleanSenderUserId);
        }
        if (senderWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Sender wallet is not active: " + senderWallet.getStatus());
        }

        // 4. Recipient wallet resolution & status check
        Wallet recipientWallet = walletService.getWalletByUserId(recipient.getUserId());
        if (recipientWallet == null) {
            throw new EntityNotFoundException("Recipient wallet for user", recipient.getUserId());
        }
        if (recipientWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Recipient wallet is not active: " + recipientWallet.getStatus());
        }

        // 5. Amount validation and configured limits check
        if (amount == null) {
            throw new InvalidAmountException("Monetary amount cannot be null");
        }
        BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amount);
        OtogConfig config = OtogConfig.getInstance();
        BigDecimal minSend = config.getSendMinAmount();
        BigDecimal maxSend = config.getSendMaxAmount();

        if (validAmount.compareTo(minSend) < 0) {
            throw new InvalidAmountException(String.format(
                    "Amount %s is below minimum send amount of %s", validAmount, minSend));
        }
        if (validAmount.compareTo(maxSend) > 0) {
            throw new InvalidAmountException(String.format(
                    "Amount %s exceeds maximum send amount of %s", validAmount, maxSend));
        }

        // 6. Fee calculation & validation (CompositeFeePolicy when fee is omitted)
        BigDecimal validFee = resolveEffectiveFee(validAmount, fee);

        // 7. Balance check (amount + fee)
        BigDecimal totalDebit = validAmount.add(validFee).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        BigDecimal availableBalance = senderWallet.getBalance();
        if (availableBalance.compareTo(totalDebit) < 0) {
            throw new InsufficientFundsException(senderWallet.getWalletId(), availableBalance, totalDebit);
        }

        String referenceNumber = IdGenerator.nextReferenceNumber();
        String recipientName = recipient.getName() != null ? recipient.getName() : recipient.getMobileNumber();
        String recipientNumber = recipient.getMobileNumber() != null ? recipient.getMobileNumber() : recipientIdentifier;

        return new SendMoneyPreview(
                referenceNumber,
                senderWallet.getWalletId(),
                recipientWallet.getWalletId(),
                recipientName,
                recipientNumber,
                validAmount,
                validFee,
                totalDebit,
                note,
                cleanSenderUserId,
                recipient.getUserId()
        );
    }

    /**
     * Confirms and executes an atomic Send Money transfer using a previously generated reference number.
     * Enforces PIN authorization and idempotent execution (repeated confirmation with identical reference
     * produces exactly one transfer).
     */
    public Transaction confirmAndSend(String referenceNumber, String senderUserId, String pin,
                                      String recipientIdentifier, BigDecimal amount, BigDecimal fee, String note) {
        if (pin == null || pin.trim().isEmpty()) {
            throw new WalletException("Transaction PIN is required.");
        }
        if (senderUserId == null || senderUserId.trim().isEmpty()) {
            throw new IllegalArgumentException("Sender user ID must not be null or empty");
        }
        String cleanSenderUserId = senderUserId.trim();

        // Verify PIN via AuthService
        if (authService != null) {
            try {
                if (!authService.verifyPin(cleanSenderUserId, pin.trim())) {
                    throw new WalletException("Invalid transaction PIN.");
                }
            } catch (SecurityException ex) {
                throw new WalletException(ex.getMessage(), ex);
            }
        }

        // Resolve recipient & wallets
        User recipient = resolveRecipient(recipientIdentifier);
        if (recipient.getUserId().equals(cleanSenderUserId)) {
            throw new WalletException("Self-transfer is not permitted.");
        }

        Wallet senderWallet = walletService.getWalletByUserId(cleanSenderUserId);
        if (senderWallet == null) {
            throw new EntityNotFoundException("Sender wallet for user", cleanSenderUserId);
        }

        Wallet recipientWallet = walletService.getWalletByUserId(recipient.getUserId());
        if (recipientWallet == null) {
            throw new EntityNotFoundException("Recipient wallet for user", recipient.getUserId());
        }

        BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amount);
        OtogConfig config = OtogConfig.getInstance();
        BigDecimal minSend = config.getSendMinAmount();
        BigDecimal maxSend = config.getSendMaxAmount();
        if (validAmount.compareTo(minSend) < 0) {
            throw new InvalidAmountException("Amount is below minimum send amount");
        }
        if (validAmount.compareTo(maxSend) > 0) {
            throw new InvalidAmountException("Amount exceeds maximum send amount");
        }
        BigDecimal validFee = resolveEffectiveFee(validAmount, fee);

        String description = (note != null && !note.trim().isEmpty())
                ? note.trim()
                : "Transfer to " + (recipient.getName() != null ? recipient.getName() : recipient.getMobileNumber());

        return walletService.transfer(
                referenceNumber,
                senderWallet.getWalletId(),
                recipientWallet.getWalletId(),
                validAmount,
                validFee,
                description
        );
    }

    private static BigDecimal resolveEffectiveFee(BigDecimal principalAmount, BigDecimal suppliedFee) {
        if (suppliedFee != null) {
            return MoneyUtil.validateNonNegativeAmount(suppliedFee);
        }
        FeeComponent defaultPolicy = CompositeFeePolicy.fromConfig();
        return defaultPolicy.calculate(principalAmount);
    }

    /**
     * Executes the complete end-to-end sender-to-recipient flow: validates preview, verifies PIN,
     * and atomically transfers funds.
     */
    public Transaction sendMoney(String senderUserId, String recipientIdentifier,
                                 BigDecimal amount, BigDecimal fee, String note, String pin) {
        // Fast-path balance check for send operations: reject insufficient funds before processing preview
        if (senderUserId != null && !senderUserId.trim().isEmpty()
                && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            Wallet senderWallet = walletService.getWalletByUserId(senderUserId.trim());
            if (senderWallet != null) {
                BigDecimal validFee = (fee != null && fee.compareTo(BigDecimal.ZERO) >= 0)
                        ? fee
                        : CompositeFeePolicy.fromConfig().calculate(amount);
                BigDecimal totalDebit = amount.add(validFee);
                if (senderWallet.getBalance().compareTo(totalDebit) < 0) {
                    throw new InsufficientFundsException(senderWallet.getWalletId(), senderWallet.getBalance(), totalDebit);
                }
            }
        }

        // Preview validates recipient, amount, limits, balance before PIN confirmation
        SendMoneyPreview preview = preview(senderUserId, recipientIdentifier, amount, fee, note);
        return confirmAndSend(preview.getReferenceNumber(), senderUserId, pin,
                recipientIdentifier, amount, fee, note);
    }

    /**
     * Encapsulates the verified transfer details to display to the user before PIN entry.
     */
    public static class SendMoneyPreview {
        private final String referenceNumber;
        private final String senderWalletId;
        private final String recipientWalletId;
        private final String recipientName;
        private final String recipientNumber;
        private final BigDecimal amount;
        private final BigDecimal fee;
        private final BigDecimal totalDebit;
        private final String note;
        private final String senderUserId;
        private final String recipientUserId;

        public SendMoneyPreview(String referenceNumber, String senderWalletId, String recipientWalletId,
                                String recipientName, String recipientNumber, BigDecimal amount,
                                BigDecimal fee, BigDecimal totalDebit, String note,
                                String senderUserId, String recipientUserId) {
            this.referenceNumber = referenceNumber;
            this.senderWalletId = senderWalletId;
            this.recipientWalletId = recipientWalletId;
            this.recipientName = recipientName;
            this.recipientNumber = recipientNumber;
            this.amount = amount;
            this.fee = fee;
            this.totalDebit = totalDebit;
            this.note = note;
            this.senderUserId = senderUserId;
            this.recipientUserId = recipientUserId;
        }

        public String getReferenceNumber() {
            return referenceNumber;
        }

        public String getSenderWalletId() {
            return senderWalletId;
        }

        public String getRecipientWalletId() {
            return recipientWalletId;
        }

        public String getRecipientName() {
            return recipientName;
        }

        public String getRecipientNumber() {
            return recipientNumber;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public BigDecimal getFee() {
            return fee;
        }

        public BigDecimal getTotalDebit() {
            return totalDebit;
        }

        public String getNote() {
            return note;
        }

        public String getSenderUserId() {
            return senderUserId;
        }

        public String getRecipientUserId() {
            return recipientUserId;
        }

        public String toConfirmationText() {
            String currency = OtogConfig.getInstance().getDefaultCurrency();
            return new ReceiptBuilder()
                    .labelWidth(17)
                    .banner("SEND MONEY CONFIRMATION")
                    .line("Reference", referenceNumber)
                    .line("Recipient Name", recipientName)
                    .line("Recipient No", recipientNumber)
                    .moneyLine("Amount", amount, currency)
                    .moneyLine("Fee", fee, currency)
                    .divider()
                    .moneyLine("Total Debit", totalDebit, currency)
                    .optionalLine("Note", note)
                    .build();
        }
    }
}
