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

## 📋 Project Scope, Inventory & Technical Baseline

### 1. Code & Feature Inventory

| Feature / Module | Class Diagram / Ticket Scope | Status | Current Files / Notes |
| :--- | :--- | :---: | :--- |
| **Build, Run & Test Baseline** | Reproducible CLI + IDE build | **Implemented** | `build.bat`, `run.bat`, `test.bat`, `.idea/OTOG.iml`, `lib/` |
| **Login & 2FA (`UserLogin`)** | `UserLogin` Swing form (`Password` + `2FA` + `Back to Login`) | **Implemented** | `src/UserLogin.java`, `src/UserLogin.form` — wired to `AuthServiceImpl`, `WalletService`, `SessionManager`, and `RegisterDialog` with `JOptionPane` error/feedback dialogs |
| **Database & ACID Transactions** | SQLite schema, WAL/busy timeout, rollback | **Implemented** | `src/com/otog/database/DatabaseManager.java`, `TransactionCallback.java` |
| **User, Wallet & PaymentMethod Domain** | `User`, `Wallet`, `PaymentMethod`, `LedgerEntry` | **Implemented** | `src/com/otog/model/`, `src/com/otog/repository/` |
| **Core Wallet Operations** | `createWallet`, `transfer`, `cashIn`, `cashOut` | **Implemented** | `src/com/otog/service/WalletOperations.java`, `WalletService.java`, `MoneyUtil.java` |
| **Shared Service Contracts** | `WalletOperations`, `AuthService`, `LoanService`, `InvestmentService`, `AdminService` | **Implemented** | `src/com/otog/service/` |
| **Automated Domain & UI Tests** | JUnit 5 verification suite | **Implemented** | `test/com/otog/*.java` |
| **Registration & Password Authentication (CAP-03)** | Salted password hashes, account registration, wallet auto-provisioning, lockout limits & SessionManager | **Implemented** | `src/com/otog/service/AuthServiceImpl.java`, `SessionManager.java`, `src/UserLogin.java`, `src/com/otog/ui/RegisterDialog.java` |
| **Security Domain (`Pin`, `TwoFactorAuth`, `SessionManager`)** | Account-bound OTP expiry/1-time use/cooldown, hashed Transaction PIN lockouts & 15-min session timeout | **Implemented (CAP-04)** | `src/com/otog/service/OtpService.java`, `AuthServiceImpl.java`, `SessionManager.java`, `WalletService.java`, `test/com/otog/OtpPinSessionTest.java` |
| **Main Application Navigation Shell (`Main`)** | Multi-screen dashboard with balance, recent activity, receipt viewer, PIN settings, async worker states & LIFO back navigation | **Implemented (CAP-05)** | `src/com/otog/ui/Main.java`, `SessionManager.java`, `src/UserLogin.java` (unfinished Loans/Investments tabs hidden unless services are provided) |
| **CashInOut & SendMoney Workflows (CAP-05 / CAP-06)** | Swing forms, `SendMoneyService` preview/confirmation, Factory/Builder models, PIN authorization & `JOptionPane` error handling | **Implemented** | `src/com/otog/ui/Main.java`, `src/com/otog/service/SendMoneyService.java`, `TransactionBuilder.java`, `TransactionFactory.java`, `test/com/otog/SendMoneyTest.java` |
| **Admin Permissions & Account Controls (CAP-09)** | Role authorization, account suspension/reactivation with recorded reasons, suspended account payment blockage, audit logs without secrets & prohibited direct balance editing | **Implemented** | `src/com/otog/service/AdminService.java`, `AdminServiceImpl.java`, `AdminAction.java`, `AdminActionRepository.java`, `WalletService.java`, `src/com/otog/ui/AdminControlsPanel.java`, `src/com/otog/ui/AdminControlsPanel.form`, `test/com/otog/AdminPermissionsAndAccountControlTest.java` |
| **Auth & Dialog Swing `.form` Conversion (CAP-19)** | Synchronized `UserLogin.form`, bound `RegisterDialog.form` & `PinSettingsDialog.form`, and recursive `.form` staging in `build.bat` | **Implemented** | `src/UserLogin.form`, `src/com/otog/ui/RegisterDialog.form`, `src/com/otog/ui/PinSettingsDialog.form`, `build.bat`, `test/com/otog/SwingFormsAndFileHandlingTest.java` |
| **Modular `Main` Dashboard Swing `.form` Panels (CAP-20)** | Top-level `Main.form` + modular bound `.form` panels (`OverviewPanel`, `CashInOutPanel`, `SendMoneyPanel`, `AdminControlsPanel`) | **Implemented** | `src/com/otog/ui/Main.form`, `OverviewPanel.form`, `CashInOutPanel.form`, `SendMoneyPanel.form`, `AdminControlsPanel.form`, `test/com/otog/SwingFormsAndFileHandlingTest.java` |
| **Java File Handling — Store & Load Statements (CAP-21)** | Store (export) and load (import) masked wallet statements and receipts via `BufferedWriter` & `BufferedReader` | **Implemented** | `src/com/otog/service/StatementFileService.java`, `src/com/otog/ui/OverviewPanel.java`, `src/com/otog/ui/Main.java`, `test/com/otog/SwingFormsAndFileHandlingTest.java` |
| **Simulated Loans (`Loan`)** | Apply, approve, repay with wallet ledger integration | **Partial** | `LoanTransaction.java` & `LoanService.java` defined; service implementation & UI pending |
| **Simulated Investments (`InvestMoney`)** | Invest, withdraw, calculate returns with wallet ledger integration | **Partial** | `InvestMoneyTransaction.java` & `InvestmentService.java` defined; service implementation & UI pending |

