package com.otog.service;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.Credential;
import com.otog.model.PaymentMethod;
import com.otog.model.PaymentMethodType;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.UserStatus;
import com.otog.model.Wallet;
import com.otog.repository.CredentialRepository;
import com.otog.repository.UserRepository;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.PasswordUtil;

import java.time.LocalDateTime;
import java.util.Optional;

public class AuthServiceImpl implements AuthService {
    public static final int MAX_FAILED_ATTEMPTS = 5;

    private final DatabaseManager databaseManager;
    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final WalletService walletService;
    private final OtpService otpService;

    public AuthServiceImpl(DatabaseManager databaseManager) {
        this(databaseManager, new OtpService());
    }

    public AuthServiceImpl(DatabaseManager databaseManager, OtpService otpService) {
        this.databaseManager = databaseManager;
        this.userRepository = new UserRepository(databaseManager);
        this.credentialRepository = new CredentialRepository(databaseManager);
        this.walletService = new WalletService(databaseManager, this);
        this.otpService = otpService != null ? otpService : new OtpService();
    }

    public AuthServiceImpl(DatabaseManager databaseManager,
                           UserRepository userRepository,
                           CredentialRepository credentialRepository,
                           WalletService walletService) {
        this(databaseManager, userRepository, credentialRepository, walletService, new OtpService());
    }

    public AuthServiceImpl(DatabaseManager databaseManager,
                           UserRepository userRepository,
                           CredentialRepository credentialRepository,
                           WalletService walletService,
                           OtpService otpService) {
        this.databaseManager = databaseManager;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.walletService = walletService;
        this.otpService = otpService != null ? otpService : new OtpService();
    }

