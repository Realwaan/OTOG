package com.otog.model;

import com.otog.exception.MalformedQrException;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Immutable domain payload for project-specific Merchant QR codes.
 *
 * <h3>Documented Wire Grammar ({@code OTOG-MERCHANT-QR-v1})</h3>
 * <pre>
 * OTOG-MERCHANT-QR-v1|mid=&lt;id&gt;|mwid=&lt;walletId&gt;|name=&lt;escapedName&gt;|amt=&lt;amountOrEmpty&gt;|curr=&lt;ISO4217&gt;|ref=&lt;uniqueRef&gt;|note=&lt;escapedNote&gt;|crc=&lt;HEX_CRC32&gt;
 * </pre>
 * <ul>
 *   <li>{@code mid}: non-empty Merchant user ID (no delimiters or control characters).</li>
 *   <li>{@code mwid}: non-empty Merchant wallet ID (no delimiters or control characters).</li>
 *   <li>{@code name}: non-empty Merchant display name; delimiter/control characters ({@code %}, {@code |}, {@code =}, {@code \r}, {@code \n}) are percent-escaped.</li>
 *   <li>{@code amt}: optional positive decimal with scale &le; 2 (empty string for customer-entered open amount).</li>
 *   <li>{@code curr}: required 3-letter uppercase ISO currency code (e.g. {@code PHP}).</li>
 *   <li>{@code ref}: non-empty unique single-use QR reference (no delimiters or control characters).</li>
 *   <li>{@code note}: optional order/payment note; delimiter/control characters are percent-escaped.</li>
 *   <li>{@code crc}: uppercase hex IEEE CRC-32 computed over the exact raw UTF-8 canonical segment
 *       between {@code OTOG-MERCHANT-QR-v1|} and {@code |crc=}.</li>
 * </ul>
 */
public final class MerchantQrPayload {

    public static final String QR_HEADER = "OTOG-MERCHANT-QR-v1";
    public static final String DEFAULT_CURRENCY = "PHP";
    public static final int MAX_PAYLOAD_BYTES = 8192;

    private static final Set<String> ALLOWED_KEYS = Set.of("mid", "mwid", "name", "amt", "curr", "ref", "note");

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

        this.merchantId = validateStrictToken(merchantId.trim(), "Merchant ID");
        this.merchantWalletId = validateStrictToken(merchantWalletId.trim(), "Merchant wallet ID");
        this.merchantName = (merchantName != null && !merchantName.trim().isEmpty())
                ? merchantName.trim()
                : "Merchant";

        String configuredDefaultCurrency = OtogConfig.getInstance().getDefaultCurrency();
        String rawCurrency = (currency != null && !currency.trim().isEmpty())
                ? currency.trim().toUpperCase(Locale.ROOT)
                : (configuredDefaultCurrency != null && !configuredDefaultCurrency.isBlank()
                ? configuredDefaultCurrency.trim().toUpperCase(Locale.ROOT)
                : DEFAULT_CURRENCY);
        this.currency = validateStrictToken(rawCurrency, "Currency");
        this.qrReference = validateStrictToken(qrReference.trim(), "QR reference");
        this.note = (note != null && !note.trim().isEmpty()) ? note.trim() : "";

        if (amount != null) {
            this.amount = MoneyUtil.validatePositiveAmount(amount);
        } else {
            this.amount = null;
        }

