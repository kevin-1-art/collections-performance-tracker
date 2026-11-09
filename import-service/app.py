from __future__ import annotations

import csv
import io
import re
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Any
from xml.sax.saxutils import escape
from zoneinfo import ZoneInfo

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, Field
from reportlab.graphics.charts.barcharts import VerticalBarChart
from reportlab.graphics.shapes import Drawing
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

app = FastAPI(title="Collections Import and Reports", version="1.0.0")
MAX_ROWS = 300
MAX_UPLOAD_BYTES = 10 * 1024 * 1024
MAX_AMOUNT = 9_223_372_036_854_775_807
KAMPALA = ZoneInfo("Africa/Kampala")
REQUIRED = ("date", "trackingNumber", "prn", "amount")
ALIASES = {
    "date": "date",
    "collectiondate": "date",
    "trackingnumber": "trackingNumber",
    "trackingno": "trackingNumber",
    "tracking": "trackingNumber",
    "prn": "prn",
    "amount": "amount",
    "amountugx": "amount",
}


def _header_name(value: Any) -> str:
    key = re.sub(r"[^a-z0-9]", "", str(value or "").strip().lower())
    return ALIASES.get(key, key)


def _normalize_date(value: Any) -> str:
    if isinstance(value, datetime):
        parsed = value.date()
    elif isinstance(value, date):
        parsed = value
    else:
        text = str(value or "").strip()
        if not text:
            raise ValueError("date is required")
        parsed = None
        for pattern in ("%Y-%m-%d", "%d/%m/%Y", "%Y/%m/%d"):
            try:
                parsed = datetime.strptime(text, pattern).date()
                break
            except ValueError:
                continue
        if parsed is None:
            raise ValueError("date must use dd/mm/yyyy or yyyy-mm-dd")
    if parsed > datetime.now(KAMPALA).date():
        raise ValueError("date cannot be in the future")
    return parsed.isoformat()


def _excel_value(value: Any) -> Any:
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return value


def _normalize_row(row: dict[str, Any], row_number: int) -> dict[str, Any]:
    result = {_header_name(key): value for key, value in row.items()}
    normalized: dict[str, Any] = {"row": row_number}
    for field in REQUIRED:
        value = result.get(field)
        if value is None or str(value).strip() == "":
            raise ValueError(f"{field} is required")
        normalized[field] = str(value).strip()
    normalized["date"] = _normalize_date(result["date"])
    tracking = normalized["trackingNumber"]
    if re.fullmatch(r"[Gg]\d{6}-\d{4}", tracking) is None:
        raise ValueError("tracking number must match G followed by 6 digits, a hyphen, and 4 digits")
    normalized["trackingNumber"] = tracking.upper()
    if re.fullmatch(r"\d{13}", normalized["prn"]) is None:
        raise ValueError("PRN must contain exactly 13 digits")
    amount_text = normalized["amount"].replace(",", "").replace(" ", "")
    try:
        amount = Decimal(amount_text)
    except (InvalidOperation, ValueError):
        raise ValueError("amount must be a positive whole number of UGX") from None
    if not amount.is_finite() or amount <= 0 or amount != amount.to_integral_value() or amount > MAX_AMOUNT:
        raise ValueError("amount must be a positive whole number of UGX")
    normalized["amount"] = str(int(amount))
    return normalized


def _read_rows(filename: str, data: bytes) -> list[list[Any]]:
    extension = filename.lower().rsplit(".", 1)[-1] if "." in filename else ""
    try:
        if extension == "csv":
            text = data.decode("utf-8-sig")
            return list(csv.reader(io.StringIO(text)))
        if extension == "xlsx":
            from openpyxl import load_workbook

            workbook = load_workbook(io.BytesIO(data), read_only=True, data_only=True)
            try:
                return [list(row) for row in workbook.active.iter_rows(values_only=True)]
            finally:
                workbook.close()
        if extension == "xls":
            import xlrd

            workbook = xlrd.open_workbook(file_contents=data)
            sheet = workbook.sheet_by_index(0)
            rows: list[list[Any]] = []
            for row_index in range(sheet.nrows):
                values = sheet.row_values(row_index)
                normalized = []
                for column_index, cell in enumerate(values):
                    if sheet.cell_type(row_index, column_index) == xlrd.XL_CELL_DATE:
                        cell = xlrd.xldate_as_datetime(cell, workbook.datemode)
                    normalized.append(_excel_value(cell))
                rows.append(normalized)
            return rows
    except (UnicodeDecodeError, csv.Error, ValueError) as exc:
        raise HTTPException(status_code=400, detail=f"Could not read uploaded file: {exc}") from exc
    except Exception as exc:
        raise HTTPException(status_code=400, detail="Invalid or unreadable spreadsheet") from exc
    raise HTTPException(status_code=415, detail="Supported formats are .xls, .xlsx, and .csv")