### 2. Agreed Technical Baseline

- **JDK**: **JDK 19+** (configured in `.idea/misc.xml` with `project-jdk-name="19"`, tested and verified on **OpenJDK 25.0.3 LTS**).
- **Build / Test / Run Commands**:
  - `build.bat` — compiles `src/` and `test/` with UTF-8 encoding into `out/production/OTOG`, and stages `src/**/*.form` plus `config/otog.properties` into matching output directories.
  - `test.bat` — executes the full JUnit 5 suite (`lib/junit-platform-console-standalone-1.10.2.jar`).
  - `run.bat` — launches `UserLogin` Swing GUI (`run.bat --verify` runs a non-blocking clean-checkout initialization check).
- **Externalized Configuration (`config/otog.properties` & `com.otog.util.OtogConfig`)**:
  - Runtime defaults (SQLite path `data/otog.db` & busy timeout, default currency `PHP`, transfer min/max limits and default flat/percentage fees, OTP expiry/cooldown/length/attempt limits, login lockouts, PIN length & attempt limits, session inactivity timeout & UI polling interval, Cash-In/Out channels, seeded demo bank accounts, admin seed parameters, window sizes, row height, date/timestamp patterns, and UI hex theme colors) live in `config/otog.properties` instead of being hardcoded across classes.
  - Any property can be overridden at launch using JVM arguments (`-Dotog.<key>=<value>` or `-Dotog.config.file=<path>`).
