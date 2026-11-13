# One Team One Goal - Capstone

## E-Wallet System

An E-wallet system that provides a secure, efficient, and user-friendly digital financial platform for managing money transactions seamlessly. Built with exceptional quality, reliability, and modern software engineering practices, this application demonstrates practical applications of object-oriented design and Java GUI development.

### 📌 Compliance

In compliance with the subject CSIT227 - Object Oriented Programming.

### 👥 Project Team & Ownership (CAP-01)

| Member | Proposed Role | Primary Ownership Scope |
| :--- | :---: | :--- |
| **Marc Andrei Regulacion** | Role C (Baseline & Domain Owner) | Reproducible build/test baseline (`build.bat`, `run.bat`, `test.bat`), SQLite `DatabaseManager`, core domain entities, `WalletService` |
| **Jeremy Navarro** | Role A (Auth & Login UI) | `UserLogin` Swing UI, password & 2FA workflows, `AuthService` implementation |
| **Kyle Nino Duran** | Role B (Core Transactions UI) | Dashboard shell (`Main`), Cash-In / Cash-Out & Send Money UI integration |
| **Matthew Dela Cruz** | Role D (Loans & Investments) | Simulated `LoanService` & `InvestmentService` implementations and Swing panels |
| **Al Francis Egtic** | Role E (Security, QA & Docs) | PIN / Security verification, end-to-end integration QA, class diagram & documentation |

> **Note:** Role letters (`A`–`E`) are proposed module ownership roles agreed for CAP-01 kickoff; contributors may pair across modules prior to feature freeze.

---

## 📋 CAP-01 Project Scope, Inventory & Technical Baseline

### 1. Code & Feature Inventory (as of CAP-01)

| Feature / Module | Class Diagram / Ticket Scope | Status | Current Files / Notes |
| :--- | :--- | :---: | :--- |
| **Build, Run & Test Baseline** | Reproducible CLI + IDE build | **Implemented** | `build.bat`, `run.bat`, `test.bat`, `.idea/OTOG.iml`, `lib/` |
| **Login & 2FA Prototype** | `UserLogin` Swing form (`Password` + `2FA` panels) | **Partial** | `src/UserLogin.java`, `src/UserLogin.form` (form init fixed via `$$$setupUI$$$()`; still uses demo `"admin"`/`"password"` credentials until `AuthService` is wired) |
| **Database & ACID Transactions** | SQLite schema, WAL/busy timeout, rollback | **Implemented** | `src/com/otog/database/DatabaseManager.java`, `TransactionCallback.java` |
| **User, Wallet & PaymentMethod Domain** | `User`, `Wallet`, `PaymentMethod`, `LedgerEntry` | **Implemented** | `src/com/otog/model/`, `src/com/otog/repository/` |
| **Core Wallet Operations** | `createWallet`, `transfer`, `cashIn`, `cashOut` | **Implemented** | `src/com/otog/service/WalletOperations.java`, `WalletService.java`, `MoneyUtil.java` |
| **Shared Service Contracts** | `WalletOperations`, `AuthService`, `LoanService`, `InvestmentService` | **Implemented** | `src/com/otog/service/` (interfaces agreed for parallel module work) |
| **Automated Domain & Baseline Tests** | JUnit 5 verification suite | **Implemented** | `test/com/otog/*.java` |
| **Security Domain (`Password`, `Pin`, `TwoFactorAuth`)** | Persisted credential hashes, OTP expiry, PIN lockouts | **Missing** | Defined in `Class Diagram/ewallet_cd.png` and `AuthService.java`; implementation pending |
| **Main Application Navigation Shell (`Main`)** | Dashboard switching CashInOut, SendMoney, Invest, Loan, Security | **Missing** | Defined in `Class Diagram/ewallet_cd.png`; GUI shell pending |
| **CashInOut & SendMoney GUI Workflows** | Swing forms wired to `WalletOperations` | **Missing** | Domain models (`CashInOutTransaction`, `SendMoneyTransaction`) exist; GUI pending |
| **Simulated Loans (`Loan`)** | Apply, approve, repay with wallet ledger integration | **Partial** | `LoanTransaction.java` & `LoanService.java` defined; service implementation & UI pending |
| **Simulated Investments (`InvestMoney`)** | Invest, withdraw, calculate returns with wallet ledger integration | **Partial** | `InvestMoneyTransaction.java` & `InvestmentService.java` defined; service implementation & UI pending |

