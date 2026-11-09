# Collections Performance Tracker

A small, self-hosted collection-recording system for an operations team. The Spring Boot application serves the responsive browser UI and secured REST API. A separate FastAPI service validates CSV/XLS/XLSX imports and can create detailed PDF reports. PostgreSQL is the Docker Compose default; SQLite is supported for a lightweight single-instance setup.

## Requirements

- Docker Engine with Docker Compose v2 (recommended), or Java 17+, Maven 3.9+, and Python 3.11+ for local development.
- Do not use the example passwords or expose the development database to an untrusted network.

## Configure and run with Docker

1. Copy `.env.example` to `.env`.
2. Set `DB_PASSWORD`, `JWT_SECRET` (at least 32 random characters), and a unique `BOOTSTRAP_ADMIN_PASSWORD` (at least 12 characters). The temporary admin password is required at first startup; the admin must change it at first sign-in.
3. Start the stack with `docker compose up --build -d`.
4. Open [http://localhost:8080](http://localhost:8080), sign in using the configured bootstrap username/password, and set a new password.
5. API documentation is available at [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html), with the OpenAPI JSON at `/v3/api-docs`.

The database and import service are not published to the host. Persistent PostgreSQL data is kept in the `postgres-data` volume. Use `docker compose logs -f app` to inspect startup and migration problems.

## Local development

Start a PostgreSQL 16 instance and set the variables in `.env`, or configure an SQLite database:

```powershell
$env:DB_URL = "jdbc:sqlite:./data/collections.db"
$env:DB_USER = ""
$env:DB_PASSWORD = ""
$env:FLYWAY_LOCATIONS = "classpath:db/migration/sqlite"
$env:SPRING_JPA_DATABASE_PLATFORM = "org.hibernate.community.dialect.SQLiteDialect"
$env:JWT_SECRET = "replace-with-a-random-secret-at-least-32-characters"
$env:BOOTSTRAP_ADMIN_PASSWORD = "temporary-unique-password"
New-Item -ItemType Directory -Force data | Out-Null
```

In one terminal, run the import/report service:

```powershell
cd import-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app:app --reload --port 8000
```

In another terminal, run the backend from the project root:

```powershell
mvn spring-boot:run
```

The app listens on port 8080. The backend applies versioned Flyway migrations at startup. Set `IMPORT_SERVICE_URL` if the Python service is hosted elsewhere.

## Features and behavior

- Username/password login, BCrypt (work factor 12), signed 8-hour JWTs, roles `ADMIN` and `OFFICER`, and password-change-required temporary credentials.
- Five consecutive incorrect password attempts lock an account for 15 minutes. A successful login resets the counter.
- Collection records require a client name, globally unique tracking number and PRN, positive amount (two decimal places), and collection timestamp. Database unique constraints are the final race-safe duplicate guard; conflicts return HTTP 409 with existing record details where available.
- All period boundaries use `Africa/Kampala`. The weekly period starts Friday at 00:00 and ends at the following Friday 00:00 (Friday through Thursday); the daily period is the current Kampala calendar day. Custom date ranges include both selected dates.
- Admins can see and manage all records and accounts. Officers can see and delete only their own entries. Deletions record the actor and entry identifiers in the audit table.
- Record search, pagination (maximum 100 per page), CSV/XLSX exports, browser print-to-PDF, and server-generated PDF reports.
- CSV, XLSX, and legacy XLS imports accept at most 300 data rows and 10 MB per upload. Rows are validated independently; import responses include line/row numbers and skip reasons. Tracking number and PRN conflicts are also skipped with a reason.

## REST API

Swagger/OpenAPI is generated from the Spring controllers at `/swagger-ui.html` and `/v3/api-docs`. Authenticate with `POST /api/auth/login`, then pass `Authorization: Bearer <token>` on secured endpoints.

| Method | Path | Access | Description |
|---|---|---|---|
| POST | `/api/auth/login` | Public | Sign in; returns JWT and forced-change flag |
| GET | `/api/auth/me` | Signed in | Current account |
| POST | `/api/auth/password` | Signed in | Set a new password |
| POST | `/api/accounts` | Admin | Create a forced-change account |
| GET | `/api/accounts` | Admin | List accounts |
| PATCH | `/api/accounts/{id}/enabled` | Admin | Enable or disable account |
| POST | `/api/entries` | Admin/officer | Create collection |
| GET | `/api/entries` | Admin/officer | Search and page scoped records; optional `q`, `tracking`, `prn`, `from`, and exclusive `to` filters |
| DELETE | `/api/entries/{id}` | Admin/owner | Delete an entry and audit the action |
| GET | `/api/summary?period=day|week|custom` | Admin/officer | Count and sum; custom also requires `from=YYYY-MM-DD&to=YYYY-MM-DD` |
| GET | `/api/entries/export?period=...` | Admin/officer | Period CSV export |
| GET | `/api/entries/export.xlsx?period=...` | Admin/officer | Period Excel export |
| GET | `/api/reports/pdf?period=...` | Admin/officer | Generate a scoped ReportLab PDF via the Python service |
| POST | `/api/imports` | Admin/officer | Upload a CSV/XLS/XLSX file as multipart `file` |
| GET | `/health` | Public | Liveness check |

The import/report service has its own generated OpenAPI page at `/docs` when accessed directly. `POST /imports/validate` parses and validates an uploaded file. `POST /reports/pdf` accepts a JSON report payload and returns a PDF. In Compose, this service is private to the application network.

## Tests

Run backend tests with `mvn test`. Run import/report tests with:

```powershell
cd import-service
python -m pip install -r requirements.txt
python -m pytest
```

The SQLite option is intended for local, single-instance use; use PostgreSQL for concurrent users and production deployments. Configure TLS at a trusted reverse proxy before exposing the app publicly. Back up the database volume and protect the bootstrap/JWT secrets.

## Import template

Required column headers (case-insensitive aliases are accepted): `clientName`, `trackingNumber`, `prn`, `amount`, `collectedAt`. Optional: `receiptNumber`, `notes`. Example:

```csv
clientName,trackingNumber,prn,amount,collectedAt,receiptNumber,notes
Example Client,TRK-001,PRN-001,25000.00,2025-01-03T09:30:00,RCPT-001,Window collection
```

Naive spreadsheet timestamps are interpreted as Kampala local time. ISO timestamps with a timezone are preserved and normalized to UTC.