    public static String normalizeMobile(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("63") && digits.length() == 12) {
            digits = "0" + digits.substring(2);
        } else if (digits.length() == 10 && digits.startsWith("9")) {
            digits = "0" + digits;
        }
        return digits;
    }

    @Override
    public User registerUser(String name, String mobileNumber, String email, String rawPassword) {
        return registerUser(name, mobileNumber, email, rawPassword, UserRole.USER);
    }

    @Override
    public User registerUser(String name, String mobileNumber, String email, String rawPassword, UserRole role) {
        if (role == UserRole.ADMIN) {
            throw new OtogException("Self-registration as ADMIN is prohibited. Admin accounts must be provisioned by an administrator.");
        }
        UserRole effectiveRole = (role != null) ? role : UserRole.USER;

        if (name == null || name.trim().isEmpty()) {
            throw new OtogException("Name is required for registration.");
        }
        if (mobileNumber == null || mobileNumber.trim().isEmpty()) {
            throw new OtogException("Mobile number is required for registration.");
        }
        String cleanMobile = normalizeMobile(mobileNumber.trim());
        if (cleanMobile == null || !cleanMobile.matches("^09[0-9]{9}$")) {
            throw new OtogException("Invalid mobile number format: " + mobileNumber);
        }
        if (rawPassword == null || rawPassword.trim().length() < 6) {
            throw new OtogException("Password must be at least 6 characters long.");
        }

        // Check for duplicate account identifiers using normalized mobile
        if (userRepository.findByMobileNumber(cleanMobile).isPresent()) {
            throw new OtogException("Mobile number is already registered: " + cleanMobile);
        }
        String cleanEmail = (email != null && !email.trim().isEmpty()) ? email.trim() : null;
        if (cleanEmail != null && userRepository.findByEmail(cleanEmail).isPresent()) {
            throw new OtogException("Email is already registered: " + cleanEmail);
        }

        String userId = IdGenerator.nextUserId();
        User user = new User(userId, name.trim(), cleanMobile, cleanEmail, effectiveRole, UserStatus.ACTIVE);

        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hashPassword(rawPassword, salt);
        Credential credential = new Credential(userId, hash, salt, 0, null, LocalDateTime.now());

        String walletId = IdGenerator.nextWalletId();
        Wallet wallet = new Wallet(walletId, userId, MoneyUtil.ZERO, "PHP");

        // Atomic transaction: persist user, credentials, wallet, and seeded demo bank accounts in a single rollback unit
        return databaseManager.executeInTransaction(conn -> {
            userRepository.save(user, conn);
            credentialRepository.save(credential, conn);
            walletService.getWalletRepository().save(wallet, conn);
            seedDemoPaymentMethodsForUser(userId, name.trim(), conn);
            return user;
        });
    }

    private void seedDemoPaymentMethodsForUser(String userId, String userName, java.sql.Connection conn) throws java.sql.SQLException {
        PaymentMethod bdo = new PaymentMethod(IdGenerator.nextPaymentMethodId(), userId,
                PaymentMethodType.BANK_ACCOUNT, "BDO Unibank", userName + " BDO Demo", "•••• 1234");
        bdo.setDefault(true);
        walletService.getPaymentMethodRepository().save(bdo, conn);

        PaymentMethod bpi = new PaymentMethod(IdGenerator.nextPaymentMethodId(), userId,
                PaymentMethodType.BANK_ACCOUNT, "Bank of the Philippine Islands (BPI)", userName + " BPI Demo", "•••• 5678");
        walletService.getPaymentMethodRepository().save(bpi, conn);

        PaymentMethod metro = new PaymentMethod(IdGenerator.nextPaymentMethodId(), userId,
                PaymentMethodType.BANK_ACCOUNT, "Metrobank", userName + " Metrobank Demo", "•••• 9012");
        walletService.getPaymentMethodRepository().save(metro, conn);
    }

    @Override
    public Optional<User> authenticatePassword(String usernameOrMobile, String rawPassword) {
        if (usernameOrMobile == null || usernameOrMobile.trim().isEmpty() ||
                rawPassword == null || rawPassword.isEmpty()) {
            return Optional.empty();
        }

        Optional<User> userOpt = userRepository.findByIdentifier(usernameOrMobile.trim());
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }

        User user = userOpt.get();

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new OtogException("Account is suspended. Please contact support.");
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new OtogException("Account is locked due to too many failed login attempts. Please contact support.");
        }

        Optional<Credential> credOpt = credentialRepository.findByUserId(user.getUserId());
        if (credOpt.isEmpty()) {
            return Optional.empty();
        }

        Credential cred = credOpt.get();

        boolean matches = PasswordUtil.verifyPassword(rawPassword, cred.getSalt(), cred.getPasswordHash());
        if (matches) {
            credentialRepository.resetFailedAttempts(user.getUserId());
            return Optional.of(user);
        } else {
            int failed = credentialRepository.recordFailedAttempt(user.getUserId());
            if (failed >= MAX_FAILED_ATTEMPTS) {
                user.setStatus(UserStatus.LOCKED);
                userRepository.update(user);
                throw new OtogException("Account locked: maximum failed login attempts (" + MAX_FAILED_ATTEMPTS + ") reached.");
            }
            return Optional.empty();
        }
    }

    @Override
    public String issueTwoFactorOtp(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("User ID must not be empty.");
        }
        return otpService.generateOtp(userId.trim());
    }

    @Override
    public boolean verifyTwoFactorOtp(String userId, String otpCode) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        return otpService.verifyOtp(userId.trim(), otpCode);
    }

    @Override
    public boolean hasPin(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        return credentialRepository.findByUserId(userId.trim())
                .map(Credential::hasPin)
                .orElse(false);
    }

    @Override
    public void setPin(String userId, String currentPassword, String newPin) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new OtogException("User ID is required to set a transaction PIN.");
        }
        String cleanUserId = userId.trim();
        SessionManager session = SessionManager.getInstance();
        session.requireActiveSessionFor(cleanUserId);

        if (currentPassword == null || currentPassword.isEmpty()) {
            throw new OtogException("Current password is required to set a transaction PIN.");
        }
        String validPin = validatePinFormat(newPin);

        Credential cred = credentialRepository.findByUserId(cleanUserId)
                .orElseThrow(() -> new OtogException("Credentials not found for user: " + cleanUserId));
        if (!PasswordUtil.verifyPassword(currentPassword, cred.getSalt(), cred.getPasswordHash())) {
            throw new OtogException("Reauthentication failed: current password is incorrect.");
        }

        String pinSalt = PasswordUtil.generateSalt();
        String pinHash = PasswordUtil.hashPassword(validPin, pinSalt);
        credentialRepository.updatePin(cleanUserId, pinHash, pinSalt);
        session.resetPinAttempts();
        session.touchActivity();
    }

    @Override
    public void changePin(String userId, String currentPin, String newPin) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new OtogException("User ID is required to change a transaction PIN.");
        }
        String cleanUserId = userId.trim();
        SessionManager session = SessionManager.getInstance();
        session.requireActiveSessionFor(cleanUserId);

        if (!hasPin(cleanUserId)) {
            throw new OtogException("No transaction PIN is set. Please set a PIN first.");
        }
        String validNewPin = validatePinFormat(newPin);

        if (!verifyPin(cleanUserId, currentPin)) {
            throw new OtogException("Reauthentication failed: current transaction PIN is incorrect.");
        }
        if (validNewPin.equals(currentPin)) {
            throw new OtogException("New transaction PIN must be different from the current PIN.");
        }

        String pinSalt = PasswordUtil.generateSalt();
        String pinHash = PasswordUtil.hashPassword(validNewPin, pinSalt);
        credentialRepository.updatePin(cleanUserId, pinHash, pinSalt);
        session.resetPinAttempts();
        session.touchActivity();
    }

    @Override
    public boolean verifyPin(String userId, String rawPin) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        String cleanUserId = userId.trim();
        SessionManager session = SessionManager.getInstance();
        session.requireActiveSessionFor(cleanUserId);

        Credential cred = credentialRepository.findByUserId(cleanUserId)
                .orElseThrow(() -> new OtogException("Credentials not found for user: " + cleanUserId));
        if (!cred.hasPin()) {
            throw new SecurityException("Transaction PIN is not set. Please set a PIN before authorizing payments.");
        }
        if (cred.getPinFailedAttempts() >= SessionManager.MAX_PIN_ATTEMPTS || session.isLockedOutByPin()) {
            throw new SecurityException("Transaction PIN is locked due to too many failed attempts.");
        }

        boolean matches = rawPin != null
                && !rawPin.isEmpty()
                && PasswordUtil.verifyPassword(rawPin, cred.getPinSalt(), cred.getPinHash());
        if (matches) {
            credentialRepository.resetPinFailedAttempts(cleanUserId);
            session.markPinVerified();
            return true;
        }

        int dbFailedAttempts = credentialRepository.recordPinFailedAttempt(cleanUserId);
        boolean sessionLocked = session.recordFailedPinAttempt();
        if (dbFailedAttempts >= SessionManager.MAX_PIN_ATTEMPTS && !sessionLocked) {
            session.lockOutByPin();
        }
        return false;
    }

    private String validatePinFormat(String rawPin) {
        if (rawPin == null || !rawPin.matches("^[0-9]{4,6}$")) {
            throw new OtogException("Transaction PIN must be 4 to 6 numeric digits.");
        }
        return rawPin;
    }

    @Override
    public void logout(String userId) {
        SessionManager.getInstance().logout();
        if (userId != null && !userId.trim().isEmpty()) {
            otpService.invalidateOtp(userId.trim());
        }
    }

    public OtpService getOtpService() {
        return otpService;
    }

    public UserRepository getUserRepository() {
        return userRepository;
    }

    public CredentialRepository getCredentialRepository() {
        return credentialRepository;
    }

    public WalletService getWalletService() {
        return walletService;
    }

    public AdminService getAdminService() {
        return walletService != null ? walletService.getAdminService() : null;
    }
}
