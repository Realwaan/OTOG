package com.otog.model;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Objects;

/**
 * Persistent user inbox notification generated strictly after a committed financial transaction.
 * Supports read/unread tracking, deterministic idempotency via {@code dedupKey}, and newest-first ordering.
 */
public class Notification implements Comparable<Notification> {

    private static final Comparator<Notification> NEWEST_FIRST = Comparator
            .comparing(Notification::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Notification::getNotificationId, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Notification::getDedupKey, Comparator.nullsLast(Comparator.naturalOrder()));

    private String notificationId;
    private String userId;
    private String transactionId;
    private String referenceNumber;
    private NotificationType type;
    private String title;
    private String message;
    private boolean read;
    private String dedupKey;
    private LocalDateTime createdAt;
    private LocalDateTime readAt;

    public Notification() {
        this.type = NotificationType.PAYMENT_UPDATE;
        this.read = false;
        this.createdAt = LocalDateTime.now();
    }

    public Notification(String notificationId,
                        String userId,
                        String transactionId,
                        String referenceNumber,
                        NotificationType type,
                        String title,
                        String message,
                        String dedupKey) {
        this();
        this.notificationId = notificationId;
        this.userId = userId;
        this.transactionId = transactionId;
        this.referenceNumber = referenceNumber;
        this.type = type != null ? type : NotificationType.PAYMENT_UPDATE;
        this.title = title;
        this.message = message;
        this.dedupKey = dedupKey;
    }

    public String getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(String notificationId) {
        this.notificationId = notificationId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public NotificationType getType() {
        return type;
    }

    public void setType(NotificationType type) {
        this.type = type != null ? type : NotificationType.PAYMENT_UPDATE;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
        if (read && this.readAt == null) {
            this.readAt = LocalDateTime.now();
        } else if (!read) {
            this.readAt = null;
        }
    }

    public void markRead() {
        this.read = true;
        if (this.readAt == null) {
            this.readAt = LocalDateTime.now();
        }
    }

    public void markUnread() {
        this.read = false;
        this.readAt = null;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    @Override
    public int compareTo(Notification other) {
        if (other == null) {
            return -1;
        }
        return NEWEST_FIRST.compare(this, other);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Notification that)) return false;
        return Objects.equals(notificationId, that.notificationId)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(dedupKey, that.dedupKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(notificationId, createdAt, dedupKey);
    }

    @Override
    public String toString() {
        return "Notification{" +
                "notificationId='" + notificationId + '\'' +
                ", userId='" + userId + '\'' +
                ", type=" + type +
                ", read=" + read +
                ", referenceNumber='" + referenceNumber + '\'' +
                '}';
    }
}
