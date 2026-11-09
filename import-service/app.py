from __future__ import annotations

import csv
import io
from itertools import islice
import re
from datetime import date, datetime, time
from decimal import Decimal, InvalidOperation
from typing import Any
from zoneinfo import ZoneInfo

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, Field
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

app = FastAPI(title="Collections Import and Reports", version="1.0.0")
KAMPALA = ZoneInfo("Africa/Kampala")
MAX_ROWS = 300
MAX_UPLOAD_BYTES = 10 * 1024 * 1024
REQUIRED = ("clientName", "trackingNumber", "prn", "amount", "collectedAt")
ALIASES = {
    "clientname": "clientName", "client_name": "clientName", "client": "clientName",
    "trackingnumber": "trackingNumber", "tracking_number": "trackingNumber", "tracking": "trackingNumber",
    "prn": "prn", "amount": "amount", "collectedat": "collectedAt",
    "collected_at": "collectedAt", "date": "collectedAt", "receipt": "receiptNumber",
    "receiptnumber": "receiptNumber", "receipt_number": "receiptNumber", "notes": "notes",
}


def _header_name(value: Any) -> str:
    key = re.sub(r"\s+", "_", str(value or "").strip()).lower()
    return ALIASES.get(key.replace("_", ""), ALIASES.get(key, key))


def _normalize_datetime(value: Any) -> str:
    if isinstance(value, datetime):
        parsed = value
    elif isinstance(value, date):
        parsed = datetime.combine(value, time.min)
    else:
        text = str(value or "").strip()
        if not text:
            raise ValueError("collectedAt is required")
        try:
            parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        except ValueError:
            for pattern in ("%Y-%m-%d", "%d/%m/%Y", "%Y/%m/%d"):
                try:
                    parsed = datetime.strptime(text, pattern)
                    break
                except ValueError:
                    continue
            else:
                raise ValueError("collectedAt must be an ISO timestamp or date") from None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=KAMPALA)
    return parsed.astimezone(ZoneInfo("UTC")).isoformat().replace("+00:00", "Z")


def _normalize_row(row: dict[str, Any], row_number: int) -> dict[str, Any]:
    result = {_header_name(key): value for key, value in row.items()}
    normalized: dict[str, Any] = {"row": row_number}
    for field in REQUIRED:
        value = result.get(field)
        if value is None or str(value).strip() == "":
            raise ValueError(f"{field} is required")
        normalized[field] = str(value).strip()
    if len(normalized["clientName"]) > 160:
        raise ValueError("clientName must be at most 160 characters")
    if len(normalized["trackingNumber"]) > 80 or len(normalized["prn"]) > 80:
        raise ValueError("trackingNumber and prn must be at most 80 characters")
    try:
        amount = Decimal(normalized["amount"])
    except (InvalidOperation, ValueError):
        raise ValueError("amount must be a positive number with at most 2 decimals") from None
    if not amount.is_finite() or amount <= 0 or amount.as_tuple().exponent < -2 or amount >= Decimal("1000000000000"):
        raise ValueError("amount must be a positive number with at most 2 decimals")
    normalized["amount"] = format(amount, ".2f")
    normalized["collectedAt"] = _normalize_datetime(result["collectedAt"])
    for field in ("receiptNumber", "notes"):
        value = result.get(field)
        normalized[field] = str(value).strip() if value is not None and str(value).strip() else None
    if normalized["receiptNumber"] and len(normalized["receiptNumber"]) > 80:
        raise ValueError("receiptNumber must be at most 80 characters")
    if normalized["notes"] and len(normalized["notes"]) > 1000:
        raise ValueError("notes must be at most 1000 characters")
    return normalized


