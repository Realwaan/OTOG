package com.otog.repository;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.service.InvestmentService;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Repository for persisted simulated investment products and per-wallet holdings in SQLite.
 * Holdings are keyed by immutable {@code product_code} referencing {@code investment_products(product_code)}.
 */
public class InvestmentRepository {

    public record HoldingRecord(BigDecimal units, BigDecimal totalInvested) {
    }

    private final DatabaseManager databaseManager;

    public InvestmentRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public InvestmentService.SimulatedProduct findSeededProduct() {
        try (Connection conn = databaseManager.getConnection()) {
            return findSeededProduct(conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to load seeded investment product: " + e.getMessage(), e);
        }
    }

    public InvestmentService.SimulatedProduct findSeededProduct(Connection conn) throws SQLException {
        OtogConfig config = OtogConfig.getInstance();
        String configuredCode = config.getInvestmentProductCode();

        Optional<ProductRow> dbRow = queryProductByCode(configuredCode, conn);
        if (dbRow.isEmpty()) {
            dbRow = queryFirstProduct(conn);
        }

        String productCode = dbRow.map(ProductRow::productCode).orElse(configuredCode);
        String productName = dbRow.map(ProductRow::productName).orElse(config.getInvestmentProductName());
        BigDecimal unitPrice = dbRow.map(ProductRow::simulatedUnitPrice).orElse(config.getInvestmentSimulatedUnitPrice());
        String currency = dbRow.map(ProductRow::currency).orElse(config.getDefaultCurrency());

        // Honor explicit runtime/test overrides on top of the persisted product row while keeping immutable product_code
        if (config.hasOverrideOrSysProp("investment.product.name")) {
            productName = config.getInvestmentProductName();
        }
        if (config.hasOverrideOrSysProp("investment.simulated.unit.price")) {
            unitPrice = config.getInvestmentSimulatedUnitPrice();
        }
        if (config.hasOverrideOrSysProp("currency.default")) {
            currency = config.getDefaultCurrency();
        }

        BigDecimal scaledPrice = unitPrice.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        String validCurrency = MoneyUtil.validateCurrency(currency);
        return new InvestmentService.SimulatedProduct(
                productCode,
                productName,
                scaledPrice,
                validCurrency,
                "SIMULATED FIXED PRICE - " + MoneyUtil.format(scaledPrice, validCurrency)
                        + " / unit (Live prices, forecasting, and multiple products deferred)"
        );
    }

    public HoldingRecord findHolding(String walletId, String productCode) {
        try (Connection conn = databaseManager.getConnection()) {
            return findHolding(walletId, productCode, conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to load investment holdings for wallet " + walletId, e);
        }
    }

    public HoldingRecord findHolding(String walletId, String productCode, Connection conn) throws SQLException {
        String sql = "SELECT units, total_invested FROM investment_holdings WHERE wallet_id = ? AND product_code = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, productCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal units = new BigDecimal(rs.getString("units"))
                            .setScale(InvestmentService.UNIT_SCALE, RoundingMode.HALF_UP);
                    BigDecimal totalInvested = new BigDecimal(rs.getString("total_invested"))
                            .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
                    return new HoldingRecord(units, totalInvested);
                }
            }
        }
        return new HoldingRecord(
                BigDecimal.ZERO.setScale(InvestmentService.UNIT_SCALE, RoundingMode.HALF_UP),
                MoneyUtil.ZERO
        );
    }

    public void upsertHolding(String walletId, String productCode, BigDecimal newUnits,
                              BigDecimal newTotalInvested, Connection conn) throws SQLException {
        String sql = "INSERT INTO investment_holdings (wallet_id, product_code, units, total_invested, updated_at) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(wallet_id, product_code) DO UPDATE SET " +
                "units = excluded.units, total_invested = excluded.total_invested, updated_at = excluded.updated_at";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, productCode);
            ps.setString(3, newUnits.toPlainString());
            ps.setString(4, newTotalInvested.toPlainString());
            ps.setString(5, LocalDateTime.now().toString());
            ps.executeUpdate();
        }
    }

    private Optional<ProductRow> queryProductByCode(String productCode, Connection conn) throws SQLException {
        String sql = "SELECT product_code, product_name, simulated_unit_price, currency " +
                "FROM investment_products WHERE product_code = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapProductRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<ProductRow> queryFirstProduct(Connection conn) throws SQLException {
        String sql = "SELECT product_code, product_name, simulated_unit_price, currency " +
                "FROM investment_products ORDER BY product_code ASC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(mapProductRow(rs));
            }
        }
        return Optional.empty();
    }

    private static ProductRow mapProductRow(ResultSet rs) throws SQLException {
        return new ProductRow(
                rs.getString("product_code"),
                rs.getString("product_name"),
                new BigDecimal(rs.getString("simulated_unit_price")),
                rs.getString("currency")
        );
    }

    private record ProductRow(String productCode, String productName,
                              BigDecimal simulatedUnitPrice, String currency) {
    }
}
