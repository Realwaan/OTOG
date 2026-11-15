package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.WalletException;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.repository.UserRepository;
import com.otog.service.*;
import com.otog.util.IdGenerator;

import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for OTP verification, PIN management, SessionManager,
 * and WalletService PIN authorization gate.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OtpPinSessionTest {

    private static DatabaseManager dbManager;
    private static UserRepository userRepo;
    private static OtpService otpService;
    private static AuthServiceImpl authService;
    private static WalletService walletService;

    private static final String USER_A_ID = "USR-TESTA00001";
    private static final String USER_B_ID = "USR-TESTB00002";

    @BeforeAll
    static void setup() {
        dbManager = new DatabaseManager("jdbc:sqlite::memory:");
        userRepo = new UserRepository(dbManager);
        otpService = new OtpService();
        authService = new AuthServiceImpl(userRepo, otpService);
        walletService = new WalletService(dbManager);

        // Seed two test users
        User userA = new User(USER_A_ID, "Alice", "09170000001");
        User userB = new User(USER_B_ID, "Bob", "09170000002");
        userRepo.save(userA);
        userRepo.save(userB);
    }

    @BeforeEach
    void resetState() {
        otpService.clear();
        SessionManager.resetInstance();
    }

    @AfterAll
    static void tearDown() {
        dbManager.close();
    }

    // ======================== OTP TESTS ========================

    @Test
    @Order(1)
    @DisplayName("OTP: correct code verifies and is consumed (one-time use)")
    void otpCorrectCodeVerifiesAndConsumed() {
        String code = otpService.generateOtp(USER_A_ID);
        assertNotNull(code);
        assertEquals(6, code.length());

        // First verification succeeds
        assertTrue(otpService.verifyOtp(USER_A_ID, code));

        // Second verification fails (already consumed)
        assertFalse(otpService.verifyOtp(USER_A_ID, code));
    }

    @Test
    @Order(2)
    @DisplayName("OTP: random / wrong code is rejected")
    void otpRandomCodeRejected() {
        otpService.generateOtp(USER_A_ID);

        assertFalse(otpService.verifyOtp(USER_A_ID, "000000"));
        assertFalse(otpService.verifyOtp(USER_A_ID, "ABCDEF"));
        assertFalse(otpService.verifyOtp(USER_A_ID, ""));
    }

    @Test
    @Order(3)
    @DisplayName("OTP: expired code (> 5 min) is rejected")
    void otpExpiredCodeRejected() throws Exception {
        String code = otpService.generateOtp(USER_A_ID);

        // Manipulate the record's createdAt to simulate expiry
        OtpService.OtpRecord record = otpService.getRecord(USER_A_ID);
        Instant expired = Instant.now().minusSeconds(OtpService.OTP_EXPIRY_MINUTES * 60L + 1);
        OtpService.OtpRecord expiredRecord = new OtpService.OtpRecord(
                record.code, record.userId, expired, record.used, record.failedAttempts);

        // Use reflection to put the expired record back into the store
        Field storeField = OtpService.class.getDeclaredField("store");
        storeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, OtpService.OtpRecord> store =
                (java.util.concurrent.ConcurrentHashMap<String, OtpService.OtpRecord>) storeField.get(otpService);
        store.put(USER_A_ID, expiredRecord);

        assertFalse(otpService.verifyOtp(USER_A_ID, code));
    }

    @Test
    @Order(4)
    @DisplayName("OTP: already-used code is rejected")
    void otpReusedCodeRejected() {
        String code = otpService.generateOtp(USER_A_ID);
        assertTrue(otpService.verifyOtp(USER_A_ID, code));  // consume
        assertFalse(otpService.verifyOtp(USER_A_ID, code)); // reuse
    }

    @Test
    @Order(5)
    @DisplayName("OTP: another account's code is rejected (account binding)")
    void otpCrossAccountRejected() {
        String codeA = otpService.generateOtp(USER_A_ID);

        // User B cannot verify User A's code
        assertFalse(otpService.verifyOtp(USER_B_ID, codeA));

        // User A can still verify their own code
        assertTrue(otpService.verifyOtp(USER_A_ID, codeA));
    }

    @Test
    @Order(6)
    @DisplayName("OTP: rejected after max attempts exhausted")
    void otpMaxAttemptsExhausted() {
        String code = otpService.generateOtp(USER_A_ID);

        for (int i = 0; i < OtpService.MAX_ATTEMPTS; i++) {
            assertFalse(otpService.verifyOtp(USER_A_ID, "WRONG" + i));
        }

        // Even the correct code is rejected now (record invalidated)
        assertFalse(otpService.verifyOtp(USER_A_ID, code));
    }

    @Test
    @Order(7)
    @DisplayName("OTP: resend cooldown prevents immediate reissue")
    void otpResendCooldown() {
        otpService.generateOtp(USER_A_ID);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> otpService.generateOtp(USER_A_ID));
        assertTrue(ex.getMessage().contains("cooldown"));
    }

    // ======================== PIN TESTS ========================

    @Test
    @Order(8)
    @DisplayName("PIN: set PIN when none exists")
    void pinSetFirstTime() {
        SessionManager.getInstance().createSession(USER_A_ID);

        assertDoesNotThrow(() -> authService.setPin(USER_A_ID, "1234"));

        User user = userRepo.findById(USER_A_ID).orElseThrow();
        assertNotNull(user.getPinHash());
        assertEquals(AuthServiceImpl.hashPin("1234"), user.getPinHash());
    }

    @Test
    @Order(9)
    @DisplayName("PIN: change PIN with correct old PIN")
    void pinChangeWithCorrectOld() {
        SessionManager.getInstance().createSession(USER_A_ID);
        // Ensure PIN is set first
        User user = userRepo.findById(USER_A_ID).orElseThrow();
        user.setPinHash(AuthServiceImpl.hashPin("1234"));
        userRepo.update(user);

        assertDoesNotThrow(() -> authService.changePin(USER_A_ID, "1234", "5678"));

        User updated = userRepo.findById(USER_A_ID).orElseThrow();
        assertEquals(AuthServiceImpl.hashPin("5678"), updated.getPinHash());
    }

    @Test
    @Order(10)
    @DisplayName("PIN: change PIN with wrong old PIN is rejected")
    void pinChangeWithWrongOldRejected() {
        SessionManager.getInstance().createSession(USER_A_ID);
        User user = userRepo.findById(USER_A_ID).orElseThrow();
        user.setPinHash(AuthServiceImpl.hashPin("5678"));
        userRepo.update(user);

        assertThrows(Exception.class,
                () -> authService.changePin(USER_A_ID, "0000", "9999"));
    }

    @Test
    @Order(11)
    @DisplayName("PIN: attempt limit locks session")
    void pinAttemptLimitLocksSession() {
        SessionManager.getInstance().createSession(USER_A_ID);
        User user = userRepo.findById(USER_A_ID).orElseThrow();
        user.setPinHash(AuthServiceImpl.hashPin("1234"));
        userRepo.update(user);

        // Exhaust PIN attempts
        for (int i = 0; i < SessionManager.MAX_PIN_ATTEMPTS; i++) {
            assertFalse(authService.verifyPin(USER_A_ID, "WRONG"));
        }

        // Session is now destroyed
        assertFalse(SessionManager.getInstance().isSessionActive(USER_A_ID));
        // Even correct PIN fails without a session
        assertFalse(authService.verifyPin(USER_A_ID, "1234"));
    }

    // ======================== SESSION TESTS ========================

    @Test
    @Order(12)
    @DisplayName("Session: created on login, active check returns true")
    void sessionCreatedAndActive() {
        SessionManager sm = SessionManager.getInstance();
        sm.createSession(USER_A_ID);

        assertTrue(sm.isSessionActive(USER_A_ID));
    }

    @Test
    @Order(13)
    @DisplayName("Session: expires after inactivity timeout")
    void sessionExpiresAfterTimeout() throws Exception {
        SessionManager sm = SessionManager.getInstance();
        sm.createSession(USER_A_ID);

        // Manipulate lastActivity to simulate timeout
        Field sessionsField = SessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Object> sessions =
                (java.util.concurrent.ConcurrentHashMap<String, Object>) sessionsField.get(sm);

        Object info = sessions.get(USER_A_ID);
        // Build an expired SessionInfo via reflection
        Class<?> siClass = Class.forName("com.otog.service.SessionManager$SessionInfo");
        var ctor = siClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Instant expired = Instant.now().minusSeconds(SessionManager.INACTIVITY_TIMEOUT_MINUTES * 60L + 1);
        Object expiredInfo = ctor.newInstance(USER_A_ID, Instant.now(), expired, false, 0);
        sessions.put(USER_A_ID, expiredInfo);

        assertFalse(sm.isSessionActive(USER_A_ID));
    }

    @Test
    @Order(14)
    @DisplayName("Session: touchSession resets the inactivity clock")
    void sessionTouchResetsInactivity() {
        SessionManager sm = SessionManager.getInstance();
        sm.createSession(USER_A_ID);
        sm.touchSession(USER_A_ID);

        assertTrue(sm.isSessionActive(USER_A_ID));
    }

    @Test
    @Order(15)
    @DisplayName("Session: Singleton returns the same instance")
    void sessionSingletonConsistency() {
        SessionManager a = SessionManager.getInstance();
        SessionManager b = SessionManager.getInstance();
        assertSame(a, b);
    }

    // ======================== PAYMENT GATE TESTS ========================

    @Test
    @Order(16)
    @DisplayName("Payment Gate: transfer blocked when PIN not verified")
    void transferBlockedWithoutPin() {
        SessionManager sm = SessionManager.getInstance();
        sm.createSession(USER_A_ID);
        // PIN not verified

        assertThrows(WalletException.class, () ->
                walletService.transfer(USER_A_ID, IdGenerator.nextReferenceNumber(),
                        "WLT-SRC", "WLT-TGT", BigDecimal.TEN, BigDecimal.ZERO, "test"));
    }

    @Test
    @Order(17)
    @DisplayName("Payment Gate: transfer succeeds after PIN verification")
    void transferSucceedsAfterPin() {
        // Create wallets for the transfer
        walletService.createWallet(USER_A_ID, new BigDecimal("1000.00"), "PHP");
        walletService.createWallet(USER_B_ID, new BigDecimal("500.00"), "PHP");

        Wallet walletA = walletService.getWalletByUserId(USER_A_ID);
        Wallet walletB = walletService.getWalletByUserId(USER_B_ID);

        // Set up authenticated session with PIN verified
        SessionManager sm = SessionManager.getInstance();
        sm.createSession(USER_A_ID);
        sm.markPinVerified(USER_A_ID);

        // Transfer should succeed
        assertDoesNotThrow(() ->
                walletService.transfer(USER_A_ID, IdGenerator.nextReferenceNumber(),
                        walletA.getWalletId(), walletB.getWalletId(),
                        new BigDecimal("100.00"), BigDecimal.ZERO, "test transfer"));
    }

    @Test
    @Order(18)
    @DisplayName("Payment Gate: cashOut blocked without PIN")
    void cashOutBlockedWithoutPin() {
        SessionManager sm = SessionManager.getInstance();
        sm.createSession(USER_A_ID);
        // PIN not verified

        assertThrows(WalletException.class, () ->
                walletService.cashOut(USER_A_ID, IdGenerator.nextReferenceNumber(),
                        "WLT-SRC", null, BigDecimal.TEN, BigDecimal.ZERO, "test"));
    }

    @Test
    @Order(19)
    @DisplayName("Payment Gate: cashIn works without PIN (no gate)")
    void cashInWorksWithoutPin() {
        // cashIn has no PIN gate — use existing wallet from test 17
        Wallet walletA = walletService.getWalletByUserId(USER_A_ID);

        // No session or PIN needed for cash-in
        assertDoesNotThrow(() ->
                walletService.cashIn(IdGenerator.nextReferenceNumber(),
                        walletA.getWalletId(), null,
                        new BigDecimal("50.00"), "test cash in"));
    }
}
