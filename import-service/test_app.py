import io

from fastapi.testclient import TestClient
from openpyxl import Workbook

from app import _normalize_row, app

client = TestClient(app)


def valid_row(**updates):
    row = {
        "Date": "2025-01-03",
        "Tracking number": "g240712-2665",
        "PRN": "2270005999848",
        "Amount": "1,250,000",
    }
    row.update(updates)
    return row


def test_normalizes_case_and_amount_without_fractional_ugx():
    row = _normalize_row(valid_row(), 2)
    assert row == {
        "row": 2,
        "date": "2025-01-03",
        "trackingNumber": "G240712-2665",
        "prn": "2270005999848",
        "amount": "1250000",
    }


def test_accepts_dd_mm_yyyy_and_excel_date_objects():
    from datetime import date

    assert _normalize_row(valid_row(Date="03/01/2025"), 2)["date"] == "2025-01-03"
    assert _normalize_row(valid_row(Date=date(2025, 1, 3)), 2)["date"] == "2025-01-03"


def test_rejects_bad_tracking_prn_and_fractional_amount():
    invalid_rows = [
        valid_row(**{"Tracking number": "T-1"}),
        valid_row(PRN="123"),
        valid_row(Amount="12.50"),
    ]
    for row in invalid_rows:
        try:
            _normalize_row(row, 2)
        except ValueError:
            pass
        else:
            raise AssertionError(f"invalid import row accepted: {row}")


def test_csv_header_import_skips_invalid_rows_with_original_row_number():
    response = client.post(
        "/imports/validate",
        files={"file": (
            "entries.csv",
            b"Date,Tracking number,PRN,Amount\n"
            b"03/01/2025,g240712-2665,2270005999848,1250000\n"
            b"03/01/2025,G123,2270005999849,100\n",
            "text/csv",
        )},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["received"] == 2
    assert data["rows"][0]["trackingNumber"] == "G240712-2665"
    assert data["skipped"][0]["row"] == 3


def test_csv_import_without_header_uses_column_positions_and_skips_duplicates():
    response = client.post(
        "/imports/validate",
        files={"file": (
            "entries.csv",
            b"2025-01-03,G240712-2665,2270005999848,100\n"
            b"2025-01-04,g240712-2665,2270005999849,200\n"
            b"2025-01-05,G123456-7890,2270005999848,300\n",
            "text/csv",
        )},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["received"] == 3
    assert len(data["rows"]) == 1
    assert data["skipped"][0]["row"] == 2
    assert "duplicate tracking number" in data["skipped"][0]["reason"]
    assert "duplicate PRN" in data["skipped"][1]["reason"]


def test_xlsx_import_preserves_text_prn_and_converts_excel_date_cells():
    workbook = Workbook()
    sheet = workbook.active
    sheet.append(["Date", "Tracking number", "PRN", "Amount"])
    sheet.append([__import__("datetime").date(2025, 1, 3), "G240712-2665", "0227000599984", 25000])
    buffer = io.BytesIO()
    workbook.save(buffer)
    response = client.post(
        "/imports/validate",
        files={"file": (
            "entries.xlsx",
            buffer.getvalue(),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )},
    )
    assert response.status_code == 200
    row = response.json()["rows"][0]
    assert row["date"] == "2025-01-03"
    assert row["prn"] == "0227000599984"


def test_import_rejects_more_than_300_rows():
    rows = ["Date,Tracking number,PRN,Amount"]
    rows.extend(f"2025-01-03,G{i:06d}-0001,{i:013d},1" for i in range(301))
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
        "periodType": "week",
        "start": "2025-01-03",
        "end": "2025-01-09",
        "generatedOn": "2025-01-09",
        "scope": "Amina",
        "total": 1250,
        "entries": [
            {"date": "2025-01-03", "trackingNumber": "G240712-2665", "prn": "2270005999848",
             "amount": 1250, "officer": "Amina"},
        ],
    })
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/pdf"
    assert response.content.startswith(b"%PDF")
