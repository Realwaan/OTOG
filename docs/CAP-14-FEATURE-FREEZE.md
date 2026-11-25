# CAP-14 — Integration Checkpoint & Feature Freeze

## Governance & Freeze Policy

| Milestone | Date (Asia/Manila) | Rule |
| :--- | :--- | :--- |
| Integration Checkpoint | November 23–27, 2026 | Merge reviewed features into one shared runnable build and verify cross-feature consistency |
| **Feature Freeze** | **November 29, 2026** | **No new features after November 29, 2026.** Any unresolved work after this date becomes either a **bug fix** on frozen scope or **explicitly deferred scope**. |
| Readiness Deadline | December 8, 2026 | Final bug fixes, demo rehearsal, and clean-checkout verification complete |
| Capstone Presentations | Dec 9 (F1), Dec 10 (F2), Dec 12 (G9), 2026 | Live demonstration using reproducible simulated demo data |

All financial integrations (banks, OTP SMS delivery, biller settlements, and merchant QR terminal workflows) are **simulated** for the capstone build.

---

## Module Integration & Scope Matrix

| Ticket | Module / Capability | Status at CAP-14 | Presentation Disposition |
| :--- | :--- | :--- | :--- |
| CAP-01 | Baseline reproducibility (`build.bat`, `run.bat`, `test.bat`, `--verify`) | Integrated & Verified | **Included** |
| CAP-02 | SQLite wallet domain, `BigDecimal` money rules, ACID rollback, concurrency locks | Integrated & Verified | **Included** |
| CAP-03 | Registration, salted SHA-256 auth, login lockout, `SessionManager` | Integrated & Verified | **Included** |
| CAP-04 | Simulated SMS 2FA OTP, Transaction PIN, inactivity timeout | Integrated & Verified | **Included** |
| CAP-05 | Dashboard shell, navigation back-stack, async `SwingWorker` loading | Integrated & Verified | **Included** |
| CAP-06 | Peer-to-peer Send Money (preview, limits, fee policy, PIN, idempotency) | Integrated & Verified | **Included** |
| CAP-07 | Cash In / Cash Out with simulated `MockBankAdapter`, timeout/settlement sync | Integrated & Verified | **Included** |
| CAP-08 | Filtered transaction history, masked receipts, repeat drafts, post-commit notifications | Integrated & Verified | **Included** |
| CAP-09 | Role-based Admin Controls, suspend/reactivate, traceable secret-free audit log | Integrated & Verified | **Included** |
| CAP-10 | Pay Bills with seeded billers, masked biller refs, fee policy, notifications | Integrated & Verified | **Included** |
| CAP-11 | Merchant QR PNG generation/import, validation, PIN payment, merchant terminal | Integrated & Verified (PR #16 merged in CAP-14) | **Included** |
| CAP-13 | Basic simulated investment workflow (PR #15) | Integrated & Verified (PR #15 merged to `main`) | **Included** |
| CAP-19..21 | Bound IntelliJ `.form` panels, `StatementFileService` file I/O, GoF design patterns & externalized config | Integrated & Verified | **Included** |
| CAP-12 | Simulated loans workflow (`LoanService` contract only) | No PR / unimplemented | **Explicitly Deferred** (`Loans` tab remains hidden) |

### Post-Freeze Change Control (After November 29, 2026)
1. **No new features, tabs, or schema expansions** may be merged after November 29, 2026.
2. Any defect discovered in CAP-01 through CAP-11, CAP-13, or CAP-19 through CAP-21 is tracked and fixed strictly as a **bug** with regression test coverage.
3. Unfinished optional modules (**CAP-12 Loans**) remain **explicitly deferred** and hidden from the dashboard UI.

---

## Deterministic Demo Data Catalog

All demo seeds are externalized in `config/otog.properties` and provisioned idempotently on startup via `DatabaseManager.initSchema()` and `DemoDataSeeder`.

### Seeded Accounts

| Role | User ID | Wallet ID | Name / Username | Mobile | Email | Password | PIN | Initial Balance |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ADMIN` | `USR-ADMIN-01` | `WLT-ADMIN-01` | `admin` | `09000000000` | `admin@otog.ph` | `password` | `123456` | `PHP 1,000.00` |
| `MERCHANT` | `USR-MERCHANT-01` | `WLT-MERCHANT-01` | `OTOG Merchant Store` | `09178888888` | `merchant@otog.ph` | `Merchant123!` | `8888` | `PHP 0.00` |
| `USER` | `USR-DEMO-01` | `WLT-DEMO-01` | `Demo Sender` | `09000000101` | `demo.sender@otog.ph` | `DemoPass123!` | `1234` | `PHP 5,000.00` |
| `USER` | `USR-DEMO-02` | `WLT-DEMO-02` | `Demo Recipient` | `09000000102` | `demo.recipient@otog.ph` | `DemoPass123!` | `5678` | `PHP 1,500.00` |

### Seeded Demo Banks & Cash Channels
- **Linked Bank Accounts** (auto-seeded deterministically for every seeded account):
  - `BDO Unibank` (`•••• 1234`, Default)
  - `Bank of the Philippine Islands (BPI)` (`•••• 5678`)
  - `Metrobank` (`•••• 9012`)
- **Cash Channels**: `GCash`, `Maya`, `BDO`, `BPI`, `7-Eleven OTC`

### Seeded Demo Billers & Sample References
| Biller | Sample Account Reference | Sample Amount |
| :--- | :--- | :--- |
| `Meralco (Electricity)` | `MERALCO-10023456` | `PHP 1,250.00` |
| `Manila Water (Water)` | `MWATER-20034567` | `PHP 485.50` |
| `Maynilad (Water)` | `MAYNILAD-30045678` | `PHP 520.00` |
| `PLDT (Telecom & Internet)` | `PLDT-40056789` | `PHP 1,699.00` |
| `Globe Telecom (Mobile & Broadband)` | `GLOBE-50067890` | `PHP 999.00` |

---

## Reproducible Build, Verification & Demo Reset Commands

```cmd
build.bat             # Cleanly compile all sources, tests, .form files, and config
test.bat              # Execute the full JUnit 5 verification & integration suite
run.bat --verify      # Headless startup smoke check for UserLogin and schema init
run.bat --seed-demo   # Verify deterministic demo data and print account/biller/bank summary
run.bat --reset-demo  # Recreate data/otog.db cleanly with fresh deterministic demo data
run.bat               # Launch the OTOG E-Wallet desktop application
```

---

## End-to-End Original Sequence Verification
1. **Sign In & 2FA**: Sign in as `Demo Sender` (`09000000101` / `DemoPass123!`) and complete simulated SMS 2FA OTP verification.
2. **Send Money Validation**: Attempt invalid/over-limit/insufficient-fund transfer or wrong PIN — verified rejected with zero balance mutation, zero ledger rows, and zero notifications.
3. **Send Money Execution**: Send funds to `Demo Recipient` (`09000000102`), confirm preview, authorize with Transaction PIN `1234`.
4. **Dual-Balance & Ledger Commit**: Sender wallet debited (`amount + fee`), recipient wallet credited (`amount`), `COMPLETED` transaction and double-entry `DEBIT` / `CREDIT` ledger entries committed atomically.
5. **Post-Commit Notifications**: Persistent `TRANSFER_SENT` notification delivered to `Demo Sender` and `TRANSFER_RECEIVED` notification delivered to `Demo Recipient` (with masked identifiers and unread badge counters).