def _read_rows(filename: str, data: bytes) -> list[dict[str, Any]]:
    extension = filename.lower().rsplit(".", 1)[-1] if "." in filename else ""
    try:
        if extension == "csv":
            text = data.decode("utf-8-sig")
            return list(islice(csv.DictReader(io.StringIO(text)), MAX_ROWS + 1))
        if extension == "xlsx":
            from openpyxl import load_workbook

            workbook = load_workbook(io.BytesIO(data), read_only=True, data_only=True)
            try:
                values = iter(workbook.active.iter_rows(values_only=True))
                headers = next(values, None)
                if not headers:
                    return []
                return [dict(zip(headers, row)) for row in islice(values, MAX_ROWS + 1) if any(v is not None for v in row)]
            finally:
                workbook.close()
        if extension == "xls":
            import xlrd

            workbook = xlrd.open_workbook(file_contents=data)
            sheet = workbook.sheet_by_index(0)
            if sheet.nrows == 0:
                return []
            headers = sheet.row_values(0)
            rows = []
            for row_index in range(1, min(sheet.nrows, MAX_ROWS + 2)):
                values = sheet.row_values(row_index)
                normalized = {}
                for header, cell in zip(headers, values):
                    if _header_name(header) == "collectedAt" and isinstance(cell, (int, float)):
                        try:
                            cell = xlrd.xldate_as_datetime(cell, workbook.datemode)
                        except Exception:
                            pass
                    elif isinstance(cell, float) and cell.is_integer():
                        cell = int(cell)
                    normalized[header] = cell
                rows.append(normalized)
            return rows
    except (UnicodeDecodeError, csv.Error, ValueError) as exc:
        raise HTTPException(status_code=400, detail=f"Could not read uploaded file: {exc}") from exc
    except Exception as exc:
        raise HTTPException(status_code=400, detail="Invalid or unreadable spreadsheet") from exc
    raise HTTPException(status_code=415, detail="Supported formats are .csv, .xlsx, and .xls")


@app.post("/imports/validate")
async def validate_import(file: UploadFile = File(...)) -> dict[str, Any]:
    filename = file.filename or ""
    data = await file.read(MAX_UPLOAD_BYTES + 1)
    if not data:
        raise HTTPException(status_code=400, detail="Upload is empty")
    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="Maximum upload size is 10 MB")
    raw_rows = _read_rows(filename, data)
    if not raw_rows:
        raise HTTPException(status_code=400, detail="No data rows found")
    if len(raw_rows) > MAX_ROWS:
        raise HTTPException(status_code=413, detail=f"Maximum import size is {MAX_ROWS} rows")
    if raw_rows and not all(_header_name(h) in {*REQUIRED, "receiptNumber", "notes"} for h in raw_rows[0]):
        present = {_header_name(h) for h in raw_rows[0]}
        missing = [field for field in REQUIRED if field not in present]
        if missing:
            raise HTTPException(status_code=400, detail=f"Missing required columns: {', '.join(missing)}")
    rows, skipped = [], []
    for index, row in enumerate(raw_rows, start=2):
        try:
            rows.append(_normalize_row(row, index))
        except (ValueError, TypeError) as exc:
            skipped.append({"row": index, "reason": str(exc)})
    return {"received": len(raw_rows), "rows": rows, "skipped": skipped}


class ReportEntry(BaseModel):
    clientName: str = Field(max_length=160)
    trackingNumber: str = Field(max_length=80)
    prn: str = Field(max_length=80)
    amount: Decimal
    collectedAt: str
    createdBy: str = ""


class ReportRequest(BaseModel):
    title: str = Field(default="Collections Performance Report", max_length=120)
    period: str = Field(default="Custom", max_length=80)
    start: str = ""
    end: str = ""
    total: Decimal = Decimal("0")
    entries: list[ReportEntry] = Field(default_factory=list, max_length=10000)


@app.post("/reports/pdf")
def make_report(report: ReportRequest) -> Response:
    buffer = io.BytesIO()
    doc = SimpleDocTemplate(buffer, pagesize=landscape(A4), title=report.title)
    styles = getSampleStyleSheet()
    story = [Paragraph(report.title, styles["Title"]), Spacer(1, 8),
             Paragraph(f"{report.period}: {report.start} — {report.end}", styles["Normal"]),
             Paragraph(f"Records: {len(report.entries)} | Total collected: {report.total}", styles["Normal"]),
             Spacer(1, 12)]
    data = [["Client", "Tracking number", "PRN", "Amount", "Collected at", "Officer"]]
    data.extend([[e.clientName, e.trackingNumber, e.prn, f"{e.amount:,.2f}", e.collectedAt, e.createdBy]
                 for e in report.entries])
    table = Table(data, repeatRows=1, colWidths=[145, 105, 90, 85, 145, 100])
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#183b56")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("GRID", (0, 0), (-1, -1), 0.3, colors.HexColor("#d5dee6")),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#f2f6f9")]),
        ("FONTSIZE", (0, 0), (-1, -1), 8),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
    ]))
    story.append(table)
    doc.build(story)
    return Response(buffer.getvalue(), media_type="application/pdf",
                    headers={"Content-Disposition": 'attachment; filename="collections-report.pdf"'})


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
