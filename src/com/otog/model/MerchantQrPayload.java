package com.otog.model;

import com.otog.exception.InvalidAmountException;
import com.otog.exception.MalformedQrException;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Immutable domain payload for project-specific Merchant QR codes.
 * Contains merchant identity, target wallet, pre-configured or dynamic amount,
 * currency, unique QR reference, optional note, and integrity checksum.
 */
public final class MerchantQrPayload {

    public static final String QR_HEADER = "OTOG-MERCHANT-QR-v1";
    public static final String DEFAULT_CURRENCY = "PHP";

    private final String merchantId;
    private final String merchantWalletId;
    private final String merchantName;
    private final BigDecimal amount;
    private final String currency;
    private final String qrReference;
    private final String note;
    private final String checksum;

    public MerchantQrPayload(String merchantId,
                             String merchantWalletId,
                             String merchantName,
                             BigDecimal amount,
                             String currency,
                             String qrReference,
                             String note) {
        if (merchantId == null || merchantId.trim().isEmpty()) {
            throw new MalformedQrException("Merchant ID must not be null or empty");
        }
        if (merchantWalletId == null || merchantWalletId.trim().isEmpty()) {
            throw new MalformedQrException("Merchant wallet ID must not be null or empty");
        }
        if (qrReference == null || qrReference.trim().isEmpty()) {
            throw new MalformedQrException("QR reference must not be null or empty");
        }

        this.merchantId = merchantId.trim();
        this.merchantWalletId = merchantWalletId.trim();
        this.merchantName = (merchantName != null && !merchantName.trim().isEmpty())
                ? merchantName.trim()
                : "Merchant";
        this.currency = (currency != null && !currency.trim().isEmpty())
                ? currency.trim().toUpperCase(java.util.Locale.ROOT)
                : DEFAULT_CURRENCY;
        this.qrReference = qrReference.trim();
        this.note = (note != null && !note.trim().isEmpty()) ? note.trim() : "";

        if (amount != null) {
            this.amount = MoneyUtil.validatePositiveAmount(amount);
        } else {
            this.amount = null;
        }

        this.checksum = calculateChecksum(buildCanonicalData(
                this.merchantId, this.merchantWalletId, this.merchantName,
                this.amount, this.currency, this.qrReference, this.note));
    }