        String canonical = buildCanonicalData(
                this.merchantId, this.merchantWalletId, this.merchantName,
                this.amount, this.currency, this.qrReference, this.note);
        this.checksum = calculateChecksum(canonical);
        String encoded = QR_HEADER + "|" + canonical + "|crc=" + this.checksum;
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new MalformedQrException("QR payload exceeds maximum allowed size of "
                    + MAX_PAYLOAD_BYTES + " bytes.");
        }
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
     * Serializes this payload into the canonical documented {@code OTOG-MERCHANT-QR-v1} wire format.
     */
    public String encode() {
        String canonical = buildCanonicalData(
                merchantId, merchantWalletId, merchantName, amount, currency, qrReference, note);
        return QR_HEADER + "|" + canonical + "|crc=" + checksum;
    }

    /**
     * Strictly parses and verifies a raw QR code text payload against the documented
     * {@code OTOG-MERCHANT-QR-v1} grammar and raw-segment CRC-32 checksum.
     *
     * @param rawText the raw text extracted from the QR code
     * @return the verified {@link MerchantQrPayload}
     * @throws MalformedQrException if grammar, required fields, amount scale, or checksum validation fails
     */
    public static MerchantQrPayload parse(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            throw new MalformedQrException("QR code payload is empty or unreadable");
        }

        String trimmed = rawText.trim();
        if (trimmed.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new MalformedQrException("QR code payload exceeds maximum allowed size (" + MAX_PAYLOAD_BYTES + " bytes)");
        }
        String headerPrefix = QR_HEADER + "|";
        if (!trimmed.startsWith(headerPrefix)) {
            throw new MalformedQrException("Invalid QR code format: missing expected header '" + QR_HEADER + "'");
        }

        String bodyWithCrc = trimmed.substring(headerPrefix.length());
        int crcMarkerIdx = bodyWithCrc.lastIndexOf("|crc=");
        if (crcMarkerIdx <= 0) {
            throw new MalformedQrException("Malformed QR code: missing trailing '|crc=' checksum segment");
        }

        String rawCanonicalSegment = bodyWithCrc.substring(0, crcMarkerIdx);
        String declaredCrc = bodyWithCrc.substring(crcMarkerIdx + "|crc=".length());
        if (declaredCrc.isEmpty() || !declaredCrc.matches("^[0-9A-Fa-f]{1,8}$")) {
            throw new MalformedQrException("Malformed QR code: invalid checksum token '" + declaredCrc + "'");
        }

        // Verify CRC against the exact raw canonical segment before decoding fields
        String expectedRawCrc = calculateChecksum(rawCanonicalSegment);
        if (!declaredCrc.equalsIgnoreCase(expectedRawCrc)) {
            throw new MalformedQrException("QR code data integrity check failed: checksum mismatch (expected "
                    + expectedRawCrc + ", found " + declaredCrc + ")");
        }

        String[] parts = rawCanonicalSegment.split("\\|", -1);
        Map<String, String> fields = new HashMap<>();

        for (String part : parts) {
            int eqIdx = part.indexOf('=');
            if (eqIdx <= 0) {
                throw new MalformedQrException("Malformed QR code: invalid field token '" + part + "'");
            }
            String key = part.substring(0, eqIdx).trim().toLowerCase(Locale.ROOT);
            String val = part.substring(eqIdx + 1);
            if (!ALLOWED_KEYS.contains(key)) {
                throw new MalformedQrException("Malformed QR code: unrecognized field '" + key + "'");
            }
            if (fields.containsKey(key)) {
                throw new MalformedQrException("Malformed QR code: duplicate field '" + key + "'");
            }
            fields.put(key, val);
        }

        if (!fields.containsKey("mid") || fields.get("mid").trim().isEmpty()) {
            throw new MalformedQrException("Malformed QR code: missing merchant identifier (mid)");
        }
        if (!fields.containsKey("mwid") || fields.get("mwid").trim().isEmpty()) {
            throw new MalformedQrException("Malformed QR code: missing merchant wallet identifier (mwid)");
        }
        if (!fields.containsKey("name") || fields.get("name").trim().isEmpty()) {
            throw new MalformedQrException("Malformed QR code: missing merchant name (name)");
        }
        if (!fields.containsKey("curr") || fields.get("curr").trim().isEmpty()) {
            throw new MalformedQrException("Malformed QR code: missing currency (curr)");
        }
        if (!fields.containsKey("ref") || fields.get("ref").trim().isEmpty()) {
            throw new MalformedQrException("Malformed QR code: missing unique QR reference (ref)");
        }

        String mid = validateStrictToken(fields.get("mid").trim(), "Merchant ID");
        String mwid = validateStrictToken(fields.get("mwid").trim(), "Merchant wallet ID");
        String name = unescapeFieldValue(fields.get("name")).trim();
        if (name.isEmpty()) {
            throw new MalformedQrException("Malformed QR code: merchant name (name) must not be blank");
        }
        String curr = validateStrictToken(fields.get("curr").trim().toUpperCase(Locale.ROOT), "Currency");
        String ref = validateStrictToken(fields.get("ref").trim(), "QR reference");
        String note = fields.containsKey("note") ? unescapeFieldValue(fields.get("note")).trim() : "";

        BigDecimal parsedAmount = null;
        String amtStr = fields.get("amt");
        if (amtStr != null && !amtStr.trim().isEmpty()) {
            try {
                BigDecimal rawDecimal = new BigDecimal(amtStr.trim());
                parsedAmount = MoneyUtil.validatePositiveAmount(rawDecimal);
            } catch (Exception ex) {
                throw new MalformedQrException("Malformed QR code: invalid amount '" + amtStr + "': " + ex.getMessage(), ex);
            }
        }

        return new MerchantQrPayload(mid, mwid, name, parsedAmount, curr, ref, note, declaredCrc.toUpperCase(Locale.ROOT));
    }

    private static String validateStrictToken(String value, String label) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '|' || c == '=' || c == '\r' || c == '\n' || c == '%') {
                throw new MalformedQrException(label + " contains forbidden delimiter character: '" + c + "'");
            }
        }
        return value;
    }

    private static String escapeFieldValue(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '%' -> sb.append("%25");
                case '|' -> sb.append("%7C");
                case '=' -> sb.append("%3D");
                case '\r' -> sb.append("%0D");
                case '\n' -> sb.append("%0A");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescapeFieldValue(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%') {
                if (i + 2 >= encoded.length()) {
                    throw new MalformedQrException("Malformed QR code: truncated escape sequence");
                }
                String hex = encoded.substring(i + 1, i + 3).toUpperCase(Locale.ROOT);
                switch (hex) {
                    case "25" -> sb.append('%');
                    case "7C" -> sb.append('|');
                    case "3D" -> sb.append('=');
                    case "0D" -> sb.append('\r');
                    case "0A" -> sb.append('\n');
                    default -> throw new MalformedQrException("Malformed QR code: invalid escape sequence '%" + hex + "'");
                }
                i += 2;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String buildCanonicalData(String mid, String mwid, String name,
                                             BigDecimal amt, String curr, String ref, String note) {
        return "mid=" + mid +
                "|mwid=" + mwid +
                "|name=" + escapeFieldValue(name != null ? name : "") +
                "|amt=" + (amt != null ? amt.toPlainString() : "") +
                "|curr=" + (curr != null ? curr : DEFAULT_CURRENCY) +
                "|ref=" + ref +
                "|note=" + escapeFieldValue(note != null ? note : "");
    }

    private static String calculateChecksum(String data) {
        CRC32 crc = new CRC32();
        crc.update(data.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue()).toUpperCase(Locale.ROOT);
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
