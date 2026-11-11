package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.InvalidAmountException;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.WalletService;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests AC 2: Use BigDecimal for money; validate positive amounts and explicit currency precision.
 */
public class BigDecimalMoneyValidationTest {
    private DatabaseManager databaseManager;
    private WalletService walletService;
    private Wallet senderWallet;
    private Wallet recipientWallet;

    @BeforeEach
    void setUp() {
        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        walletService = new WalletService(databaseManager);

        User u1 = new User(IdGenerator.nextUserId(), "Alice", "09170000001");
        User u2 = new User(IdGenerator.nextUserId(), "Bob", "09170000002");
        walletService.getUserRepository().save(u1);
        walletService.getUserRepository().save(u2);

        senderWallet = walletService.createWallet(u1.getUserId(), new BigDecimal("1000.00"), "PHP");
        recipientWallet = walletService.createWallet(u2.getUserId(), new BigDecimal("100.00"), "PHP");
    }

    @AfterEach
    void tearDown() {
        databaseManager.close();
    }

    @Test
    void testPrecisionAndScaleEnforcement() {
        // Valid 2-decimal scale
        BigDecimal valid = MoneyUtil.validatePositiveAmount(new BigDecimal("123.45"));
        assertEquals(2, valid.scale());
        assertEquals(new BigDecimal("123.45"), valid);

        // Valid whole number normalized to 2 decimals
        BigDecimal whole = MoneyUtil.validatePositiveAmount(new BigDecimal("100"));
        assertEquals(2, whole.scale());
        assertEquals(new BigDecimal("100.00"), whole);

        // Valid trailing zeros stripped to 2 decimals
        BigDecimal trailingZeros = MoneyUtil.validatePositiveAmount(new BigDecimal("50.500"));
        assertEquals(2, trailingZeros.scale());
        assertEquals(new BigDecimal("50.50"), trailingZeros);

        // Invalid fractional cents exceeding 2 decimal places (3 decimals)
        assertThrows(InvalidAmountException.class, () ->
                MoneyUtil.validatePositiveAmount(new BigDecimal("10.005")),
                "Fractional cents like 10.005 must be rejected");

        assertThrows(InvalidAmountException.class, () ->
                MoneyUtil.validatePositiveAmount(new BigDecimal("99.999")),
                "Fractional cents must be rejected");
    }

    @Test
    void testRejectZeroAndNegativeAmounts() {
        // Null check
        assertThrows(InvalidAmountException.class, () ->
                MoneyUtil.validatePositiveAmount(null),
                "Null amount must be rejected");

        // Zero check
        assertThrows(InvalidAmountException.class, () ->
                MoneyUtil.validatePositiveAmount(BigDecimal.ZERO),
                "Zero amount must be rejected");

        assertThrows(InvalidAmountException.class, () ->
                MoneyUtil.validatePositiveAmount(new BigDecimal("0.00")),
                "Zero amount must be rejected");

        // Negative check
        assertThrows(InvalidAmountException.class, () ->
                MoneyUtil.validatePositiveAmount(new BigDecimal("-1.00")),
                "Negative amount must be rejected");

        assertThrows(InvalidAmountException.class, () ->
                MoneyUtil.validatePositiveAmount(new BigDecimal("-0.01")),
                "Negative amount must be rejected");
    }

    @Test
    void testFeeValidation() {
        // Zero fee is permitted
        BigDecimal zeroFee = MoneyUtil.validateNonNegativeAmount(BigDecimal.ZERO);
        assertEquals(new BigDecimal("0.00"), zeroFee);

        // Positive fee is permitted
        BigDecimal fee = MoneyUtil.validateNonNegativeAmount(new BigDecimal("15.00"));
        assertEquals(new BigDecimal("15.00"), fee);

        // Negative fee must be rejected
        assertThrows(InvalidAmountException.class, () ->
                MoneyUtil.validateNonNegativeAmount(new BigDecimal("-5.00")));

        // Fee with > 2 decimal places must be rejected
        assertThrows(InvalidAmountException.class, () ->
                MoneyUtil.validateNonNegativeAmount(new BigDecimal("1.234")));
    }

    @Test
    void testCurrencyCodeValidationAndFormatting() {
        assertEquals("PHP", MoneyUtil.validateCurrency("PHP"));
        assertEquals("PHP", MoneyUtil.validateCurrency("php"));
        assertEquals("PHP", MoneyUtil.validateCurrency(null));
        assertEquals("USD", MoneyUtil.validateCurrency("USD"));

        assertThrows(InvalidAmountException.class, () -> MoneyUtil.validateCurrency("PHILIPPINE_PESO"));
        assertThrows(InvalidAmountException.class, () -> MoneyUtil.validateCurrency("P"));

        // Format
        String formatted = MoneyUtil.format(new BigDecimal("1234567.89"), "PHP");
        assertEquals("PHP 1,234,567.89", formatted);
    }

    @Test
    void testTransferRejectsInvalidAmounts() {
        // Zero transfer amount
        assertThrows(InvalidAmountException.class, () ->
                walletService.transfer("REQ-INV-1", senderWallet.getWalletId(), recipientWallet.getWalletId(),
                        BigDecimal.ZERO, BigDecimal.ZERO, "Transfer zero"));

        // Negative transfer amount
        assertThrows(InvalidAmountException.class, () ->
                walletService.transfer("REQ-INV-2", senderWallet.getWalletId(), recipientWallet.getWalletId(),
                        new BigDecimal("-50.00"), BigDecimal.ZERO, "Transfer negative"));

        // Fractional cents transfer amount
        assertThrows(InvalidAmountException.class, () ->
                walletService.transfer("REQ-INV-3", senderWallet.getWalletId(), recipientWallet.getWalletId(),
                        new BigDecimal("25.123"), BigDecimal.ZERO, "Transfer 3 decimal places"));

        // Negative fee
        assertThrows(InvalidAmountException.class, () ->
                walletService.transfer("REQ-INV-4", senderWallet.getWalletId(), recipientWallet.getWalletId(),
                        new BigDecimal("25.00"), new BigDecimal("-1.00"), "Transfer negative fee"));
    }

    @Test
    void testWalletDirectDebitCreditValidation() {
        // Direct debit rejects negative
        assertThrows(InvalidAmountException.class, () -> senderWallet.debit(new BigDecimal("-10.00")));
        // Direct debit rejects zero
        assertThrows(InvalidAmountException.class, () -> senderWallet.debit(BigDecimal.ZERO));
        // Direct credit rejects negative
        assertThrows(InvalidAmountException.class, () -> senderWallet.credit(new BigDecimal("-10.00")));
        // Direct credit rejects zero
        assertThrows(InvalidAmountException.class, () -> senderWallet.credit(BigDecimal.ZERO));
    }
}