    private MerchantQrPayload(String merchantId,
                              String merchantWalletId,
                              String merchantName,
                              BigDecimal amount,
                              String currency,
                              String qrReference,
                              String note,
                              String checksum) {
        this.merchantId = merchantId;
        this.merchantWalletId = merchantWalletId;
        this.merchantName = merchantName;
        this.amount = amount;
        this.currency = currency;
        this.qrReference = qrReference;
        this.note = note;
        this.checksum = checksum;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getMerchantWalletId() {
        return merchantWalletId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public boolean hasFixedAmount() {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
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

    public String getChecksum() {
        return checksum;
    }

    /**
     * Serializes this payload into the canonical documented project wire format.
     */
    public String encode() {
        String canonical = buildCanonicalData(
                merchantId, merchantWalletId, merchantName, amount, currency, qrReference, note);
        return QR_HEADER + "|" + canonical + "|crc=" + checksum;
    }

    /**
     * Parses and verifies a raw QR code text payload.
     *
     * @param rawText the raw text extracted from the QR code
     * @return the verified {@link MerchantQrPayload}
     * @throws MalformedQrException if parsing fails or checksum does not match
     */
    public static MerchantQrPayload parse(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            throw new MalformedQrException("QR code payload is empty or unreadable");
        }

        String trimmed = rawText.trim();
        if (!trimmed.startsWith(QR_HEADER + "|")) {
            throw new MalformedQrException("Invalid QR code format: missing expected header '" + QR_HEADER + "'");
        }

        String body = trimmed.substring((QR_HEADER + "|").length());
        String[] parts = body.split("\\|");
        Map<String, String> fields = new HashMap<>();
        String declaredCrc = null;

        for (String part : parts) {
            int eqIdx = part.indexOf('=');
            if (eqIdx <= 0) {
                continue;
            }
            String key = part.substring(0, eqIdx).trim();
            String val = part.substring(eqIdx + 1).trim();
            if ("crc".equalsIgnoreCase(key)) {
                declaredCrc = val;
            } else {
                fields.put(key.toLowerCase(java.util.Locale.ROOT), val);
            }
        }

        String mid = fields.get("mid");
        String mwid = fields.get("mwid");
        String name = fields.get("name");
        String amtStr = fields.get("amt");
        String curr = fields.get("curr");
        String ref = fields.get("ref");
        String note = fields.getOrDefault("note", "");

        if (mid == null || mid.isEmpty()) {
            throw new MalformedQrException("Malformed QR code: missing merchant identifier (mid)");
        }
        if (mwid == null || mwid.isEmpty()) {
            throw new MalformedQrException("Malformed QR code: missing merchant wallet identifier (mwid)");
        }
        if (ref == null || ref.isEmpty()) {
            throw new MalformedQrException("Malformed QR code: missing unique QR reference (ref)");
        }

        BigDecimal parsedAmount = null;
        if (amtStr != null && !amtStr.isEmpty() && !"null".equalsIgnoreCase(amtStr)) {
            try {
                parsedAmount = MoneyUtil.parse(amtStr);
                if (parsedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new InvalidAmountException("QR payment amount must be strictly positive");
                }
                parsedAmount = MoneyUtil.validatePositiveAmount(parsedAmount);
            } catch (Exception ex) {
                throw new MalformedQrException("Malformed QR code: invalid amount '" + amtStr + "': " + ex.getMessage(), ex);
            }
        }

        String effectiveCurrency = (curr != null && !curr.isEmpty())
                ? curr.toUpperCase(java.util.Locale.ROOT)
                : DEFAULT_CURRENCY;

        String canonical = buildCanonicalData(mid, mwid, name != null ? name : "Merchant",
                parsedAmount, effectiveCurrency, ref, note);
        String expectedCrc = calculateChecksum(canonical);

        if (declaredCrc == null || !declaredCrc.equalsIgnoreCase(expectedCrc)) {
            throw new MalformedQrException("QR code data integrity check failed: checksum mismatch (expected "
                    + expectedCrc + ", found " + declaredCrc + ")");
        }

        return new MerchantQrPayload(mid, mwid, name != null ? name : "Merchant",
                parsedAmount, effectiveCurrency, ref, note, declaredCrc);
    }

    private static String buildCanonicalData(String mid, String mwid, String name,
                                             BigDecimal amt, String curr, String ref, String note) {
        return "mid=" + mid +
                "|mwid=" + mwid +
                "|name=" + (name != null ? name : "") +
                "|amt=" + (amt != null ? amt.toPlainString() : "") +
                "|curr=" + (curr != null ? curr : DEFAULT_CURRENCY) +
                "|ref=" + ref +
                "|note=" + (note != null ? note : "");
    }

    private static String calculateChecksum(String data) {
        CRC32 crc = new CRC32();
        crc.update(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue()).toUpperCase(java.util.Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MerchantQrPayload that = (MerchantQrPayload) o;
        return Objects.equals(merchantId, that.merchantId) &&
                Objects.equals(merchantWalletId, that.merchantWalletId) &&
                Objects.equals(qrReference, that.qrReference) &&
                Objects.equals(checksum, that.checksum);
    }

    @Override
    public int hashCode() {
        return Objects.hash(merchantId, merchantWalletId, qrReference, checksum);
    }

    @Override
    public String toString() {
        return "MerchantQrPayload{" +
                "merchantId='" + merchantId + '\'' +
                ", merchantWalletId='" + merchantWalletId + '\'' +
                ", merchantName='" + merchantName + '\'' +
                ", amount=" + amount +
                ", currency='" + currency + '\'' +
                ", qrReference='" + qrReference + '\'' +
                ", note='" + note + '\'' +
                '}';
    }
}
