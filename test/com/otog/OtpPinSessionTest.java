package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.exception.WalletException;
import com.otog.model.Credential;
import com.otog.model.Transaction;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.repository.CredentialRepository;
import com.otog.service.AuthServiceImpl;
import com.otog.service.OtpService;
import com.otog.service.SessionManager;
import com.otog.service.WalletService;
import com.otog.util.PasswordUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JLabel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CAP-04 verification suite:
 * - Account-bound OTP lifecycle (expiry, one-time use, attempt limits, resend cooldown, simulated delivery label)
 * - Hashed transaction PIN setup/change with reauthentication and attempt lockouts
 * - Singleton SessionManager inactivity timeout and PIN state lifecycle
 * - Outgoing payment gates (transfer & cashOut blocked without PIN/session; cashIn permitted)
 */
public class OtpPinSessionTest {

    private static final class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone;

        private MutableClock(Instant initial, ZoneId zone) {
            this.current = initial;
            this.zone = zone;
        }

        void advance(Duration delta) {
            this.current = this.current.plus(delta);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private DatabaseManager databaseManager;
    private MutableClock clock;
    private OtpService otpService;
    private AuthServiceImpl authService;
    private WalletService gatedWalletService;
    private CredentialRepository credentialRepository;

    private User alice;
    private User bob;
    private Wallet aliceWallet;
    private Wallet bobWallet;

    @BeforeEach
    void setUp() {
        SessionManager.getInstance().reset();
        clock = new MutableClock(Instant.parse("2026-11-16T08:00:00Z"), ZoneOffset.UTC);
        SessionManager.getInstance().setClock(clock);

        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        otpService = new OtpService(clock);
        authService = new AuthServiceImpl(databaseManager, otpService);
        gatedWalletService = authService.getWalletService();
        credentialRepository = authService.getCredentialRepository();

        alice = authService.registerUser("Alice Santos", "09171000001", "alice@otog.ph", "AlicePass123");
        bob = authService.registerUser("Bob Reyes", "09171000002", "bob@otog.ph", "BobPass123");
        aliceWallet = gatedWalletService.getWalletByUserId(alice.getUserId());
        bobWallet = gatedWalletService.getWalletByUserId(bob.getUserId());

        gatedWalletService.cashIn("INIT-ALICE", aliceWallet.getWalletId(), null,
                new BigDecimal("1000.00"), "Initial deposit");
        aliceWallet = gatedWalletService.getWallet(aliceWallet.getWalletId());
    }

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().reset();
        databaseManager.close();
    }

    @Test
    void testOtpSimulatedLabelAccountBindingOneTimeUseAndExpiry() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        String code;
        try (PrintStream spyOut = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(spyOut);
            code = authService.issueTwoFactorOtp(alice.getUserId());
        } finally {
            System.setOut(originalOut);
        }

        String consoleOutput = captured.toString(StandardCharsets.UTF_8);
        assertTrue(consoleOutput.contains("SIMULATED SMS"),
                "OTP delivery output must explicitly label simulated delivery");
        assertNotNull(code);
        assertEquals(OtpService.OTP_LENGTH, code.length());
        assertTrue(code.matches("^[0-9]{6}$"));

        // Reject fallback/random/blank codes
        String wrongCode = "123456".equals(code) ? "654321" : "123456";
        assertFalse(authService.verifyTwoFactorOtp(alice.getUserId(), wrongCode));
        assertFalse(authService.verifyTwoFactorOtp(alice.getUserId(), ""));
        assertFalse(authService.verifyTwoFactorOtp(alice.getUserId(), "   "));

        // Reject Alice's OTP when submitted for Bob (cross-account isolation)
        assertFalse(authService.verifyTwoFactorOtp(bob.getUserId(), code),
                "Another account's OTP must be rejected");

        // Reject expired OTP
        clock.advance(Duration.ofMinutes(OtpService.OTP_EXPIRY_MINUTES).plusSeconds(1));
        assertFalse(authService.verifyTwoFactorOtp(alice.getUserId(), code),
                "Expired OTP must be rejected");

