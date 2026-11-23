package com.otog.service;

import com.otog.exception.EntityNotFoundException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.InvalidAmountException;
import com.otog.exception.MalformedQrException;
import com.otog.exception.WalletException;
import com.otog.model.MerchantQrPayload;
import com.otog.model.ReceiptBuilder;
import com.otog.model.Transaction;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.UserStatus;
import com.otog.model.Wallet;
import com.otog.model.WalletStatus;
import com.otog.repository.UserRepository;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Service managing project-specific Merchant QR image generation, file decoding,
 * merchant and currency validation, pre-payment confirmation, and atomic transfer execution.
 */
public class MerchantQrService {

    private static final byte[] MAGIC_HEADER = new byte[]{'O', 'T', 'O', 'G', 'Q', 'R'};
    public static final int QR_IMAGE_WIDTH = 300;
    public static final int QR_IMAGE_HEIGHT = 300;

    private final WalletOperations walletService;
    private final UserRepository userRepository;
    private final AuthService authService;

    public MerchantQrService(WalletOperations walletService, UserRepository userRepository, AuthService authService) {
        this.walletService = Objects.requireNonNull(walletService, "WalletOperations cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.authService = authService;
    }

    /**
     * Generates a visual 2D QR image containing the embedded project QR payload.
     */
    public BufferedImage generateQrImage(MerchantQrPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("MerchantQrPayload cannot be null");
        }

        BufferedImage image = new BufferedImage(QR_IMAGE_WIDTH, QR_IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, QR_IMAGE_WIDTH, QR_IMAGE_HEIGHT);

            // Draw header banner
            g2.setColor(new Color(24, 76, 120));
            g2.fillRect(0, 0, QR_IMAGE_WIDTH, 36);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("SansSerif", Font.BOLD, 14));
            FontMetrics fm = g2.getFontMetrics();
            String title = "OTOG MERCHANT QR";
            g2.drawString(title, (QR_IMAGE_WIDTH - fm.stringWidth(title)) / 2, 23);

            // Draw visual QR matrix with finder patterns
            int matrixSize = 200;
            int startX = (QR_IMAGE_WIDTH - matrixSize) / 2;
            int startY = 46;

            g2.setColor(Color.BLACK);
            // Finder patterns (3 corners)
            drawFinderPattern(g2, startX + 10, startY + 10, 42);
            drawFinderPattern(g2, startX + matrixSize - 52, startY + 10, 42);
            drawFinderPattern(g2, startX + 10, startY + matrixSize - 52, 42);

            // Alignment pattern bottom right
            g2.fillRect(startX + matrixSize - 36, startY + matrixSize - 36, 18, 18);
            g2.setColor(Color.WHITE);
            g2.fillRect(startX + matrixSize - 32, startY + matrixSize - 32, 10, 10);
            g2.setColor(Color.BLACK);
            g2.fillRect(startX + matrixSize - 29, startY + matrixSize - 29, 4, 4);

            // Timing patterns
            for (int x = startX + 56; x < startX + matrixSize - 56; x += 8) {
                g2.fillRect(x, startY + 28, 4, 4);
                g2.fillRect(startX + 28, startY + (x - startX), 4, 4);
            }

            // Pseudo-random deterministic module grid derived from payload checksum
            long seed = Math.abs(payload.getChecksum().hashCode() ^ payload.getQrReference().hashCode());
            java.util.Random rand = new java.util.Random(seed);
            int moduleSize = 6;
            for (int r = startY + 56; r < startY + matrixSize - 15; r += moduleSize) {
                for (int c = startX + 15; c < startX + matrixSize - 15; c += moduleSize) {
                    // Do not overwrite finder corners
                    if ((c < startX + 58 && r < startY + 58) ||
                            (c > startX + matrixSize - 58 && r < startY + 58) ||
                            (c < startX + 58 && r > startY + matrixSize - 58)) {
                        continue;
                    }
                    if (rand.nextBoolean()) {
                        g2.fillRect(c, r, moduleSize - 1, moduleSize - 1);
                    }
                }
            }

            // Footer with merchant name & amount
            g2.setColor(new Color(245, 247, 250));
            g2.fillRect(0, QR_IMAGE_HEIGHT - 44, QR_IMAGE_WIDTH, 44);
            g2.setColor(new Color(30, 41, 59));
            g2.setFont(new Font("SansSerif", Font.BOLD, 12));
            fm = g2.getFontMetrics();
            String nameStr = payload.getMerchantName();
            g2.drawString(nameStr, (QR_IMAGE_WIDTH - fm.stringWidth(nameStr)) / 2, QR_IMAGE_HEIGHT - 26);

