# Collections Performance Tracker

A responsive collection-entry and reporting application for Ugandan collection officers. The Java 17+ Spring Boot service provides the web UI, REST API, authentication and persistent storage. A private Python 3.11+ FastAPI service validates CSV/XLS/XLSX imports and generates ReportLab PDFs. PostgreSQL is the Docker Compose database; SQLite is available for local, single-instance development.

## Requirements

- Docker Engine and Docker Compose v2 (recommended), or Java 17+, Maven 3.9+, and Python 3.11+ for local development.
- Do not use example passwords or expose a development database to an untrusted network.

## Install, configure and run

### Docker Compose (recommended)

1. Copy `.env.example` to `.env`.
2. Set `DB_PASSWORD`, `JWT_SECRET` (at least 32 random characters), and a unique `BOOTSTRAP_ADMIN_PASSWORD` (at least 12 characters). The bootstrap administrator must change this temporary password at first sign-in.
3. Start all three services:

   ```powershell
   docker compose up --build -d
   ```

4. Open [http://localhost:8080](http://localhost:8080), sign in with the configured bootstrap credentials, and change the password.
5. View the REST API documentation at [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) and the OpenAPI definition at `/v3/api-docs`.

The PostgreSQL database and import/report service are only exposed on the internal Compose network. Database files persist in the `postgres-data` volume. Use `docker compose logs -f app` to inspect startup and migration errors.

### Local development

Start PostgreSQL 16, configure the environment below, and create a temporary bootstrap password and JWT secret. For SQLite instead of PostgreSQL:

```powershell
$env:DB_URL = "jdbc:sqlite:./data/collections.db"
$env:DB_USER = ""
$env:DB_PASSWORD = ""
$env:SPRING_JPA_DATABASE_PLATFORM = "org.hibernate.community.dialect.SQLiteDialect"
$env:JWT_SECRET = "<random secret of at least 32 characters>"
$env:BOOTSTRAP_ADMIN_PASSWORD = "<temporary password of at least 12 characters>"
New-Item -ItemType Directory -Force data | Out-Null
```

In one terminal:

```powershell
cd import-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m uvicorn app:app --reload --port 8000
```

In another terminal, from the project root:

```powershell
mvn spring-boot:run
```

The web app listens on port 8080. Flyway applies PostgreSQL migrations at startup; SQLite uses the built-in versioned migration runner. Set `IMPORT_SERVICE_URL` if the private Python service is hosted at a different address.

### Deploy to Render

The root `render.yaml` Blueprint defines the public Java web service, private import/report service, and PostgreSQL database. The web service connects to PostgreSQL over Render's private network, and public officer registration is enabled.

1. Push this repository to GitHub and connect the repository in the [Render Dashboard](https://dashboard.render.com).
2. Select **New > Blueprint**, choose the repository and `master` branch, review the services and their plans/prices, then deploy the Blueprint. The database and non-free services may incur recurring charges; confirm the displayed costs before deploying.
3. During the initial Blueprint setup, provide a unique bootstrap admin password of at least 12 characters. Render generates the JWT secret; neither secret is stored in this repository.
4. After the deploy succeeds, open the web service's `onrender.com` URL to sign in as the bootstrap admin or create an officer account. Change the bootstrap password at first sign-in.

Do not use a temporary/expiring database for real collection records. Configure database backups and restrict account creation if public registration is no longer appropriate.

## Features

- Sign-in and officer self-registration; accounts use BCrypt password hashes and signed JWTs.
- Roles are `ADMIN` and `OFFICER`. Officers can access and delete their own records; administrators can view and manage all records and select an officer for records, reports, and imports.
- The default admin is seeded from environment variables on first startup and must change the temporary password. Five failed password attempts lock an account for 15 minutes. Admins can create accounts, edit usernames/display names/roles, activate or deactivate accounts, and reset temporary passwords. At least one active administrator must remain; administrators cannot demote themselves.
- Required entry fields: Kampala calendar date, tracking number (`G` + six digits + hyphen + four digits), 13-digit PRN, and positive whole UGX amount. Lowercase `g` is normalized to uppercase. Date, tracking number, PRN, and amount are checked in the UI and API.
- Tracking numbers and PRNs are globally unique, including case-insensitive tracking number matches. Database unique indexes prevent simultaneous duplicate saves; conflicts identify the original record date and officer. Deleting a record logs the actor and timestamp and releases both identifiers.
- Dashboard cards show daily (date-selectable), current Friday–Thursday weekly, and current-calendar-month totals and record counts. Daily breakdowns are available for the current week and month. Admin officer filters apply to all dashboard figures.
- Searchable, server-paginated records support date ranges and a running filtered total; exports are available as CSV and XLSX.
- Browser-generated jsPDF reports include daily totals, a bar chart, full transactions, totals, averages, and best/lowest days. Weekly downloads are enabled on Thursdays. Custom date ranges include Friday–Thursday subtotals. A separate ReportLab PDF endpoint is available through the Java API and Python service.
- CSV, XLSX, and legacy XLS imports accept header-matched columns or positional rows without headers, up to 300 data rows and 10 MB per file. Dates accept Excel date cells, `dd/mm/yyyy`, or `yyyy-mm-dd`. PRNs are read as text; invalid rows, duplicates in the file, and database duplicates are reported by row number. Admins can import on behalf of an officer.
- Responsive mobile/desktop layout, light/dark mode, and a downloadable Excel import template.
- All date boundaries use `Africa/Kampala`; currency is UGX with no fractional amounts.

## REST endpoints

All `/api` endpoints except account registration and login require a bearer token. Sign in with `POST /api/auth/login` and send `Authorization: Bearer <token>` on secured requests. Springdoc provides the complete generated schemas at `/swagger-ui.html` and `/v3/api-docs`.

| Method | Path | Access | Description |
|---|---|---|---|
| POST | `/api/auth/register` | Public | Create an officer account |
| POST | `/api/auth/login` | Public | Sign in and receive a JWT |
| GET | `/api/auth/me` | Signed in | Current user and forced-password-change status |
| POST | `/api/auth/password` | Signed in | Change password |
| GET | `/api/dashboard?date=YYYY-MM-DD&officerId=...` | Signed in | Daily, weekly, monthly totals and breakdowns |
| POST | `/api/entries?officerId=...` | Signed in | Create a collection (admins may choose an active officer) |
| PUT | `/api/entries/{id}` | Admin/owner | Update a collection record; edits are audited |
| GET | `/api/entries?page=0&size=25&q=...&from=...&to=...&officerId=...` | Signed in | Scoped search and date-range pagination |
| GET | `/api/entries/totals?q=...&from=...&to=...&officerId=...` | Signed in | Amount total for all matching records |
| DELETE | `/api/entries/{id}` | Admin/owner | Delete a record and create an audit event |
| GET | `/api/summary?period=day\|week\|month\|custom&from=...&to=...` | Signed in | Period total and record count |
| GET | `/api/entries/export?period=...` | Signed in | CSV export |
| GET | `/api/entries/export.xlsx?period=...` | Signed in | Excel export |
| GET | `/api/imports/template` | Signed in | Download the Excel import template |
| POST | `/api/imports?officerId=...` | Signed in | Import multipart `file`; admins may select an officer |
| GET | `/api/reports/pdf?period=week` | Signed in | Create a scoped ReportLab PDF through FastAPI |
| GET/POST | `/api/accounts` | Admin | List accounts / create an account |
| PATCH | `/api/accounts/{id}` | Admin | Update username, display name, or role |
| PATCH | `/api/accounts/{id}/enabled` | Admin | Activate or deactivate an account |
| POST | `/api/accounts/{id}/password` | Admin | Set a temporary password and require a change |
| GET | `/health` | Public | Liveness check |

The import/report service has its own OpenAPI UI at `/docs` when accessed directly. `POST /imports/validate` parses and validates an uploaded file. `POST /reports/pdf` accepts the report payload from the Java API. In Compose, this service is private to the application network.

## Tests

Run both test suites:

```powershell
mvn test
python -m pip install -r import-service/requirements.txt
python -m pytest -q import-service
```

GitHub Actions runs the Java tests on Java 17 and the import/report tests on Python 3.11 for pushes and pull requests.

## Database migrations and production

PostgreSQL migrations are managed by Flyway. SQLite is intended for local, single-instance development and uses a small versioned SQL migration runner because Flyway does not publish a SQLite database module. Migration V2 converts existing collection timestamps to Kampala calendar dates, preserves legacy fields, and introduces whole-shilling amounts rounded from any historical fractional values. Use PostgreSQL for concurrent users and production deployments. Configure TLS through a trusted host/reverse proxy, keep environment secrets private, and back up the persistent database.

The `.env.example` file contains placeholders only. Never commit a populated `.env` file or reuse the sample passwords.