        // Fresh OTP works exactly once (one-time use)
        clock.advance(Duration.ofSeconds(OtpService.RESEND_COOLDOWN_SECONDS));
        String freshCode = authService.issueTwoFactorOtp(alice.getUserId());
        assertTrue(authService.verifyTwoFactorOtp(alice.getUserId(), freshCode),
                "Valid non-expired OTP must succeed once");
        assertFalse(authService.verifyTwoFactorOtp(alice.getUserId(), freshCode),
                "Reused OTP must be rejected");
    }

    @Test
    void testOtpAttemptLimitsAndResendCooldownAcrossStates() {
        String code = authService.issueTwoFactorOtp(alice.getUserId());
        String badCode = "000000".equals(code) ? "999999" : "000000";

        // Cooldown blocks immediate resend
        assertThrows(IllegalStateException.class, () -> authService.issueTwoFactorOtp(alice.getUserId()),
                "Immediate OTP resend before cooldown elapses must be blocked");

        // Exhaust MAX_ATTEMPTS wrong guesses
        for (int i = 0; i < OtpService.MAX_ATTEMPTS; i++) {
            assertFalse(authService.verifyTwoFactorOtp(alice.getUserId(), badCode));
        }
        assertEquals(0, otpService.getRemainingAttempts(alice.getUserId()));

        // Even the genuine code is now rejected after exceeding attempt limit
        assertFalse(authService.verifyTwoFactorOtp(alice.getUserId(), code),
                "OTP exhausted by failed attempts must reject the real code");

        // Resend cooldown must still apply even after OTP is consumed/exhausted
        clock.advance(Duration.ofSeconds(30));
        assertThrows(IllegalStateException.class, () -> authService.issueTwoFactorOtp(alice.getUserId()),
                "Cooldown must still block resend even after prior OTP was exhausted");

        // Resend succeeds once full cooldown has elapsed
        clock.advance(Duration.ofSeconds(31));
        String regenerated = authService.issueTwoFactorOtp(alice.getUserId());
        assertTrue(authService.verifyTwoFactorOtp(alice.getUserId(), regenerated));

        // Cooldown also blocks resend right after one-time consumption
        assertThrows(IllegalStateException.class, () -> authService.issueTwoFactorOtp(alice.getUserId()),
                "Cooldown must still apply after one-time verification consumes OTP");
    }

    @Test
    void testUserLoginDisplaysSimulatedOtpAndEnforcesResendCooldown() throws Exception {
        Class<?> loginClass = Class.forName("UserLogin");
        Object loginWindow = loginClass.getConstructor(
                com.otog.service.AuthService.class,
                com.otog.service.WalletService.class
        ).newInstance(authService, gatedWalletService);
        Method attemptLogin = loginClass.getMethod("attemptLogin", String.class, String.class);
        Method verifyTwoFactorCode = loginClass.getMethod("verifyTwoFactorCode", String.class);
        Method resendTwoFactorOtp = loginClass.getMethod("resendTwoFactorOtp");
        Method getTwoFactorHeaderLabel = loginClass.getMethod("getTwoFactorHeaderLabel");
        Method getResendOtpButton = loginClass.getMethod("getResendOtpButton");
        Method getLastIssuedOtp = loginClass.getMethod("getLastIssuedOtp");

        try {
            assertTrue((Boolean) attemptLogin.invoke(loginWindow, alice.getMobileNumber(), "AlicePass123"));
            JLabel headerLabel = (JLabel) getTwoFactorHeaderLabel.invoke(loginWindow);
            String headerText = headerLabel.getText();
            assertTrue(headerText.contains("Simulated SMS"),
                    "2FA header label must explicitly mark delivery as simulated");
            assertNotNull(getResendOtpButton.invoke(loginWindow));

            String firstOtp = (String) getLastIssuedOtp.invoke(loginWindow);
            assertNotNull(firstOtp);
            assertTrue(headerText.contains(firstOtp));

            // Cooldown blocks immediate UI resend
            InvocationTargetException cooldownEx = assertThrows(InvocationTargetException.class,
                    () -> resendTwoFactorOtp.invoke(loginWindow));
            assertInstanceOf(IllegalStateException.class, cooldownEx.getCause());

            // Advance past cooldown and resend
            clock.advance(Duration.ofSeconds(OtpService.RESEND_COOLDOWN_SECONDS + 1L));
            String secondOtp = (String) resendTwoFactorOtp.invoke(loginWindow);
            assertNotNull(secondOtp);
            assertTrue(((JLabel) getTwoFactorHeaderLabel.invoke(loginWindow)).getText().contains(secondOtp));

            // Old OTP is superseded; new OTP completes login
            assertFalse((Boolean) verifyTwoFactorCode.invoke(
                    loginWindow, "000000".equals(secondOtp) ? "999999" : "000000"));
            assertTrue((Boolean) verifyTwoFactorCode.invoke(loginWindow, secondOtp));
            assertNull(getLastIssuedOtp.invoke(loginWindow),
                    "Verified one-time OTP must be cleared from UI state");
            assertTrue(SessionManager.getInstance().isLoggedIn());
            assertEquals(alice.getUserId(), SessionManager.getInstance().getCurrentUser().getUserId());
        } finally {
            if (loginWindow instanceof JFrame frame) {
                frame.dispose();
            }
        }
    }

    @Test
    void testSetAndChangeHashedPinRequireReauthenticationAndEnforceAttemptLimits() {
        // Cannot set/change PIN without active session
        assertThrows(SecurityException.class,
                () -> authService.setPin(alice.getUserId(), "AlicePass123", "4321"));

        SessionManager.getInstance().login(alice, aliceWallet);
        assertFalse(authService.hasPin(alice.getUserId()));

        // Reject wrong password on setPin
        assertThrows(OtogException.class,
                () -> authService.setPin(alice.getUserId(), "WrongPassword", "4321"));
        assertFalse(authService.hasPin(alice.getUserId()));

        // Reject weak/non-numeric PIN formats
        assertThrows(OtogException.class,
                () -> authService.setPin(alice.getUserId(), "AlicePass123", "12"));
        assertThrows(OtogException.class,
                () -> authService.setPin(alice.getUserId(), "AlicePass123", "abcd"));

        // Valid setPin stores salted hash
        authService.setPin(alice.getUserId(), "AlicePass123", "4321");
        assertTrue(authService.hasPin(alice.getUserId()));

        Credential savedCred = credentialRepository.findByUserId(alice.getUserId()).orElseThrow();
        assertNotNull(savedCred.getPinHash());
        assertNotNull(savedCred.getPinSalt());
        assertNotEquals("4321", savedCred.getPinHash());
        assertTrue(PasswordUtil.verifyPassword("4321", savedCred.getPinSalt(), savedCred.getPinHash()));

        // Setting PIN must not implicitly authorize outgoing payments
        assertFalse(SessionManager.getInstance().isPinVerified());

        // Reject wrong current PIN on changePin
        assertThrows(OtogException.class,
                () -> authService.changePin(alice.getUserId(), "9999", "5678"));
        assertEquals(1, credentialRepository.findByUserId(alice.getUserId()).orElseThrow().getPinFailedAttempts());

        // Valid changePin updates salted hash and clears cached verification
        authService.changePin(alice.getUserId(), "4321", "5678");
        Credential updatedCred = credentialRepository.findByUserId(alice.getUserId()).orElseThrow();
        assertNotEquals(savedCred.getPinSalt(), updatedCred.getPinSalt());
        assertFalse(SessionManager.getInstance().isPinVerified());

        // Old PIN fails; verify 3 failed attempts lock out session and block valid PIN
        assertFalse(authService.verifyPin(alice.getUserId(), "4321")); // attempt 1
        assertFalse(SessionManager.getInstance().isLockedOutByPin());
        assertFalse(authService.verifyPin(alice.getUserId(), "1111")); // attempt 2
        assertFalse(SessionManager.getInstance().isLockedOutByPin());
        assertFalse(authService.verifyPin(alice.getUserId(), "2222")); // attempt 3 -> lockout

        assertTrue(SessionManager.getInstance().isLockedOutByPin());
        assertFalse(SessionManager.getInstance().isLoggedIn());
        assertThrows(SecurityException.class, () -> authService.verifyPin(alice.getUserId(), "5678"),
                "Locked-out PIN session must reject even the correct PIN");
    }

    @Test
    void testCrossLoginPinAttemptAccumulationLocksSession() {
        SessionManager session = SessionManager.getInstance();
        session.login(bob, bobWallet);
        authService.setPin(bob.getUserId(), "BobPass123", "7890");

        assertFalse(authService.verifyPin(bob.getUserId(), "0000")); // miss 1
        session.logout();

        session.login(bob, bobWallet);
        assertFalse(authService.verifyPin(bob.getUserId(), "1111")); // miss 2
        assertFalse(authService.verifyPin(bob.getUserId(), "2222")); // miss 3 (accumulated in DB)

        assertTrue(session.isLockedOutByPin(),
                "Accumulated failed PIN attempts across sessions must lock out SessionManager");
        assertFalse(session.isLoggedIn());
    }

    @Test
    void testSessionInactivityTimeoutExpiresAndCannotBeRevived() {
        SessionManager session = SessionManager.getInstance();
        session.setInactivityTimeout(Duration.ofMinutes(15));
        session.login(alice, aliceWallet);
        authService.setPin(alice.getUserId(), "AlicePass123", "1357");
        assertTrue(authService.verifyPin(alice.getUserId(), "1357"));
        assertTrue(session.isLoggedIn());
        assertTrue(session.isPinVerified());

        // Activity at 14m keeps session alive and extends timeout window
        clock.advance(Duration.ofMinutes(14));
        assertTrue(session.touchActivity());
        clock.advance(Duration.ofMinutes(10));
        assertTrue(session.isLoggedIn(), "Sliding activity should extend active session");

        // Idle past 15m expires session permanently until re-login
        clock.advance(Duration.ofMinutes(15));
        assertFalse(session.isLoggedIn());
        assertTrue(session.isExpiredDueToInactivity());
        assertNull(session.getCurrentUser());
        assertNull(session.getCurrentWallet());
        assertFalse(session.isPinVerified());
        assertFalse(session.touchActivity(), "Expired session must not revive on touchActivity()");
        assertThrows(SecurityException.class, session::requireAuthentication);
    }

    @Test
    void testOutgoingPaymentsBlockedWithoutValidPinAuthorizationWhileCashInAllowed() {
        // Configure Alice's PIN first while authenticated
        SessionManager session = SessionManager.getInstance();
        session.login(alice, aliceWallet);
        authService.setPin(alice.getUserId(), "AlicePass123", "2468");
        session.logout();

        // 1. Incoming cashIn works even when outgoing payment gates exist
        Transaction topUp = gatedWalletService.cashIn("CASHIN-01", aliceWallet.getWalletId(), null,
                new BigDecimal("250.00"), "Allowed deposit");
        assertEquals(new BigDecimal("250.00"), topUp.getAmount());
        assertEquals(new BigDecimal("1250.00"), gatedWalletService.getBalance(aliceWallet.getWalletId()));

        // 2. Outgoing transfer & cashOut blocked when logged out
        assertThrows(WalletException.class, () -> gatedWalletService.transfer(
                "TX-NO-SESSION", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("100.00"), BigDecimal.ZERO, "No session"));
        assertThrows(WalletException.class, () -> gatedWalletService.cashOut(
                "CO-NO-SESSION", aliceWallet.getWalletId(), null,
                new BigDecimal("100.00"), BigDecimal.ZERO, "No session"));

        // 3. Logged in as Alice WITHOUT PIN authorization -> blocked
        session.login(alice, aliceWallet);
        assertThrows(WalletException.class, () -> gatedWalletService.transfer(
                "TX-NO-PIN", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("100.00"), BigDecimal.ZERO, "Missing PIN auth"));
        assertThrows(WalletException.class, () -> gatedWalletService.cashOut(
                "CO-NO-PIN", aliceWallet.getWalletId(), null,
                new BigDecimal("100.00"), BigDecimal.ZERO, "Missing PIN auth"));

        // 4. Logged in with wrong user (Bob trying to spend Alice's wallet) -> blocked
        session.login(bob, bobWallet);
        assertThrows(WalletException.class, () -> gatedWalletService.transferWithPin(
                "TX-WRONG-OWNER", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("100.00"), BigDecimal.ZERO, "Cross-account", "2468"));

        // 5. Alice logged in with transferWithPin & wrong PIN -> blocked; valid PIN -> succeeds
        session.login(alice, aliceWallet);
        assertThrows(WalletException.class, () -> gatedWalletService.transferWithPin(
                "TX-BAD-PIN", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("100.00"), BigDecimal.ZERO, "Wrong PIN", "9999"));

        Transaction paidTx = gatedWalletService.transferWithPin(
                "TX-VALID-PIN", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("200.00"), BigDecimal.ZERO, "Authorized transfer", "2468");
        assertNotNull(paidTx);
        assertEquals(new BigDecimal("1050.00"), gatedWalletService.getBalance(aliceWallet.getWalletId()));
        assertEquals(new BigDecimal("200.00"), gatedWalletService.getBalance(bobWallet.getWalletId()));

        Transaction cashOutTx = gatedWalletService.cashOutWithPin(
                "CO-VALID-PIN", aliceWallet.getWalletId(), null,
                new BigDecimal("50.00"), BigDecimal.ZERO, "Authorized cash out", "2468");
        assertNotNull(cashOutTx);
        assertEquals(new BigDecimal("1000.00"), gatedWalletService.getBalance(aliceWallet.getWalletId()));

        // 6. Expired session blocks outgoing payment even if PIN was previously verified
        clock.advance(Duration.ofMinutes(SessionManager.INACTIVITY_TIMEOUT_MINUTES + 1L));
        assertThrows(WalletException.class, () -> gatedWalletService.transfer(
                "TX-EXPIRED", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("50.00"), BigDecimal.ZERO, "Expired session"));
        assertThrows(WalletException.class, () -> gatedWalletService.cashOutWithPin(
                "CO-EXPIRED", aliceWallet.getWalletId(), null,
                new BigDecimal("50.00"), BigDecimal.ZERO, "Expired session", "2468"));

        // 7. Wrong PIN during payment blocks payment after earlier verification & locks out after 3 misses
        session.login(alice, aliceWallet);
        assertTrue(authService.verifyPin(alice.getUserId(), "2468"));
        assertThrows(WalletException.class, () -> gatedWalletService.cashOutWithPin(
                "CO-FAIL-1", aliceWallet.getWalletId(), null,
                new BigDecimal("10.00"), BigDecimal.ZERO, "Fail 1", "0000"));
        assertThrows(WalletException.class, () -> gatedWalletService.transferWithPin(
                "TX-FAIL-2", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("10.00"), BigDecimal.ZERO, "Fail 2", "0000"));
        assertThrows(WalletException.class, () -> gatedWalletService.transferWithPin(
                "TX-FAIL-3", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("10.00"), BigDecimal.ZERO, "Fail 3", "0000"));
        assertTrue(session.isLockedOutByPin());
        assertThrows(WalletException.class, () -> gatedWalletService.transferWithPin(
                "TX-AFTER-LOCK", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("10.00"), BigDecimal.ZERO, "Locked", "2468"));
        assertEquals(new BigDecimal("1000.00"), gatedWalletService.getBalance(aliceWallet.getWalletId()));

        // 8. Persisted PIN lockout holds until password-reauthenticated setPin; logout clears session & OTP
        session.login(alice, aliceWallet);
        assertThrows(SecurityException.class, () -> authService.verifyPin(alice.getUserId(), "2468"));
        authService.setPin(alice.getUserId(), "AlicePass123", "2468");
        assertTrue(authService.verifyPin(alice.getUserId(), "2468"));
        clock.advance(Duration.ofSeconds(OtpService.RESEND_COOLDOWN_SECONDS + 1L));
        String pendingOtp = authService.issueTwoFactorOtp(alice.getUserId());
        authService.logout(alice.getUserId());
        assertFalse(session.isLoggedIn());
        assertFalse(session.isPinVerified());
        assertFalse(authService.verifyTwoFactorOtp(alice.getUserId(), pendingOtp));
        assertThrows(WalletException.class, () -> gatedWalletService.transfer(
                "TX-AFTER-LOGOUT", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("10.00"), BigDecimal.ZERO, "After logout"));
    }
}
