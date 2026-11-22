# One Team One Goal (OTOG)

## Description

An **E-Wallet System** built in Java (Swing + SQLite) that provides a secure, efficient, and user-friendly digital financial platform for managing wallets, Cash-In/Cash-Out operations, peer-to-peer money transfers, authentication (password, 2FA, and Transaction PIN), and administrative account controls.

In compliance with **CSIT227 - Object Oriented Programming**.

## Project Structure

```text
OTOG/
├── Class Diagram/           # UML class diagram (ewallet_cd.png)
├── config/                  # Runtime configuration (otog.properties)
├── lib/                     # External JAR dependencies (SQLite JDBC, JUnit 5, forms_rt)
├── src/
│   ├── UserLogin.java       # Application entry point & login screen
│   ├── UserLogin.form       # IntelliJ Swing UI form for login
│   └── com/otog/
│       ├── database/        # SQLite database manager & ACID transaction helpers
│       ├── exception/       # Custom domain exceptions
│       ├── model/           # Domain entities, transaction types, fees & receipts
│       ├── repository/      # JDBC data access layer
│       ├── service/         # Business logic (wallet, auth, OTP, admin, file I/O)
│       ├── ui/              # Swing dashboard, panels, dialogs & bound .form files
│       └── util/            # Configuration, UI theme, money & security utilities
├── test/com/otog/           # JUnit 5 test suites
├── build.bat                # Compiles sources and tests
├── run.bat                  # Launches the application
└── test.bat                 # Runs the JUnit 5 test suite
```

## Quick Start

Requires **JDK 19+**.

```cmd
build.bat   # Compile sources and tests
test.bat    # Run JUnit 5 tests
run.bat     # Launch the application
```

## Team Members

| Member | Role |
| :--- | :--- |
| **Marc Andrei Regulacion** | Baseline & Domain Owner |
| **Jeremy Navarro** | Auth & Login UI |
| **Kyle Nino Duran** | Core Transactions UI |
| **Matthew Dela Cruz** | Loans & Investments |
| **Al Francis Egtic** | Security, QA & Docs |
