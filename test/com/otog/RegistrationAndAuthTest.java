package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.Credential;
import com.otog.model.User;
import com.otog.model.UserStatus;
import com.otog.model.Wallet;
import com.otog.repository.CredentialRepository;
import com.otog.repository.UserRepository;
import com.otog.repository.WalletRepository;
import com.otog.service.AuthServiceImpl;
import com.otog.service.SessionManager;
import com.otog.service.WalletService;
import com.otog.util.PasswordUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class RegistrationAndAuthTest {
    private DatabaseManager databaseManager;
    private UserRepository userRepository;
    private CredentialRepository credentialRepository;
    private WalletRepository walletRepository;
    private WalletService walletService;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        walletService = new WalletService(databaseManager);
        userRepository = walletService.getUserRepository();
        walletRepository = walletService.getWalletRepository();
        credentialRepository = new CredentialRepository(databaseManager);
        authService = new AuthServiceImpl(databaseManager, userRepository, credentialRepository, walletService);

        SessionManager.getInstance().reset();
    }

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().reset();
        databaseManager.close();
    }

    @Test
    void testRegisterUniqueAccountAndAutoCreateWallet() {
        String name = "Maria Clara";
        String mobile = "09179876543";
        String email = "maria@otog.ph";
        String password = "SecretPassword123";

        User registered = authService.registerUser(name, mobile, email, password);
        assertNotNull(registered.getUserId(), "Registered user must receive a generated user ID");
        assertEquals(name, registered.getName());
        assertEquals(mobile, registered.getMobileNumber());
        assertEquals(UserStatus.ACTIVE, registered.getStatus());

        // Verify user persisted in database
        Optional<User> persistedUser = userRepository.findById(registered.getUserId());
        assertTrue(persistedUser.isPresent(), "User must be found in database");

        // Verify wallet was automatically created with 0.00 PHP balance
        Optional<Wallet> walletOpt = walletRepository.findByUserId(registered.getUserId());
        assertTrue(walletOpt.isPresent(), "A wallet must be automatically created upon registration");
        assertEquals(registered.getUserId(), walletOpt.get().getUserId());
        assertEquals(new BigDecimal("0.00"), walletOpt.get().getBalance());
        assertEquals("PHP", walletOpt.get().getCurrency());

        // Verify salted password hash stored in credentials table (raw password never saved!)
        Optional<Credential> credOpt = credentialRepository.findByUserId(registered.getUserId());
        assertTrue(credOpt.isPresent(), "Credentials record must be stored");
        Credential cred = credOpt.get();
        assertNotEquals(password, cred.getPasswordHash(), "Plaintext password must NEVER be persisted");
        assertNotNull(cred.getSalt(), "A random salt must be generated and persisted");
        assertEquals(0, cred.getFailedAttempts(), "Initial failed attempts should be 0");

        // Verify hash matches salt + password
        assertTrue(PasswordUtil.verifyPassword(password, cred.getSalt(), cred.getPasswordHash()));
    }

    @Test
    void testRejectDuplicateMobileNumberAndDuplicateEmail() {
        authService.registerUser("User One", "09181112233", "user1@otog.ph", "pass1234");

        // Duplicate mobile number must be rejected
        OtogException mobileEx = assertThrows(OtogException.class, () ->
                authService.registerUser("User Two", "09181112233", "user2@otog.ph", "pass1234"));
        assertTrue(mobileEx.getMessage().contains("already registered"),
                "Exception message must mention mobile number already registered");

        // Duplicate email must be rejected
        OtogException emailEx = assertThrows(OtogException.class, () ->
                authService.registerUser("User Three", "09199998877", "user1@otog.ph", "pass1234"));
        assertTrue(emailEx.getMessage().contains("already registered"),
                "Exception message must mention email already registered");
    }

    @Test
    void testSuccessfulAuthenticationWithSaltedHash() {
        String mobile = "09205556677";
        String password = "CorrectHorseBatteryStaple!";
        User user = authService.registerUser("Jose Rizal", mobile, "jose@otog.ph", password);

        // Authenticate with mobile and correct password
        Optional<User> authOpt = authService.authenticatePassword(mobile, password);
        assertTrue(authOpt.isPresent(), "Authentication should succeed with valid mobile and password");
        assertEquals(user.getUserId(), authOpt.get().getUserId());

        // Authenticate with user name and correct password
        Optional<User> authByName = authService.authenticatePassword("Jose Rizal", password);
        assertTrue(authByName.isPresent(), "Authentication should succeed with valid name identifier");
    }

    @Test
    void testRejectIncorrectPassword() {
        String mobile = "09213334455";
        authService.registerUser("Andres B", mobile, "andres@otog.ph", "Katipunan1896");

        // Incorrect password returns empty Optional
        Optional<User> failed = authService.authenticatePassword(mobile, "WrongPassword");
        assertTrue(failed.isEmpty(), "Authentication must fail on wrong password");

        // Failed attempts count should be incremented
        User user = userRepository.findByMobileNumber(mobile).orElseThrow();
        Credential cred = credentialRepository.findByUserId(user.getUserId()).orElseThrow();
        assertEquals(1, cred.getFailedAttempts(), "Failed attempts should be incremented to 1");
    }

    @Test
    void testFailedLoginLimitsAndAccountLockout() {
        String mobile = "09224445566";
        User user = authService.registerUser("Emilio A", mobile, "emilio@otog.ph", "CavitePassword");

        // 4 failed attempts should not lock account yet
        for (int i = 1; i <= 4; i++) {
            Optional<User> attempt = authService.authenticatePassword(mobile, "BadPassword" + i);
            assertTrue(attempt.isEmpty());
        }

        User beforeLock = userRepository.findById(user.getUserId()).orElseThrow();
        assertEquals(UserStatus.ACTIVE, beforeLock.getStatus(), "Account should still be ACTIVE after 4 failed attempts");

        // 5th failed attempt should trigger lockout
        OtogException lockEx = assertThrows(OtogException.class, () ->
                authService.authenticatePassword(mobile, "BadPassword5"));
        assertTrue(lockEx.getMessage().contains("locked"), "Exception must mention account is locked");

        // Verify user status in database is now LOCKED
        User afterLock = userRepository.findById(user.getUserId()).orElseThrow();
        assertEquals(UserStatus.LOCKED, afterLock.getStatus(), "User status must be updated to LOCKED in database");

        // Even with correct password, a locked account cannot authenticate
        OtogException lockedLoginEx = assertThrows(OtogException.class, () ->
                authService.authenticatePassword(mobile, "CavitePassword"));
        assertTrue(lockedLoginEx.getMessage().contains("locked"));
    }

    @Test
    void testResetFailedAttemptsOnSuccessfulLogin() {
        String mobile = "09231112222";
        authService.registerUser("Apolinario M", mobile, "mabini@otog.ph", "SublimeParalytic");

        // 2 bad attempts
        authService.authenticatePassword(mobile, "wrong1");
        authService.authenticatePassword(mobile, "wrong2");

        User user = userRepository.findByMobileNumber(mobile).orElseThrow();
        Credential credBefore = credentialRepository.findByUserId(user.getUserId()).orElseThrow();
        assertEquals(2, credBefore.getFailedAttempts());

        // Now provide correct password
        Optional<User> success = authService.authenticatePassword(mobile, "SublimeParalytic");
        assertTrue(success.isPresent());

        // Failed attempts must be reset to 0
        Credential credAfter = credentialRepository.findByUserId(user.getUserId()).orElseThrow();
        assertEquals(0, credAfter.getFailedAttempts(), "Failed attempts must reset to 0 upon successful login");
    }

    @Test
    void testSingletonSessionManagerLifecycle() {
        SessionManager session = SessionManager.getInstance();
        assertSame(session, SessionManager.getInstance(), "SessionManager must be a Singleton");
        assertFalse(session.isLoggedIn(), "SessionManager should start without active login");

        // Attempting to access protected resource throws SecurityException
        assertThrows(SecurityException.class, session::requireAuthentication,
                "Accessing protected screens without login must throw SecurityException");

        // Perform login
        User user = new User("USR-123", "Test User", "09990001122");
        Wallet wallet = new Wallet("WLT-123", user.getUserId(), new BigDecimal("250.00"), "PHP");

        session.login(user, wallet);
        assertTrue(session.isLoggedIn(), "Session must be logged in after login()");
        assertEquals(user, session.getCurrentUser());
        assertEquals(wallet, session.getCurrentWallet());
        assertDoesNotThrow(session::requireAuthentication, "requireAuthentication must pass when logged in");

        // Logout
        session.logout();
        assertFalse(session.isLoggedIn(), "Session must be cleared on logout()");
        assertNull(session.getCurrentUser(), "Current user must be null after logout");
        assertNull(session.getCurrentWallet(), "Current wallet must be null after logout");
        assertThrows(SecurityException.class, session::requireAuthentication,
                "After logout, requireAuthentication must fail again");
    }

    @Test
    void testUserLoginComponentWithRealAuthService() throws Exception {
        Class<?> loginClass = Class.forName("UserLogin");
        Object loginWindow = loginClass.getConstructor(
                com.otog.service.AuthService.class,
                com.otog.service.WalletService.class
        ).newInstance(authService, walletService);

        try {
            Object registerBtn = loginClass.getMethod("getRegisterButton").invoke(loginWindow);
            assertNotNull(registerBtn, "UserLogin must expose a Register button");

            // Register a user
            authService.registerUser("Gabriela Silang", "09257778899", "gabriela@otog.ph", "GeneralGabriela");

            java.lang.reflect.Method attemptLogin = loginClass.getMethod("attemptLogin", String.class, String.class);
            java.lang.reflect.Method verifyTwoFactor = loginClass.getMethod("verifyTwoFactorCode", String.class);

            // Attempt login with invalid credentials
            assertFalse((Boolean) attemptLogin.invoke(loginWindow, "09257778899", "Wrong"));
            assertFalse(SessionManager.getInstance().isLoggedIn());

            // Attempt login with registered credentials
            assertTrue((Boolean) attemptLogin.invoke(loginWindow, "09257778899", "GeneralGabriela"));
            assertFalse(SessionManager.getInstance().isLoggedIn(), "Session must NOT be active until 2FA succeeds");

            // Two-Factor verification completes login and establishes session
            assertTrue((Boolean) verifyTwoFactor.invoke(loginWindow, "123456"));
            assertTrue(SessionManager.getInstance().isLoggedIn(), "Session must be active after successful 2FA");
            assertEquals("Gabriela Silang", SessionManager.getInstance().getCurrentUser().getName());
            assertNotNull(SessionManager.getInstance().getCurrentWallet());
        } finally {
            if (loginWindow instanceof javax.swing.JFrame frame) {
                frame.dispose();
            }
        }
    }
}
