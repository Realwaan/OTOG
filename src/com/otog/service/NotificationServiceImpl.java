package com.otog.service;

import com.otog.database.DatabaseManager;
import com.otog.exception.EntityNotFoundException;
import com.otog.model.Notification;
import com.otog.model.NotificationType;
import com.otog.model.ReceiptBuilder;
import com.otog.model.Transaction;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.repository.NotificationRepository;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

/**
 * Default {@link NotificationService} implementation backed by {@link NotificationRepository}
 * and a thread-safe FIFO {@link ArrayDeque} retry queue.
 * Structurally decoupled from payment services so notification retries can never repeat payments.
 */
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final Queue<Notification> retryQueue = new ArrayDeque<>();
    private final Object retryLock = new Object();

    public NotificationServiceImpl(DatabaseManager databaseManager) {
        this(new NotificationRepository(Objects.requireNonNull(databaseManager, "DatabaseManager cannot be null")));
    }

    public NotificationServiceImpl(NotificationRepository notificationRepository) {
        this.notificationRepository = Objects.requireNonNull(
                notificationRepository, "NotificationRepository cannot be null");
    }

    public NotificationRepository getNotificationRepository() {
        return notificationRepository;
    }

    @Override
    public boolean dispatchAfterCommit(Notification notification) {
        if (notification == null) {
            throw new IllegalArgumentException("Notification cannot be null");
        }
        if (notification.getUserId() == null || notification.getUserId().trim().isEmpty()) {
            throw new IllegalArgumentException("Notification owner user ID cannot be null or empty");
        }
        if (notification.getNotificationId() == null || notification.getNotificationId().trim().isEmpty()) {
            notification.setNotificationId(IdGenerator.nextNotificationId());
        }
        try {
            notificationRepository.save(notification);
            return true;
        } catch (RuntimeException ex) {
            System.err.println("[WARN] Notification persistence failed after commit for ref "
                    + notification.getReferenceNumber() + "; queued for idempotent retry: " + ex.getMessage());
            synchronized (retryLock) {
                retryQueue.offer(notification);
            }
            return false;
        }
    }

    @Override
    public int retryPendingNotifications() {
        int persistedCount = 0;
        int batchSize;
        synchronized (retryLock) {
            batchSize = retryQueue.size();
        }
        for (int i = 0; i < batchSize; i++) {
            Notification next;
            synchronized (retryLock) {
                next = retryQueue.poll();
            }
            if (next == null) {
                break;
            }
            try {
                notificationRepository.save(next);
                persistedCount++;
            } catch (RuntimeException ex) {
                System.err.println("[WARN] Notification retry still failing for ref "
                        + next.getReferenceNumber() + "; re-queued: " + ex.getMessage());
                synchronized (retryLock) {
                    retryQueue.offer(next);
                }
            }
        }
        return persistedCount;
    }

    @Override
    public int getPendingRetryCount() {
        synchronized (retryLock) {
            return retryQueue.size();
        }
    }

    @Override
    public List<Notification> getNotificationsForUser(String userId, boolean unreadOnly) {
        String cleanUserId = requireUserId(userId);
        return Collections.unmodifiableList(notificationRepository.findByUserId(cleanUserId, unreadOnly));
    }

    @Override
    public int getUnreadCount(String userId) {
        String cleanUserId = requireUserId(userId);
        return notificationRepository.countUnreadByUserId(cleanUserId);
    }

    @Override
    public void markNotificationRead(String userId, String notificationId) {
        String cleanUserId = requireUserId(userId);
        Notification notification = requireOwnedNotification(cleanUserId, notificationId);
        notificationRepository.markAsRead(notification.getNotificationId(), cleanUserId);
    }

    @Override
    public void markNotificationUnread(String userId, String notificationId) {
        String cleanUserId = requireUserId(userId);
        Notification notification = requireOwnedNotification(cleanUserId, notificationId);
        notificationRepository.markAsUnread(notification.getNotificationId(), cleanUserId);
    }

    @Override
    public int markAllNotificationsRead(String userId) {
        String cleanUserId = requireUserId(userId);
        return notificationRepository.markAllAsRead(cleanUserId);
    }

    @Override
    public void notifyTransferCommitted(Transaction txn, User sender, User recipient) {
        if (txn == null) {
            return;
        }
        String amountText = MoneyUtil.format(txn.getAmount(), txn.getCurrency());
        String feeText = MoneyUtil.format(txn.getFee() != null ? txn.getFee() : BigDecimal.ZERO, txn.getCurrency());
        String ref = txn.getReferenceNumber() != null ? txn.getReferenceNumber() : txn.getTransactionId();

        if (sender != null && sender.getUserId() != null) {
            String recipientDisplay = formatMaskedCounterparty(recipient, txn.getTargetWalletId());
            String senderMsg = "You sent " + amountText
                    + (txn.getFee() != null && txn.getFee().compareTo(BigDecimal.ZERO) > 0
                    ? " (Fee: " + feeText + ")"
                    : "")
                    + " to " + recipientDisplay + ". Ref: " + ref;
            notifyTransactionEvent(txn, sender.getUserId(), NotificationType.TRANSFER_SENT,
                    "Money Sent", senderMsg);
        }

        if (recipient != null && recipient.getUserId() != null) {
            String senderDisplay = formatMaskedCounterparty(sender, txn.getSourceWalletId());
            String recipientMsg = "You received " + amountText
                    + " from " + senderDisplay + ". Ref: " + ref;
            notifyTransactionEvent(txn, recipient.getUserId(), NotificationType.TRANSFER_RECEIVED,
                    "Money Received", recipientMsg);
        }
    }

    @Override
    public void notifyCashOperationCommitted(Transaction txn, String ownerUserId, String channel) {
        if (txn == null || ownerUserId == null || ownerUserId.trim().isEmpty()) {
            return;
        }
        boolean isCashIn = txn.getType() == TransactionType.CASH_IN;
        String opLabel = isCashIn ? "Cash In" : "Cash Out";
        String channelLabel = (channel != null && !channel.trim().isEmpty()) ? channel.trim() : "Bank";
        String amountText = MoneyUtil.format(txn.getAmount(), txn.getCurrency());
        String ref = txn.getReferenceNumber() != null ? txn.getReferenceNumber() : txn.getTransactionId();

        NotificationType notifType;
        String title;
        String message;
        TransactionStatus status = txn.getStatus();

        if (status == TransactionStatus.COMPLETED) {
            notifType = isCashIn ? NotificationType.CASH_IN_COMPLETED : NotificationType.CASH_OUT_COMPLETED;
            title = opLabel + " Completed";
            message = isCashIn
                    ? "Cash In of " + amountText + " via " + channelLabel + " completed. Ref: " + ref
                    : "Cash Out of " + amountText + " to " + channelLabel + " completed. Ref: " + ref;
        } else if (status == TransactionStatus.FAILED) {
            notifType = isCashIn ? NotificationType.CASH_IN_FAILED : NotificationType.CASH_OUT_FAILED;
            title = opLabel + " Failed";
            message = isCashIn
                    ? "Cash In of " + amountText + " via " + channelLabel + " failed. No funds were credited. Ref: " + ref
                    : "Cash Out of " + amountText + " to " + channelLabel + " failed. Reserved funds were released. Ref: " + ref;
        } else {
            notifType = isCashIn ? NotificationType.CASH_IN_PENDING : NotificationType.CASH_OUT_PENDING;
            title = opLabel + " Pending";
            message = isCashIn
                    ? "Cash In of " + amountText + " via " + channelLabel + " is pending bank confirmation. Ref: " + ref
                    : "Cash Out of " + amountText + " to " + channelLabel + " is pending settlement (funds reserved). Ref: " + ref;
        }

        notifyTransactionEvent(txn, ownerUserId, notifType, title, message);
    }

    @Override
    public void notifyTransactionEvent(Transaction txn, String ownerUserId, NotificationType type,
                                       String title, String message) {
        if (txn == null || ownerUserId == null || ownerUserId.trim().isEmpty()) {
            return;
        }
        String cleanOwner = ownerUserId.trim();
        NotificationType effectiveType = type != null ? type : NotificationType.PAYMENT_UPDATE;
        String txnKey = txn.getTransactionId() != null ? txn.getTransactionId() : txn.getReferenceNumber();
        String dedupKey = txnKey + ":" + cleanOwner + ":" + effectiveType.name();

        Notification notification = new Notification(
                IdGenerator.nextNotificationId(),
                cleanOwner,
                txn.getTransactionId(),
                txn.getReferenceNumber(),
                effectiveType,
                title != null ? title : "Payment Update",
                message != null ? message : "Transaction " + txn.getReferenceNumber() + " updated.",
                dedupKey
        );
        dispatchAfterCommit(notification);
    }

    private Notification requireOwnedNotification(String ownerUserId, String notificationId) {
        if (notificationId == null || notificationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Notification ID must not be null or empty");
        }
        Notification existing = notificationRepository.findById(notificationId.trim())
                .orElseThrow(() -> new EntityNotFoundException("Notification", notificationId));
        if (!ownerUserId.equals(existing.getUserId())) {
            throw new SecurityException("Access denied: Cannot modify another user's notification.");
        }
        return existing;
    }

    private static String requireUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("User ID must not be null or empty");
        }
        return userId.trim();
    }

    public static String formatMaskedCounterparty(User counterparty, String fallbackWalletId) {
        if (counterparty != null) {
            String name = (counterparty.getName() != null && !counterparty.getName().trim().isEmpty())
                    ? counterparty.getName().trim()
                    : "OTOG User";
            String maskedMobile = counterparty.getMobileNumber() != null
                    ? ReceiptBuilder.maskIdentifier(counterparty.getMobileNumber())
                    : ReceiptBuilder.maskIdentifier(fallbackWalletId);
            return name + " (" + maskedMobile + ")";
        }
        return "Wallet " + ReceiptBuilder.maskIdentifier(fallbackWalletId);
    }
}
