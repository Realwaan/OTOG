# One Team One Goal - Capstone

## E-Wallet System

An E-wallet system that provides a secure, efficient, and user-friendly digital financial platform for managing money transactions seamlessly. Built with exceptional quality, reliability, and modern software engineering practices, this application demonstrates practical applications of object-oriented design and Java GUI development.

### 📌 Compliance

In compliance with the subject CSIT227 - Object Oriented Programming.

### 👥 Project Team

- Marc Andrei Regulacion
- Kyle Nino Duran
- Matthew Dela Cruz
- Al Francis Egtic
- Jeremy Navarro

---

## 🏛️ Domain Architecture & Persistence (CAP-02)

The core domain model, persistence layer, and transaction correctness rules are fully implemented and verified under SQLite with strict ACID transaction guarantees.

### 1. Domain Entities (`com.otog.model`)
- **`User`**: Core user entity supporting roles (`USER`, `MERCHANT`, `ADMIN`), status (`ACTIVE`, `SUSPENDED`, `LOCKED`), and unique mobile numbers.
- **`Wallet`**: Encapsulates user funds, balance modifications (`debit`, `credit`), active status checks, and currency definitions.
- **`PaymentMethod`**: Represents linked payment instruments (bank accounts, e-wallets, cards) owned by users with default selection support.
- **`Transaction`**: Full audit record of financial operations (`CASH_IN`, `CASH_OUT`, `SEND_MONEY`, `BILL_PAYMENT`, `LOAN_DISBURSEMENT`, `INVESTMENT_BUY`, etc.) with unique idempotency reference numbers, fees, and receipt generation.
  - Specialized transaction models: `SendMoneyTransaction`, `CashInOutTransaction`, `InvestMoneyTransaction`, `LoanTransaction`.
- **`LedgerEntry`**: Double-entry accounting ledger entries (`DEBIT`, `CREDIT`) recording balance before, balance after, amount, and timestamp for complete financial reconciliation.

### 2. Financial Correctness & Currency Rules (`com.otog.util.MoneyUtil`)
- **Strict `BigDecimal` Math**: Eliminates IEEE-754 floating-point inaccuracies.
- **Explicit Precision**: 2 decimal places (`scale = 2`) using `RoundingMode.HALF_EVEN` (Banker's rounding).
- **Positive Amount Enforcement**: Strictly rejects null, zero, negative, or fractional cent amounts (> 2 decimal places).
- **Currency**: Standard ISO-4217 PHP (Philippine Peso).

### 3. ACID Transactions & Concurrency Safety (`com.otog.service.WalletService`)
- **Atomic Commits**: Balances, transactions, and ledger entries commit or roll back together as a single unit of work.
- **Insufficient Funds Rejection**: Debits exceeding available balance throw `InsufficientFundsException` without altering database state.
- **Duplicate Request Rejection**: Idempotency is enforced at both service and database levels (`UNIQUE(reference_number)`), throwing `DuplicateTransactionException`.
- **Concurrency & Competing Spends**: Deadlock-free ordered wallet locking prevents race conditions and double-spending across concurrent threads.
- **Restart Persistence**: State persists across complete application shutdowns using SQLite with WAL mode.

---

## 🚀 Building and Running Tests

### Prerequisites
- JDK 19+ (tested on OpenJDK 25 LTS)

### Windows Command Line
```cmd
# Compile all source and test classes
build.bat

# Execute the JUnit 5 test suite
test.bat

# Launch the User Login GUI
run.bat
```

### PowerShell / Unix
```powershell
javac -cp "lib/*;out/production/OTOG" -d out/production/OTOG (Get-ChildItem -Recurse src/*.java, test/*.java | ForEach-Object { $_.FullName })
java -jar lib/junit-platform-console-standalone-1.10.2.jar execute -cp "out/production/OTOG;lib/sqlite-jdbc-3.47.0.0.jar;lib/forms_rt.jar" --scan-classpath
```
