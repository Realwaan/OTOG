package com.otog.service;

import com.otog.model.InvestMoneyTransaction;
import com.otog.model.Transaction;
import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.util.List;

/**
 * Shared service contract for simulated investment subscriptions, redemptions, holdings, and previews.
 */
public interface InvestmentService {

    int UNIT_SCALE = 4;

    InvestMoneyTransaction invest(String referenceNumber, String walletId, String product,
                                  BigDecimal amount, BigDecimal units);

    Transaction withdrawInvestment(String referenceNumber, String walletId, String investmentTransactionId);

    BigDecimal calculateReturns(String investmentTransactionId);

    List<InvestMoneyTransaction> getInvestmentsByWalletId(String walletId);

    SimulatedProduct getSeededProduct();

    InvestmentHolding getHolding(String walletId);

    InvestmentPreview previewBuy(String walletId, BigDecimal units, BigDecimal amount);

    InvestmentPreview previewRedeem(String walletId, BigDecimal units);

    InvestMoneyTransaction buyInvestment(String referenceNumber, String walletId,
                                         BigDecimal units, BigDecimal amount);

    InvestMoneyTransaction buyInvestmentWithPin(String referenceNumber, String walletId,
                                                BigDecimal units, BigDecimal amount, String rawPin);

    InvestMoneyTransaction redeemInvestment(String referenceNumber, String walletId, BigDecimal units);

    InvestMoneyTransaction redeemInvestmentWithPin(String referenceNumber, String walletId,
                                                   BigDecimal units, String rawPin);

    /**
     * Immutable descriptor of the single seeded simulated investment product.
     */
    final class SimulatedProduct {
        private final String productCode;
        private final String productName;
        private final BigDecimal simulatedUnitPrice;
        private final String currency;
        private final String simulationDisclaimer;

        public SimulatedProduct(String productCode, String productName, BigDecimal simulatedUnitPrice,
                                String currency, String simulationDisclaimer) {
            this.productCode = productCode;
            this.productName = productName;
            this.simulatedUnitPrice = simulatedUnitPrice;
            this.currency = currency;
            this.simulationDisclaimer = simulationDisclaimer;
        }

        public String getProductCode() {
            return productCode;
        }

        public String getProductName() {
            return productName;
        }

        public BigDecimal getSimulatedUnitPrice() {
            return simulatedUnitPrice;
        }

        public String getCurrency() {
            return currency;
        }

        public String getSimulationDisclaimer() {
            return simulationDisclaimer;
        }
    }

    /**
     * Immutable snapshot of a wallet's persisted holding in the simulated investment product.
     */
    final class InvestmentHolding {
        private final String walletId;
        private final String product;
        private final BigDecimal units;
        private final BigDecimal simulatedUnitPrice;
        private final BigDecimal simulatedMarketValue;
        private final BigDecimal totalInvested;
        private final String currency;

        public InvestmentHolding(String walletId, String product, BigDecimal units,
                                 BigDecimal simulatedUnitPrice, BigDecimal simulatedMarketValue,
                                 BigDecimal totalInvested, String currency) {
            this.walletId = walletId;
            this.product = product;
            this.units = units;
            this.simulatedUnitPrice = simulatedUnitPrice;
            this.simulatedMarketValue = simulatedMarketValue;
            this.totalInvested = totalInvested;
            this.currency = currency;
        }

        public String getWalletId() {
            return walletId;
        }

        public String getProduct() {
            return product;
        }

        public BigDecimal getUnits() {
            return units;
        }

        public BigDecimal getSimulatedUnitPrice() {
            return simulatedUnitPrice;
        }

        public BigDecimal getSimulatedMarketValue() {
            return simulatedMarketValue;
        }

        public BigDecimal getTotalInvested() {
            return totalInvested;
        }

        public String getCurrency() {
            return currency;
        }
    }

    /**
     * Immutable preview summary of an investment purchase or redemption before execution.
     */
    final class InvestmentPreview {
        private final String operation;
        private final String walletId;
        private final String product;
        private final BigDecimal units;
        private final BigDecimal simulatedUnitPrice;
        private final BigDecimal amount;
        private final BigDecimal fee;
        private final BigDecimal totalAmount;
        private final BigDecimal currentHoldingUnits;
        private final BigDecimal projectedHoldingUnits;
        private final String currency;
        private final String referenceNumber;

        public InvestmentPreview(String operation, String walletId, String product,
                                 BigDecimal units, BigDecimal simulatedUnitPrice,
                                 BigDecimal amount, BigDecimal fee, BigDecimal totalAmount,
                                 BigDecimal currentHoldingUnits, BigDecimal projectedHoldingUnits,
                                 String currency, String referenceNumber) {
            this.operation = operation;
            this.walletId = walletId;
            this.product = product;
            this.units = units;
            this.simulatedUnitPrice = simulatedUnitPrice;
            this.amount = amount;
            this.fee = fee;
            this.totalAmount = totalAmount;
            this.currentHoldingUnits = currentHoldingUnits;
            this.projectedHoldingUnits = projectedHoldingUnits;
            this.currency = currency;
            this.referenceNumber = referenceNumber;
        }

        public String getOperation() {
            return operation;
        }

        public String getWalletId() {
            return walletId;
        }

        public String getProduct() {
            return product;
        }

        public BigDecimal getUnits() {
            return units;
        }

        public BigDecimal getSimulatedUnitPrice() {
            return simulatedUnitPrice;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public BigDecimal getFee() {
            return fee;
        }

        public BigDecimal getTotalAmount() {
            return totalAmount;
        }

        public BigDecimal getCurrentHoldingUnits() {
            return currentHoldingUnits;
        }

        public BigDecimal getProjectedHoldingUnits() {
            return projectedHoldingUnits;
        }

        public String getCurrency() {
            return currency;
        }

        public String getReferenceNumber() {
            return referenceNumber;
        }

        public String toConfirmationText() {
            boolean isBuy = "BUY".equalsIgnoreCase(operation);
            return new StringBuilder()
                    .append("Operation: ").append(isBuy ? "BUY (Purchase Units)" : "REDEEM (Sell Units)").append("\n")
                    .append("Product: ").append(product).append("\n")
                    .append("Simulated Unit Price: ").append(MoneyUtil.format(simulatedUnitPrice, currency))
                    .append(" [SIMULATED]\n")
                    .append("Units: ").append(units.toPlainString()).append("\n")
                    .append(isBuy ? "Total Cost: " : "Proceeds Credit: ")
                    .append(MoneyUtil.format(totalAmount, currency)).append("\n")
                    .append("Current Holdings: ").append(currentHoldingUnits.toPlainString()).append(" units\n")
                    .append("Projected Holdings: ").append(projectedHoldingUnits.toPlainString()).append(" units\n")
                    .append("Reference: ").append(referenceNumber)
                    .toString();
        }
    }
}