### 2. Agreed Technical Baseline

- **JDK**: **JDK 19+** (configured in `.idea/misc.xml` with `project-jdk-name="19"`, tested and verified on **OpenJDK 25.0.3 LTS**).
- **Build / Test / Run Commands**:
  - `build.bat` — compiles `src/` and `test/` with UTF-8 encoding into `out/production/OTOG` and stages `UserLogin.form`.
  - `test.bat` — executes the full JUnit 5 suite (`lib/junit-platform-console-standalone-1.10.2.jar`).
  - `run.bat` — launches `UserLogin` Swing GUI (`run.bat --verify` runs a non-blocking clean-checkout initialization check).
- **Database**: Embedded **SQLite 3** (`lib/sqlite-jdbc-3.47.0.0.jar`), file path `data/otog.db` (`jdbc:sqlite:data/otog.db`) for runtime and `jdbc:sqlite::memory:` for isolated unit tests, managed by `com.otog.database.DatabaseManager`.
- **Package Structure**:
  - `src/` (default package): `UserLogin.java` & `UserLogin.form`
  - `com.otog.database`: SQLite lifecycle & ACID transaction helpers
  - `com.otog.model`: Domain entities & enums (`User`, `Wallet`, `Transaction`, subclasses, `LedgerEntry`, `PaymentMethod`)
  - `com.otog.repository`: JDBC repositories
  - `com.otog.service`: Shared service interfaces (`WalletOperations`, `AuthService`, `LoanService`, `InvestmentService`) & implementations (`WalletService`)
  - `com.otog.exception`: Typed domain exceptions (`OtogException`, `InsufficientFundsException`, `DuplicateTransactionException`, etc.)
  - `com.otog.util`: `MoneyUtil` & `IdGenerator`
  - `test/com/otog`: JUnit 5 test suites
- **IntelliJ Form Initialization**:
  - `.idea/uiDesigner.xml` disables binary `.class` instrumentation (`INSTRUMENT_CLASSES=false`), and `UserLogin.java` includes explicit `$$$setupUI$$$()` component initialization invoked when `pnlMain == null`. This guarantees `UserLogin` launches identically whether compiled via `build.bat` (`javac`) or IntelliJ IDEA.

### 3. Rubric Scope Confirmation & Delivery Schedule

- **Loans & Investments Rubric Decision**: **Confirmed Required.** Both **Loans (`Loan`)** and **Investments (`InvestMoney`)** are part of the CSIT227 OOP rubric and the approved `Class Diagram/ewallet_cd.png` (demonstrating inheritance and polymorphism under `Transaction`). Both features will use **simulated financial integrations** backed by `WalletService` and SQLite ledger entries (no external banking or market APIs).
- **Milestones**:
  - **Feature Freeze**: November 29, 2026
  - **Readiness Deadline**: December 8, 2026
  - **Presentations**: December 9 (F1), December 10 (F2), December 12 (G9), 2026

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

### Clean-Checkout Teammate Verification Procedure
To verify reproducibility from a clean checkout (without pre-built `out/` files or IDE compilation):
```powershell
# 1. Remove any existing build output
Remove-Item -Recurse -Force out -ErrorAction SilentlyContinue

# 2. Build all sources and tests from scratch
.\build.bat

# 3. Run the automated JUnit 5 suite
.\test.bat

# 4. Verify non-blocking login initialization, then launch the GUI
.\run.bat --verify
.\run.bat
```

### Windows Command Line
```cmd
# Compile all source and test classes
build.bat

# Execute the JUnit 5 test suite
test.bat

# Launch the User Login GUI (or pass --verify for initialization check)
run.bat
```

### PowerShell / Unix
```powershell
New-Item -ItemType Directory -Force -Path out/production/OTOG | Out-Null
javac -encoding UTF-8 -cp "lib/*;out/production/OTOG" -d out/production/OTOG (Get-ChildItem -Recurse src/*.java, test/*.java | ForEach-Object { $_.FullName })
java --enable-native-access=ALL-UNNAMED -jar lib/junit-platform-console-standalone-1.10.2.jar execute -cp "out/production/OTOG;lib/sqlite-jdbc-3.47.0.0.jar;lib/forms_rt.jar" --scan-classpath
```