- **Database**: Embedded **SQLite 3** (`lib/sqlite-jdbc-3.47.0.0.jar`), default file path configured in `config/otog.properties` (`data/otog.db`) for runtime and `jdbc:sqlite::memory:` for isolated unit tests, managed by `com.otog.database.DatabaseManager`.
- **Package Structure**:
  - `src/` (default package): `UserLogin.java` & `UserLogin.form`
  - `com.otog.ui`: Bound IntelliJ Swing `.form` windows, dialogs & panels (`Main.java`/`Main.form`, `OverviewPanel.java`/`OverviewPanel.form`, `CashInOutPanel.java`/`CashInOutPanel.form`, `SendMoneyPanel.java`/`SendMoneyPanel.form`, `AdminControlsPanel.java`/`AdminControlsPanel.form`, `RegisterDialog.java`/`RegisterDialog.form`, `PinSettingsDialog.java`/`PinSettingsDialog.form`)
  - `com.otog.database`: SQLite lifecycle & ACID transaction helpers (`DatabaseManager`, `TransactionCallback`)
  - `com.otog.model`: Domain entities, enums, factory, builder, prototype, composite fee policies, and composite receipts (`User`, `UserRole`, `UserStatus`, `Wallet`, `WalletStatus`, `Transaction`, `TransactionPrototype`, `SendMoneyTransaction`, `CashInOutTransaction`, `InvestMoneyTransaction`, `LoanTransaction`, `TransactionFactory`, `TransactionBuilder`, `ReceiptBuilder`, `ReceiptComponent`, `ReceiptLine`, `ReceiptSection`, `FeeComponent`, `FlatFee`, `PercentageFee`, `CompositeFeePolicy`, `LedgerEntry`, `PaymentMethod`, `Credential`, `AdminAction`)
  - `com.otog.repository`: JDBC repositories (`UserRepository`, `WalletRepository`, `PaymentMethodRepository`, `TransactionRepository`, `LedgerRepository`, `CredentialRepository`, `AdminActionRepository`)
  - `com.otog.service`: Shared service interfaces, protection proxies, adapters, decorators, file format bridge & implementations (`WalletOperations`, `WalletService`, `ProtectedWalletOperationsProxy`, `AuthService`, `AuthServiceImpl`, `OtpService`, `SessionManager`, `SendMoneyService`, `BankAdapter`, `MockBankAdapter`, `BankAdapterDecorator`, `ValidatingBankAdapterDecorator`, `AuditingBankAdapterDecorator`, `PaymentProviderFactory`, `StatementFileService`, `ExportableDocument`, `WalletStatementDocument`, `TransactionReceiptDocument`, `DocumentFormat`, `PipeTextDocumentFormat`, `CsvDocumentFormat`, `AdminService`, `AdminServiceImpl`, `ProtectedAdminServiceProxy`, `LoanService`, `InvestmentService`)
  - `com.otog.exception`: Typed domain exceptions (`OtogException`, `InsufficientFundsException`, `DuplicateTransactionException`, `EntityNotFoundException`, `InvalidAmountException`, `WalletException`)
  - `com.otog.util`: `OtogConfig`, `UiTheme`, `MoneyUtil`, `IdGenerator`, `PasswordUtil`
  - `test/com/otog`: JUnit 5 test suites (`AdminPermissionsAndAccountControlTest`, `AtomicCommitAndRollbackTest`, `BaselineReproducibilityTest`, `BigDecimalMoneyValidationTest`, `CashInOutMockBankApiTest`, `ConcurrencyDoubleSpendTest`, `DashboardAndNavigationTest`, `DesignPatternsAndConfigTest`, `OtpPinSessionTest`, `RegistrationAndAuthTest`, `RestartPersistenceTest`, `SendMoneyTest`, `SwingFormsAndFileHandlingTest`, `UserWalletPersistenceTest`)
- **IntelliJ Form Initialization**:
  - `.idea/uiDesigner.xml` disables binary `.class` instrumentation (`INSTRUMENT_CLASSES=false`), and every bound Swing `.form` class (`UserLogin`, `RegisterDialog`, `PinSettingsDialog`, `Main`, `OverviewPanel`, `CashInOutPanel`, `SendMoneyPanel`, `AdminControlsPanel`) includes explicit `$$$setupUI$$$()` and `$$$getRootComponent$$$()` methods using `com.intellij.uiDesigner.core.GridLayoutManager` and `GridConstraints` (`lib/forms_rt.jar`). This guarantees all forms render identically in IntelliJ IDEA GUI Designer and when compiled via `build.bat` (`javac`).

