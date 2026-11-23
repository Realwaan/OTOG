# One Team One Goal (OTOG)

## Description

An **E-Wallet System** built in Java (Swing + SQLite) that provides a secure, efficient, and user-friendly digital financial platform for managing wallets, Cash-In/Cash-Out operations, peer-to-peer money transfers, bill payments, merchant QR payments, filtered transaction history with masked receipts and notifications, authentication (password, simulated SMS 2FA, and Transaction PIN), and administrative account controls.

In compliance with **CSIT227 - Object Oriented Programming**.

## Project Structure

```text
OTOG/
├── Class Diagram/           # UML class diagram (ewallet_cd.png)
├── config/                  # Runtime configuration & deterministic demo seeds (otog.properties)
├── docs/                    # Integration checkpoint & feature freeze documentation
├── lib/                     # External JAR dependencies (SQLite JDBC, JUnit 5, forms_rt)
├── src/
│   ├── UserLogin.java       # Application entry point, login screen & CLI verify/demo flags
│   ├── UserLogin.form       # IntelliJ Swing UI form for login
│   └── com/otog/
│       ├── database/        # SQLite database manager & ACID transaction helpers
│       ├── exception/       # Custom domain exceptions
│       ├── model/           # Domain entities, transaction types, QR payload, fees & receipts
│       ├── repository/      # JDBC data access layer
│       ├── service/         # Business logic (wallet, auth, OTP, bills, QR, admin, demo seeder, file I/O)
│       ├── ui/              # Swing dashboard, panels, dialogs & bound .form files
│       └── util/            # Configuration, UI theme, money & security utilities
├── test/com/otog/           # JUnit 5 unit & cross-feature integration test suites
├── build.bat                # Compiles sources and tests
├── run.bat                  # Launches the application (supports --verify, --seed-demo, --reset-demo)
└── test.bat                 # Runs the JUnit 5 test suite
```

## Quick Start

Requires **JDK 19+**.

```cmd
build.bat             # Compile sources and tests
test.bat              # Run JUnit 5 tests
run.bat --seed-demo   # Verify deterministic demo data & print summary
run.bat --reset-demo  # Reset data/otog.db to a fresh deterministic demo baseline
run.bat               # Launch the application
```

See [CAP-14 Feature Freeze & Demo Guide](docs/CAP-14-FEATURE-FREEZE.md) for the frozen scope matrix, deferred modules, and deterministic demo credentials.

## Team Members

| Member | Role |
| :--- | :--- |
| **Marc Andrei Regulacion** | Baseline & Domain Owner |
| **Jeremy Navarro** | Auth & Login UI |
| **Kyle Nino Duran** | Core Transactions UI |
| **Matthew Dela Cruz** | Loans & Investments |
| **Al Francis Egtic** | Security, QA & Docs |