def _rows_as_records(rows: list[list[Any]]) -> list[tuple[int, dict[str, Any]]]:
    if not rows:
        return []
    first = rows[0]
    normalized_headers = [_header_name(value) for value in first]
    has_header = set(REQUIRED).issubset(normalized_headers)
    if has_header:
        headers = normalized_headers
        start_index = 1
        first_row_number = 2
    else:
        headers = list(REQUIRED)
        start_index = 0
        first_row_number = 1
    records = []
    for index, values in enumerate(rows[start_index:], start=first_row_number):
        if not any(value is not None and str(value).strip() for value in values):
            continue
        records.append((index, dict(zip(headers, values))))
    return records


@app.post("/imports/validate")
async def validate_import(file: UploadFile = File(...)) -> dict[str, Any]:
    filename = file.filename or ""
    data = await file.read(MAX_UPLOAD_BYTES + 1)
    if not data:
        raise HTTPException(status_code=400, detail="Upload is empty")
    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="Maximum upload size is 10 MB")
    raw_rows = _read_rows(filename, data)
    records = _rows_as_records(raw_rows)
    if not records:
        raise HTTPException(status_code=400, detail="No data rows found")
    if len(records) > MAX_ROWS:
        raise HTTPException(status_code=413, detail=f"Maximum import size is {MAX_ROWS} rows")
    accepted: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    tracking_seen: set[str] = set()
    prn_seen: set[str] = set()
    for row_number, row in records:
        try:
            normalized = _normalize_row(row, row_number)
            tracking = normalized["trackingNumber"]
            prn = normalized["prn"]
            if tracking in tracking_seen:
                raise ValueError(f"duplicate tracking number {tracking} in this file")
            if prn in prn_seen:
                raise ValueError(f"duplicate PRN {prn} in this file")
            tracking_seen.add(tracking)
            prn_seen.add(prn)
            accepted.append(normalized)
        except (ValueError, TypeError) as exc:
            skipped.append({"row": row_number, "reason": str(exc)})
    return {"received": len(records), "rows": accepted, "skipped": skipped}


class ReportEntry(BaseModel):
    date: date
    trackingNumber: str = Field(max_length=80)
    prn: str = Field(max_length=80)
    amount: int = Field(gt=0)
    officer: str = ""


class ReportRequest(BaseModel):
    title: str = Field(default="Collections Performance Report", max_length=120)
    period: str = Field(default="Custom", max_length=80)
    periodType: str = Field(default="custom", max_length=20)
    start: date
    end: date
    generatedOn: date
    scope: str = Field(default="All records", max_length=160)
    total: int = Field(default=0, ge=0)
    entries: list[ReportEntry] = Field(default_factory=list, max_length=10000)


def _money(amount: int) -> str:
    return f"UGX {amount:,}"