### 3. Rubric Scope Confirmation & Delivery Schedule

- **Loans & Investments Rubric Decision**: **Confirmed Required.** Both **Loans (`Loan`)** and **Investments (`InvestMoney`)** are part of the CSIT227 OOP rubric and the approved `Class Diagram/ewallet_cd.png` (demonstrating inheritance and polymorphism under `Transaction`). Both features will use **simulated financial integrations** backed by `WalletService` and SQLite ledger entries (no external banking or market APIs).
- **Milestones**:
  - **Feature Freeze**: November 29, 2026
  - **MS Teams GitHub Repository Submission (`jayvince.serato@cit.edu`)**: December 1, 2026
  - **Readiness Deadline**: December 8, 2026
  - **Presentations (12 minutes max)**: December 9 (F1), December 10 (F2), December 12 (G9), 2026
  - **Written Final Exam**: December 9 (G9), December 12 (F1), December 13 (F2), 2026

---

## 🧩 Design Patterns Applied (CSIT227 Bonus [5%])

In accordance with the **CSIT227 Capstone Requirements**, the OTOG E-Wallet system implements all applicable **Creational** and **Structural** GoF Design Patterns, with their exact project locations and responsibilities detailed below:

### Creational Design Patterns

| Design Pattern | Applied Classes / Files | Area of Application & Purpose |
| :--- | :--- | :--- |
| **Singleton** | `SessionManager` (`src/com/otog/service/SessionManager.java`), `OtogConfig` (`src/com/otog/util/OtogConfig.java`) | Thread-safe double-checked locking Singletons managing: (1) the active authenticated user/wallet session, inactivity timeout, and PIN lockout state (`SessionManager`), and (2) externalized runtime settings loaded from `config/otog.properties` and system property overrides (`OtogConfig`). |
| **Factory Method** | `TransactionFactory` (`src/com/otog/model/TransactionFactory.java`) | Centralizes polymorphic instantiation of `SendMoneyTransaction`, `CashInOutTransaction`, `LoanTransaction`, and `InvestMoneyTransaction`, plus `createEmptyForType(TransactionType)` used by `TransactionRepository` during SQLite row hydration. |
| **Abstract Factory** | `PaymentProviderFactory` (`src/com/otog/service/PaymentProviderFactory.java`) | Produces cohesive provider families — decorated `BankAdapter`, `BankApiClient`, and `FeeComponent` (`createFeePolicy()`) — configured for deterministic modes (`SUCCESS`, `REJECTED`, `FAILED`, `TIMEOUT`, `PENDING`, `UNKNOWN`). |
| **Builder** | `TransactionBuilder` (`src/com/otog/model/TransactionBuilder.java`), `ReceiptBuilder` (`src/com/otog/model/ReceiptBuilder.java`) | `TransactionBuilder` provides fluent step-by-step construction and field validation for `buildSendMoney()`, `buildCashInOut()`, `buildLoan()`, and `buildInvestment()`. `ReceiptBuilder` builds aligned `ReceiptComponent` trees for transaction receipts and Send Money confirmation previews. |
| **Prototype** | `TransactionPrototype` (`src/com/otog/model/TransactionPrototype.java`), overridden in `Transaction`, `SendMoneyTransaction`, `CashInOutTransaction`, `LoanTransaction`, `InvestMoneyTransaction` | Polymorphically clones any completed or historical transaction via `copyAsDraft(newReferenceNumber)` into a fresh `PENDING` draft with a new transaction ID and cleared settlement metadata, without mutating the original prototype record. |

### Structural Design Patterns

