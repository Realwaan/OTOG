package com.otog.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Audit record of administrative actions (suspensions, reactivations, role changes, system reviews).
 * Secrets (passwords, PINs, auth tokens, salts, CVVs) are strictly excluded from details and reasons.
 */
public class AdminAction {
    private String actionId;
    private String adminUserId;
    private String actionType;
    private String targetUserId;
    private String reason;
    private String details;
    private LocalDateTime createdAt;

    public AdminAction() {
        this.createdAt = LocalDateTime.now();
    }

    public AdminAction(String actionId, String adminUserId, String actionType,
                       String targetUserId, String reason, String details) {
        this.actionId = actionId;
        this.adminUserId = adminUserId;
        this.actionType = actionType;
        this.targetUserId = targetUserId;
        this.reason = sanitizeWithoutSecrets(reason);
        this.details = sanitizeWithoutSecrets(details);
        this.createdAt = LocalDateTime.now();
    }

    public AdminAction(String actionId, String adminUserId, String actionType,
                       String targetUserId, String reason, String details, LocalDateTime createdAt) {
        this.actionId = actionId;
        this.adminUserId = adminUserId;
        this.actionType = actionType;
        this.targetUserId = targetUserId;
        this.reason = sanitizeWithoutSecrets(reason);
        this.details = sanitizeWithoutSecrets(details);
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    /**
     * Sanitizes strings to prevent inadvertent leakage of credentials, PINs, salts, or tokens into admin logs.
     */
    public static String sanitizeWithoutSecrets(String text) {
        if (text == null) {
            return null;
        }
        String sanitized = text.replaceAll("(?i)(password|passwd|pin|salt|hash|secret|token|otp|cvv)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
        sanitized = sanitized.replaceAll("(?i)\\b(pin|otp|code)\\b\\s*:\\s*\\d+", "$1:[REDACTED]");
        return sanitized;
    }

    public String getActionId() {
        return actionId;
    }

    public void setActionId(String actionId) {
        this.actionId = actionId;
    }

    public String getAdminUserId() {
        return adminUserId;
    }

    public void setAdminUserId(String adminUserId) {
        this.adminUserId = adminUserId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(String targetUserId) {
        this.targetUserId = targetUserId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = sanitizeWithoutSecrets(reason);
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = sanitizeWithoutSecrets(details);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AdminAction that)) return false;
        return Objects.equals(actionId, that.actionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(actionId);
    }

    @Override
    public String toString() {
        return "AdminAction{" +
                "actionId='" + actionId + '\'' +
                ", adminUserId='" + adminUserId + '\'' +
                ", actionType='" + actionType + '\'' +
                ", targetUserId='" + targetUserId + '\'' +
                ", reason='" + reason + '\'' +
                ", details='" + details + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