            g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
            fm = g2.getFontMetrics();
            String amtStr = payload.hasFixedAmount()
                    ? payload.getCurrency() + " " + MoneyUtil.format(payload.getAmount(), "")
                    : "Pay Any Amount (" + payload.getCurrency() + ")";
            g2.drawString(amtStr, (QR_IMAGE_WIDTH - fm.stringWidth(amtStr)) / 2, QR_IMAGE_HEIGHT - 10);
        } finally {
            g2.dispose();
        }

        // Lossless raster data encoding along bottom rows
        embedPayloadIntoImage(image, payload);
        return image;
    }

    /**
     * Exports a generated QR image to the specified target file path as PNG.
     */
    public Path exportQrImage(MerchantQrPayload payload, Path targetFile) {
        if (targetFile == null) {
            throw new IllegalArgumentException("Target file path cannot be null");
        }
        BufferedImage image = generateQrImage(payload);
        try {
            File file = targetFile.toFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            boolean ok = ImageIO.write(image, "PNG", file);
            if (!ok) {
                throw new WalletException("Failed to encode QR image as PNG: No appropriate writer found");
            }
            return targetFile;
        } catch (IOException ex) {
            throw new WalletException("Failed to write QR image file: " + ex.getMessage(), ex);
        }
    }

    /**
     * Imports and parses an image file, extracting the project-specific QR payload.
     */
    public MerchantQrPayload importQrImage(Path sourceFile) {
        if (sourceFile == null) {
            throw new MalformedQrException("QR source file cannot be null");
        }
        File file = sourceFile.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new MalformedQrException("QR image file does not exist: " + sourceFile);
        }

        BufferedImage image;
        try {
            image = ImageIO.read(file);
        } catch (IOException ex) {
            throw new MalformedQrException("Failed to read image file: " + ex.getMessage(), ex);
        }

        if (image == null) {
            throw new MalformedQrException("The selected file is not a valid or readable image.");
        }

        String rawText = extractPayloadFromImage(image);
        return MerchantQrPayload.parse(rawText);
    }

    /**
     * Validates that the merchant and target wallet exist and are currently active.
     */
    public User validateMerchant(MerchantQrPayload payload) {
        if (payload == null) {
            throw new MalformedQrException("QR payload cannot be null");
        }
        if (!MerchantQrPayload.DEFAULT_CURRENCY.equalsIgnoreCase(payload.getCurrency())) {
            throw new WalletException("Unsupported currency: " + payload.getCurrency() + ". Only "
                    + MerchantQrPayload.DEFAULT_CURRENCY + " is supported.");
        }

        User merchant = userRepository.findById(payload.getMerchantId())
                .orElseThrow(() -> new EntityNotFoundException("Merchant user", payload.getMerchantId()));

        if (merchant.getRole() != UserRole.MERCHANT) {
            throw new WalletException("Account " + merchant.getUserId() + " is not a registered merchant (Role: "
                    + merchant.getRole() + ").");
        }

        if (merchant.getStatus() != UserStatus.ACTIVE) {
            throw new WalletException("Merchant account is inactive: " + merchant.getStatus());
        }

        Wallet merchantWallet = walletService.getWallet(payload.getMerchantWalletId());
        if (merchantWallet == null) {
            throw new EntityNotFoundException("Merchant wallet", payload.getMerchantWalletId());
        }
        if (!merchant.getUserId().equals(merchantWallet.getUserId())) {
            throw new WalletException("Merchant wallet ownership mismatch: wallet does not belong to merchant.");
        }
        if (merchantWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Merchant wallet is not active: " + merchantWallet.getStatus());
        }

        return merchant;
    }

    /**
     * Generates a pre-confirmation payment preview validating balance, merchant state, and amount.
     */
    public MerchantPaymentPreview previewPayment(String customerUserId, MerchantQrPayload payload, BigDecimal inputAmount) {
        if (customerUserId == null || customerUserId.trim().isEmpty()) {
            throw new IllegalArgumentException("Customer user ID cannot be null or empty");
        }
        String cleanCustomer = customerUserId.trim();

        // Validate merchant and currency
        User merchant = validateMerchant(payload);

        // Reject self-payment
        if (merchant.getUserId().equals(cleanCustomer)) {
            throw new WalletException("Self-payment to owned merchant account is not permitted.");
        }

        // Determine effective amount
        BigDecimal effectiveAmount;
        if (payload.hasFixedAmount()) {
            effectiveAmount = payload.getAmount();
        } else {
            if (inputAmount == null) {
                throw new InvalidAmountException("Payment amount is required for open merchant QR.");
            }
            effectiveAmount = MoneyUtil.validatePositiveAmount(inputAmount);
        }

        // Validate customer account status before previewing payment
        User customerUser = userRepository.findById(cleanCustomer)
                .orElseThrow(() -> new EntityNotFoundException("Customer user", cleanCustomer));
        if (customerUser.getStatus() == UserStatus.SUSPENDED) {
            throw new WalletException("Suspended accounts cannot initiate payments: User " + cleanCustomer + " is suspended.");
        }
        if (customerUser.getStatus() == UserStatus.LOCKED) {
            throw new WalletException("Account is locked: User " + cleanCustomer + " is locked.");
        }
        if (customerUser.getStatus() != UserStatus.ACTIVE) {
            throw new WalletException("Customer account is not active: " + customerUser.getStatus());
        }

        // Validate customer wallet & balance
        Wallet customerWallet = walletService.getWalletByUserId(cleanCustomer);
        if (customerWallet == null) {
            throw new EntityNotFoundException("Customer wallet for user", cleanCustomer);
        }
        if (customerWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletException("Customer wallet is not active: " + customerWallet.getStatus());
        }

        BigDecimal balance = customerWallet.getBalance();
        if (balance.compareTo(effectiveAmount) < 0) {
            throw new InsufficientFundsException(customerWallet.getWalletId(), balance, effectiveAmount);
        }

        String note = (payload.getNote() != null && !payload.getNote().isEmpty())
                ? payload.getNote()
                : "QR Payment to " + merchant.getName();

        return new MerchantPaymentPreview(
                cleanCustomer,
                customerWallet.getWalletId(),
                merchant,
                payload.getMerchantWalletId(),
                effectiveAmount,
                payload.getCurrency(),
                payload.getQrReference(),
                note
        );
    }

    /**
     * Executes the merchant QR payment atomically via {@link WalletOperations#transferWithPin}.
     */
    public Transaction confirmAndPay(MerchantPaymentPreview preview, String rawPin) {
        if (preview == null) {
            throw new IllegalArgumentException("Payment preview cannot be null");
        }
        if (rawPin == null || rawPin.trim().isEmpty()) {
            throw new WalletException("Transaction PIN is required.");
        }

        // Re-validate merchant status right before debiting
        validateMerchant(new MerchantQrPayload(
                preview.getMerchant().getUserId(),
                preview.getMerchantWalletId(),
                preview.getMerchant().getName(),
                preview.getAmount(),
                preview.getCurrency(),
                preview.getQrReference(),
                preview.getNote()
        ));

        String transferRef = "REQ-QR-" + preview.getQrReference();
        String description = "QR Pay: " + preview.getMerchant().getName()
                + (preview.getNote() != null && !preview.getNote().isEmpty() ? " - " + preview.getNote() : "");

        return walletService.transferWithPin(
                transferRef,
                preview.getCustomerWalletId(),
                preview.getMerchantWalletId(),
                preview.getAmount(),
                BigDecimal.ZERO,
                description,
                rawPin
        );
    }

    // --- Private Visual & Lossless Raster Helpers ---

    private static void drawFinderPattern(Graphics2D g2, int x, int y, int size) {
        g2.setColor(Color.BLACK);
        g2.fillRect(x, y, size, size);
        g2.setColor(Color.WHITE);
        g2.fillRect(x + 6, y + 6, size - 12, size - 12);
        g2.setColor(Color.BLACK);
        g2.fillRect(x + 12, y + 12, size - 24, size - 24);
    }

    private static void embedPayloadIntoImage(BufferedImage image, MerchantQrPayload payload) {
        String encoded = payload.encode();
        byte[] payloadBytes = encoded.getBytes(StandardCharsets.UTF_8);

        CRC32 crc = new CRC32();
        crc.update(payloadBytes);
        int crcVal = (int) crc.getValue();

        int totalLen = MAGIC_HEADER.length + 4 + payloadBytes.length + 4;
        byte[] buffer = new byte[totalLen];
        int pos = 0;

        System.arraycopy(MAGIC_HEADER, 0, buffer, pos, MAGIC_HEADER.length);
        pos += MAGIC_HEADER.length;

        // 4 bytes payload length
        int len = payloadBytes.length;
        buffer[pos++] = (byte) ((len >>> 24) & 0xFF);
        buffer[pos++] = (byte) ((len >>> 16) & 0xFF);
        buffer[pos++] = (byte) ((len >>> 8) & 0xFF);
        buffer[pos++] = (byte) (len & 0xFF);

        // payload bytes
        System.arraycopy(payloadBytes, 0, buffer, pos, payloadBytes.length);
        pos += payloadBytes.length;

        // 4 bytes crc
        buffer[pos++] = (byte) ((crcVal >>> 24) & 0xFF);
        buffer[pos++] = (byte) ((crcVal >>> 16) & 0xFF);
        buffer[pos++] = (byte) ((crcVal >>> 8) & 0xFF);
        buffer[pos++] = (byte) (crcVal & 0xFF);

        // Write into pixels at the very bottom edge (lossless RGB preservation)
        int width = image.getWidth();
        int height = image.getHeight();
        for (int i = 0; i < buffer.length; i++) {
            int x = i % width;
            int y = (height - 1) - (i / width);
            if (y < 0) {
                break;
            }
            int val = buffer[i] & 0xFF;
            image.setRGB(x, y, (0xFF << 24) | (val << 16) | (val << 8) | val);
        }
    }

    private static String extractPayloadFromImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        long pixelCapacity = (long) width * (long) height;
        int magicLen = MAGIC_HEADER.length;
        if (width <= 0 || height <= 0 || pixelCapacity < (long) magicLen + 8L) {
            throw new MalformedQrException("Selected image is too small to contain an OTOG Merchant QR payload.");
        }

        // 1. Read and check magic header
        byte[] magic = new byte[magicLen];
        for (int i = 0; i < magicLen; i++) {
            int x = i % width;
            int y = (height - 1) - (i / width);
            magic[i] = (byte) (image.getRGB(x, y) & 0xFF);
            if (magic[i] != MAGIC_HEADER[i]) {
                throw new MalformedQrException("Selected image is not a valid OTOG Merchant QR code (missing marker).");
            }
        }

        // 2. Read 4 bytes length
        int offset = magicLen;
        int len = 0;
        for (int i = 0; i < 4; i++) {
            int idx = offset + i;
            int x = idx % width;
            int y = (height - 1) - (idx / width);
            int b = image.getRGB(x, y) & 0xFF;
            len = (len << 8) | b;
        }

        if (len <= 0 || len > 10000 || (long) magicLen + 4L + (long) len + 4L > pixelCapacity) {
            throw new MalformedQrException("Corrupted QR payload length: " + len);
        }

        offset += 4;
        byte[] payloadBytes = new byte[len];
        for (int i = 0; i < len; i++) {
            int idx = offset + i;
            int x = idx % width;
            int y = (height - 1) - (idx / width);
            payloadBytes[i] = (byte) (image.getRGB(x, y) & 0xFF);
        }

        offset += len;
        int expectedCrc = 0;
        for (int i = 0; i < 4; i++) {
            int idx = offset + i;
            int x = idx % width;
            int y = (height - 1) - (idx / width);
            int b = image.getRGB(x, y) & 0xFF;
            expectedCrc = (expectedCrc << 8) | b;
        }

        CRC32 crc = new CRC32();
        crc.update(payloadBytes);
        int actualCrc = (int) crc.getValue();

        if (actualCrc != expectedCrc) {
            throw new MalformedQrException("Corrupted QR code: raster checksum mismatch.");
        }

        return new String(payloadBytes, StandardCharsets.UTF_8);
    }

    /**
     * Immutable confirmation summary displayed to the customer before PIN entry.
     */
    public static final class MerchantPaymentPreview {
        private final String customerUserId;
        private final String customerWalletId;
        private final User merchant;
        private final String merchantWalletId;
        private final BigDecimal amount;
        private final String currency;
        private final String qrReference;
        private final String note;

        public MerchantPaymentPreview(String customerUserId,
                                      String customerWalletId,
                                      User merchant,
                                      String merchantWalletId,
                                      BigDecimal amount,
                                      String currency,
                                      String qrReference,
                                      String note) {
            this.customerUserId = customerUserId;
            this.customerWalletId = customerWalletId;
            this.merchant = merchant;
            this.merchantWalletId = merchantWalletId;
            this.amount = amount;
            this.currency = currency;
            this.qrReference = qrReference;
            this.note = note;
        }

        public String getCustomerUserId() {
            return customerUserId;
        }

        public String getCustomerWalletId() {
            return customerWalletId;
        }

        public User getMerchant() {
            return merchant;
        }

        public String getMerchantWalletId() {
            return merchantWalletId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getCurrency() {
            return currency;
        }

        public String getQrReference() {
            return qrReference;
        }

        public String getNote() {
            return note;
        }

        public String toConfirmationText() {
            StringBuilder sb = new StringBuilder();
            sb.append("========================================\n");
            sb.append("       MERCHANT PAYMENT CONFIRMATION    \n");
            sb.append("========================================\n");
            sb.append("Merchant Name:   ").append(merchant.getName()).append("\n");
            sb.append("Merchant ID:     ").append(merchant.getUserId()).append("\n");
            sb.append("Merchant Mobile: ").append(ReceiptBuilder.maskIdentifier(merchant.getMobileNumber())).append("\n");
            sb.append("Amount:          ").append(MoneyUtil.format(amount, currency)).append("\n");
            sb.append("QR Reference:    ").append(qrReference).append("\n");
            if (note != null && !note.isEmpty()) {
                sb.append("Note:            ").append(note).append("\n");
            }
            sb.append("========================================\n");
            return sb.toString();
        }
    }
}