| Design Pattern | Applied Classes / Files | Area of Application & Purpose |
| :--- | :--- | :--- |
| **Adapter** | `BankAdapter`, `MockBankAdapter`, `BankApiClient` (`src/com/otog/service/`) | Adapts simulated external banking/payment channel protocols (GCash, Maya, BDO, BPI, 7-Eleven OTC) behind a uniform internal `BankAdapter` contract (`processCashIn`, `processCashOut`, `queryStatus`). |
| **Bridge** | `ExportableDocument`, `WalletStatementDocument`, `TransactionReceiptDocument` ↔ `DocumentFormat`, `PipeTextDocumentFormat`, `CsvDocumentFormat` (`src/com/otog/service/`) | Decouples file-handling document abstractions (wallet statements and receipts) from storage formats (masked pipe-delimited `.txt` `OTOG-STATEMENT-V1` vs. RFC-4180 `.csv` `OTOG-STATEMENT-CSV-V1`) inside `StatementFileService`. |
| **Composite** | `ReceiptComponent` (`ReceiptLine`, `ReceiptSection`) & `FeeComponent` (`FlatFee`, `PercentageFee`, `CompositeFeePolicy`) (`src/com/otog/model/`) | Combines leaf and composite nodes uniformly: `ReceiptSection`/`ReceiptLine` compose polymorphic transaction receipts without duplicated string builders, while `CompositeFeePolicy` composes flat and percentage `BigDecimal` fees configured in `config/otog.properties`. |
| **Decorator** | `BankAdapterDecorator`, `ValidatingBankAdapterDecorator`, `AuditingBankAdapterDecorator` (`src/com/otog/service/`) | Dynamically wraps `BankAdapter` instances created by `PaymentProviderFactory` to enforce input/precision invariants (`ValidatingBankAdapterDecorator`) and record bounded secret-free gateway call audits (`AuditingBankAdapterDecorator`), with `BankAdapterDecorator.findAdapter(...)` unwrapping layers cleanly for `BankApiClient`. |
| **Facade** | `WalletOperations` / `WalletService` (`src/com/otog/service/WalletService.java`) & `AdminService` / `AdminServiceImpl` (`src/com/otog/service/AdminServiceImpl.java`) | Simplifies Swing UI and workflow interactions behind clean service contracts, encapsulating canonical per-wallet `ReentrantLock` ordering, SQLite `executeInTransaction(...)` boundaries, double-entry ledger writes, and RBAC checks. |
| **Flyweight** | `UiTheme` (`src/com/otog/util/UiTheme.java`) | Shares immutable `Font` (keyed by `FontKey`), `Color`, and `Border` flyweight instances initialized from `OtogConfig`, eliminating duplicate font/color allocations and hardcoded styling literals across all Swing windows and panels. |
| **Proxy (Protection)** | `ProtectedWalletOperationsProxy`, `ProtectedAdminServiceProxy` (`src/com/otog/service/`) | Guards runtime dashboard access (`UserLogin.openDashboard()` and `Main.withOwnedDatabase(...)`) by enforcing an active, non-suspended authenticated session, wallet ownership checks, and `ADMIN` role verification before delegating to the real facades. |

---

## 📂 File Handling — Store & Load Data (CSIT227 [5% + Bonus 5%] — CAP-21)

In addition to embedded SQLite persistence (`data/otog.db`), the application implements explicit Java file I/O in `com.otog.service.StatementFileService` (`src/com/otog/service/StatementFileService.java`):

- **Storing Data to Disk (`BufferedWriter` with `try-with-resources`)**:
  - `exportWalletStatement(User, Wallet, List<Transaction>, Path)` writes a portable `OTOG-STATEMENT-V1` file containing masked account metadata (`StatementFileService.maskIdentifier(...)`), `BigDecimal` wallet balance, and pipe-delimited transaction history rows.
  - `exportReceipt(Transaction, Path)` exports individual masked transaction receipts (`OTOG-RECEIPT-V1`).
- **Loading Data into the Program (`BufferedReader` with `try-with-resources`)**:
  - `importWalletStatement(Path)` reads and validates saved statement files back into an immutable `LoadedStatement` model (`List<StatementEntry>`), checking header version, `BigDecimal` scale, and row counts, and throwing typed `OtogException` subclasses (`WalletException`, `InvalidAmountException`) if a file is missing or corrupted.
  - `importReceipt(Path)` reads and validates saved receipt files back into the application.
