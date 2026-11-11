package com.otog.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Functional interface for database operations executed within an ACID transaction boundary.
 */
@FunctionalInterface
public interface TransactionCallback<T> {
    T execute(Connection connection) throws Exception;
}
