package com.otog.service;

import com.otog.model.InvestMoneyTransaction;
import com.otog.model.Notification;
import com.otog.model.NotificationType;
import com.otog.model.Transaction;
import com.otog.model.User;

import java.util.List;

/**
 * Service contract for post-commit persistent user notifications, read/unread inbox management,
 * and idempotent FIFO retry dispatch isolated from payment execution.
 */
public interface NotificationService {

    /**
     * Persists a notification strictly after a committed financial transaction.
     * On transient persistence failure, enqueues the notification for retry without throwing,
     * ensuring committed payments are never rolled back or repeated.
     *
     * @return {@code true} if persisted immediately, {@code false} if queued for retry
     */
    boolean dispatchAfterCommit(Notification notification);

    /**
     * Retries queued post-commit notifications in FIFO order.
     * Retries only notification persistence and never triggers payment execution.
     *
     * @return count of notifications persisted during this retry pass
     */
    int retryPendingNotifications();

    int getPendingRetryCount();

    List<Notification> getNotificationsForUser(String userId, boolean unreadOnly);

    int getUnreadCount(String userId);

    void markNotificationRead(String userId, String notificationId);

    void markNotificationUnread(String userId, String notificationId);

    int markAllNotificationsRead(String userId);

    void notifyTransferCommitted(Transaction txn, User sender, User recipient);

    void notifyCashOperationCommitted(Transaction txn, String ownerUserId, String channel);

    void notifyInvestmentCommitted(InvestMoneyTransaction txn, String ownerUserId);

    void notifyTransactionEvent(Transaction txn, String ownerUserId, NotificationType type,
                                String title, String message);
}