- **Swing UI Integration (`OverviewPanel.form` & `Main.java`)**:
  - The **Overview** tab provides **Export Statement**, **Load Statement**, and **Save Receipt File** buttons wired to `JFileChooser` and off-EDT `SwingWorker` tasks (`exportStatementAsync`, `importStatementAsync`, `exportSelectedReceiptAsync`).

---

## 🖼️ IntelliJ Swing `.form` GUI Architecture (CSIT227 [15%] — CAP-19 & CAP-20)

Every screen, modal dialog, and dashboard tab is backed by a bound **IntelliJ IDEA GUI Designer `.form` file** using `GridLayoutManager` and `GridConstraints` (`lib/forms_rt.jar`):

| Bound `.form` File | Bound Java Class | UI Responsibility |
| :--- | :--- | :--- |
| `src/UserLogin.form` | `UserLogin` (`JFrame`) | Password sign-in, simulated SMS 2FA OTP verification, and registration launch |
| `src/com/otog/ui/RegisterDialog.form` | `com.otog.ui.RegisterDialog` (`JDialog`) | New user registration with password confirmation and automatic wallet provisioning |
| `src/com/otog/ui/PinSettingsDialog.form` | `com.otog.ui.PinSettingsDialog` (`JDialog`) | Setting or changing the 4–6 digit Transaction PIN with password/PIN reauthentication |
| `src/com/otog/ui/Main.form` | `com.otog.ui.Main` (`JFrame`) | Authenticated dashboard shell (identity header, balance display, LIFO back button, `JTabbedPane`, status footer) |
| `src/com/otog/ui/OverviewPanel.form` | `com.otog.ui.OverviewPanel` (`JPanel`) | Quick Action shortcuts, Statement File Export/Load actions, and Recent Activity `CardLayout` (`LOADING`, `EMPTY`, `ERROR`, `READY`) |
| `src/com/otog/ui/CashInOutPanel.form` | `com.otog.ui.CashInOutPanel` (`JPanel`) | Cash-In / Cash-Out form with linked bank accounts, channels, mock provider modes, and PIN field |
| `src/com/otog/ui/SendMoneyPanel.form` | `com.otog.ui.SendMoneyPanel` (`JPanel`) | Wallet-to-wallet transfer form with recipient lookup, pre-confirmation preview, and PIN authorization |
| `src/com/otog/ui/AdminControlsPanel.form` | `com.otog.ui.AdminControlsPanel` (`JPanel`) | Role-gated Admin Controls split-pane (Registered Accounts & Wallets table, Suspend/Reactivate/Role actions, and Audit Log table) |

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

## 🛡️ Admin Permissions & Account Controls (CAP-09)

The platform enforces strict role-based access control, account lifecycle governance, and traceable administrative audits:

### 1. Role Authorization (`USER`, `MERCHANT`, `ADMIN`)
- **Seeded Administrator**: `USR-ADMIN-01` (`admin` / `password`) is automatically seeded with `ADMIN` role, active status, seeded credentials, and an initial wallet.
- **Privilege Separation**:
  - `ADMIN`: Full administrative authority to view all user accounts, wallet accounts, and system-wide transactions, suspend/reactivate accounts with mandatory recorded reasons, and inspect user histories.
  - `MERCHANT` & `USER`: Standard operational roles restricted to managing their own wallets and viewing their own transaction histories.
  - Any attempt by ordinary users to invoke administrative methods or inspect another user's history throws `SecurityException`.
  - Self-registration with `UserRole.ADMIN` is strictly disallowed; roles can only be promoted by administrators with an audit trail.