@app.post("/reports/pdf")
def make_report(report: ReportRequest) -> Response:
    if report.start > report.end:
        raise HTTPException(status_code=422, detail="Report start date must not be after end date")
    day_count = (report.end - report.start).days + 1
    daily: dict[date, list[int]] = {
        date.fromordinal(report.start.toordinal() + index): [0, 0] for index in range(day_count)
    }
    for entry in report.entries:
        current = daily.setdefault(entry.date, [0, 0])
        current[0] += 1
        current[1] += entry.amount
    day_rows = []
    current_day = report.start
    while current_day <= report.end:
        count, total = daily.get(current_day, [0, 0])
        day_rows.append([current_day.isoformat(), count, _money(total)])
        current_day = date.fromordinal(current_day.toordinal() + 1)
    grand_total = sum(entry.amount for entry in report.entries)
    transaction_count = len(report.entries)
    average = (grand_total + day_count // 2) // day_count if day_count else 0
    best = max(daily.items(), key=lambda item: item[1][1]) if daily else None
    lowest = min(daily.items(), key=lambda item: item[1][1]) if daily else None

    buffer = io.BytesIO()
    doc = SimpleDocTemplate(buffer, pagesize=landscape(A4), title=report.title,
                            leftMargin=30, rightMargin=30, topMargin=30, bottomMargin=30)
    styles = getSampleStyleSheet()
    story = [
        Paragraph(report.title, styles["Title"]),
        Spacer(1, 8),
        Paragraph(f"{report.period}: {report.start.isoformat()} – {report.end.isoformat()}", styles["Normal"]),
        Paragraph(f"Scope: {escape(report.scope)} | Generated: {report.generatedOn.isoformat()}", styles["Normal"]),
        Spacer(1, 12),
        Paragraph("Daily collections", styles["Heading2"]),
    ]
    daily_table = Table([["Date", "Transactions", "Collected"], *day_rows],
                        colWidths=[150, 110, 150], repeatRows=1)
    daily_table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#183b56")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("GRID", (0, 0), (-1, -1), 0.3, colors.HexColor("#d5dee6")),
        ("ALIGN", (1, 1), (-1, -1), "RIGHT"),
        ("FONTSIZE", (0, 0), (-1, -1), 8),
    ]))
    story.extend([daily_table, Spacer(1, 10)])
    if report.periodType.lower() == "custom":
        story.append(Paragraph("Friday–Thursday subtotals", styles["Heading2"]))
        weekly_groups: dict[date, list[int]] = {}
        for entry in report.entries:
            week_start = date.fromordinal(entry.date.toordinal() -
                                          (entry.date.weekday() - 4) % 7)
            current = weekly_groups.setdefault(week_start, [0, 0])
            current[0] += 1
            current[1] += entry.amount
        weekly_rows = [["Week beginning", "Transactions", "Collected"]]
        weekly_rows.extend([[start.isoformat(), values[0], _money(values[1])]
                            for start, values in sorted(weekly_groups.items())])
        if len(weekly_rows) == 1:
            weekly_rows.append(["No transactions", 0, _money(0)])
        weekly_table = Table(weekly_rows, colWidths=[150, 110, 150], repeatRows=1)
        weekly_table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#183b56")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("GRID", (0, 0), (-1, -1), 0.3, colors.HexColor("#d5dee6")),
            ("ALIGN", (1, 1), (-1, -1), "RIGHT"),
            ("FONTSIZE", (0, 0), (-1, -1), 8),
        ]))
        story.extend([weekly_table, Spacer(1, 10)])
    story.append(Paragraph("All transactions", styles["Heading2"]))
    transaction_rows = [["Date", "Tracking number", "PRN", "Amount (UGX)", "Officer"]]
    transaction_rows.extend([[entry.date.isoformat(), entry.trackingNumber, entry.prn,
                              f"{entry.amount:,}", entry.officer] for entry in report.entries])
    transactions = Table(transaction_rows, colWidths=[90, 130, 130, 100, 160], repeatRows=1)
    transactions.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#183b56")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("GRID", (0, 0), (-1, -1), 0.3, colors.HexColor("#d5dee6")),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#f2f6f9")]),
        ("FONTSIZE", (0, 0), (-1, -1), 8),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
    ]))
    story.extend([transactions, Spacer(1, 12)])
    best_text = f"{best[0].isoformat()} ({_money(best[1][1])})" if best else "N/A"
    lowest_text = f"{lowest[0].isoformat()} ({_money(lowest[1][1])})" if lowest else "N/A"
    story.append(Paragraph(
        f"Grand total: {_money(grand_total)} | Transactions: {transaction_count} | "
        f"Daily average: {_money(average)} | Best day: {best_text} | Lowest day: {lowest_text}",
        styles["Normal"],
    ))
    chart = Drawing(650, 200)
    bar_chart = VerticalBarChart()
    bar_chart.x = 45
    bar_chart.y = 25
    bar_chart.width = 580
    bar_chart.height = 145
    bar_chart.data = ([daily[date.fromordinal(report.start.toordinal() + i)][1]
                       for i in range(day_count)],)
    bar_chart.categoryAxis.categoryNames = [
        date.fromordinal(report.start.toordinal() + i).strftime("%d %b") for i in range(day_count)
    ]
    bar_chart.categoryAxis.labels.angle = 45
    bar_chart.categoryAxis.labels.fontSize = 7
    bar_chart.valueAxis.valueMin = 0
    bar_chart.valueAxis.labels.fontSize = 7
    bar_chart.bars[0].fillColor = colors.HexColor("#138a83")
    chart.add(bar_chart)
    story.extend([Spacer(1, 8), Paragraph("Daily collections chart", styles["Heading2"]), chart])
    doc.build(story)
    return Response(buffer.getvalue(), media_type="application/pdf",
                    headers={"Content-Disposition": 'attachment; filename="collections-report.pdf"'})


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
