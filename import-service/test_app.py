import io

from fastapi.testclient import TestClient

from app import _normalize_row, app

client = TestClient(app)


def test_normalizes_kampala_local_datetime_and_amount():
    row = _normalize_row(
        {"Client": "Amina", "tracking_number": "T-1", "PRN": "P-1",
         "Amount": "42.5", "Collected At": "2025-01-03T09:30:00"},
        2,
    )
    assert row["amount"] == "42.50"
    assert row["collectedAt"] == "2025-01-03T06:30:00Z"


def test_rejects_non_positive_amount():
    try:
        _normalize_row({"client": "A", "tracking": "T", "prn": "P",
                        "amount": "0", "date": "2025-01-03"}, 2)
    except ValueError as exc:
        assert "positive" in str(exc)
    else:
        raise AssertionError("zero amount should be rejected")


def test_csv_import_reports_invalid_rows_with_source_row_number():
    response = client.post(
        "/imports/validate",
        files={"file": ("entries.csv",
                        b"clientName,trackingNumber,prn,amount,collectedAt\n"
                        b"Client,T-1,P-1,12.00,2025-01-03\n"
                        b"Client,T-2,P-2,0,2025-01-03\n",
                        "text/csv")},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["received"] == 2
    assert len(data["rows"]) == 1
    assert data["skipped"][0]["row"] == 3
    assert "positive" in data["skipped"][0]["reason"]


def test_xlsx_import_accepts_workbook_rows():
    from openpyxl import Workbook

    workbook = Workbook()
    sheet = workbook.active
    sheet.append(["Client", "Tracking Number", "PRN", "Amount", "Date"])
    sheet.append(["Client", "T-1", "P-1", 25, "2025-01-03"])
    buffer = io.BytesIO()
    workbook.save(buffer)
    response = client.post(
        "/imports/validate",
        files={"file": ("entries.xlsx", buffer.getvalue(),
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
    )
    assert response.status_code == 200
    assert response.json()["rows"][0]["amount"] == "25.00"


def test_import_rejects_more_than_300_rows():
    rows = ["clientName,trackingNumber,prn,amount,collectedAt"]
    rows.extend(f"Client,T-{i},P-{i},1,2025-01-03" for i in range(301))
    response = client.post(
        "/imports/validate",
        files={"file": ("entries.csv", "\n".join(rows).encode(), "text/csv")},
    )
    assert response.status_code == 413
    assert "300" in response.json()["detail"]


def test_report_endpoint_returns_a_pdf():
    response = client.post("/reports/pdf", json={
        "title": "Weekly report",
        "period": "Friday–Thursday",
        "total": "12.50",
        "entries": [{"clientName": "Client", "trackingNumber": "T-1", "prn": "P-1",
                     "amount": "12.50", "collectedAt": "2025-01-03T06:30:00Z"}],
    })
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/pdf"
    assert response.content.startswith(b"%PDF")