### 2. Account Suspension & Reactivation
- **Mandatory Reason Requirement**: Account suspensions and reactivations strictly require non-empty recorded justifications (`IllegalArgumentException` thrown on empty/blank reasons).
- **Wallet Synchronization**: Suspending a user automatically transitions their wallet to `WalletStatus.FROZEN` and invalidates any active sessions in `SessionManager`. Reactivation restores active status and resets lockout limits.
- **Self-Protection**: Administrators are barred from suspending their own account (`OtogException`).

### 3. Payment Protection & Traceable Audit Trail
- **Suspended Account Payment Barrier**: Accounts in `SUSPENDED` status are blocked from initiating outgoing payments (`transfer`, `cashOut`, `transferWithPin`, `cashOutWithPin`), throwing `WalletException`.
- **Traceable Actions Without Secrets**: All administrative actions are recorded in the `admin_actions` table with action ID, administrator ID, action type, target user ID, reason, and sanitized details. Secrets (passwords, PINs, auth salts, hashes, tokens, CVVs) are strictly excluded and automatically redacted.
- **Prohibited Direct Balance Editing**: Direct arbitrary modification of balances is strictly prohibited (`UnsupportedOperationException`); all balance adjustments must occur through double-entry ledger transactions (`transfer`, `cashIn`, `cashOut`).

---

## 🖥️ Multi-Screen Navigation & `JOptionPane` Error Handling

### 1. Navigable Forms Flow
- **Authentication & Onboarding (`UserLogin`, `RegisterDialog`)**:
  - **Login Screen (`pnlPassword`) ↔ Registration (`RegisterDialog`)**: New accounts can register via `Create New Account / Register` and return to Login via `Back to Login` (pre-filling the registered mobile number on completion).
  - **Login Screen (`pnlPassword`) ↔ Two-Factor Authentication (`pnl2FA`)**: Valid credentials issue a simulated 6-digit SMS OTP and transition to `pnl2FA`; users can verify, resend OTP, or click **Back to Login** to clear pending state and return to `pnlPassword`.
- **Authenticated Dashboard (`com.otog.ui.Main`)**:
  - **Protected Entry**: Enforces `SessionManager.getInstance().requireAuthentication()` before opening.
  - **Tabs & Quick Actions**: `Overview` (with Quick Action shortcuts to `Cash In`, `Cash Out`, `Send Money`, and `Set / Change PIN`), `Cash In / Out`, `Send Money`, and role-gated `Admin Controls` (visible only to `ADMIN`).
  - **Back Navigation**: Header `← Back` button maintains a LIFO tab navigation history stack, and form tabs (`Cash In / Out`, `Send Money`) include `← Back to Overview` buttons.
  - **Modal Sub-Screens & Logout**: `Transaction PIN` setup/change dialog, double-click / `View Receipt` transaction receipt viewer, `SendMoneyService` pre-confirmation preview dialog, and `Logout` confirmation returning cleanly to `UserLogin`.

### 2. Error, Validation & Confirmation Handling (`JOptionPane`)
- **Login & Registration (`UserLogin`, `RegisterDialog`)**: Displays `JOptionPane` alerts for missing/invalid fields, mismatched or short passwords, duplicate mobile/email errors, invalid credentials, account lockouts, 2FA/OTP errors (including resend cooldown), and startup initialization failures.
- **Dashboard & Payments (`Main`)**:
  - Validates amounts (`MoneyUtil`), recipient identifiers, and Transaction PIN presence before submitting, surfacing warnings/errors via `JOptionPane` (and prompting first-time users to configure a Transaction PIN if unset).
  - Uses `SendMoneyService.preview(...)` off the EDT to resolve recipients, verify transfer limits (`PHP 1.00`–`PHP 50,000.00`), check balance, and present a `JOptionPane` confirmation summary before debiting funds.
  - Surfaces `InsufficientFundsException`, `EntityNotFoundException`, `WalletException`, PIN failures, admin operation errors, refresh failures, and session timeouts/PIN lockouts through `JOptionPane` dialogs while keeping DB and crypto work off the Swing EDT.

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
