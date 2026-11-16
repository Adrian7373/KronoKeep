/**
 * Renders the verified maintenance PDF (A4) with pdf-lib, plus a vector QR code from `qrcode`.
 * Layout only: every string comes from report.ts formatters and goes through sanitizeWinAnsi, because the
 * standard Helvetica/Courier fonts can only encode WinAnsi. Not node-testable (npm: imports).
 */
import { type PDFFont, type PDFPage, PDFDocument, rgb, StandardFonts } from "npm:pdf-lib@1";
// @deno-types="npm:@types/qrcode@1"
import QRCode from "npm:qrcode@1";
import {
  AD_HOC_TASK,
  categoryLabel,
  formatDate,
  formatDateTime,
  formatInterval,
  formatKm,
  formatLastDone,
  formatLocalDate,
  formatPesos,
  type ReportSnapshot,
  sanitizeWinAnsi,
  wrapText,
} from "./report.ts";

export interface ReportPdfInput {
  reportId: string;
  generatedAt: string;
  contentHash: string;
  snapshot: ReportSnapshot;
  verifyUrl: string;
}

const PAGE_W = 595.28;
const PAGE_H = 841.89;
const MARGIN = 48;
const CONTENT_W = PAGE_W - 2 * MARGIN;
const BOTTOM = MARGIN + 12; // content stops here; the footer sits below
const BAND_H = 72;

const TEAL = rgb(0x0f / 255, 0x6e / 255, 0x6e / 255);
const TEAL_DARK = rgb(0x0a / 255, 0x4b / 255, 0x4b / 255);
const TEAL_TINT = rgb(0.925, 0.961, 0.957);
const BRASS = rgb(0xb0 / 255, 0x8d / 255, 0x57 / 255);
const BRASS_LIGHT = rgb(0.93, 0.86, 0.72);
const INK = rgb(0.11, 0.13, 0.14);
const MUTED = rgb(0.4, 0.43, 0.45);
const RULE = rgb(0.84, 0.86, 0.86);
const ZEBRA = rgb(0.973, 0.98, 0.98);
const WHITE = rgb(1, 1, 1);

interface Fonts {
  regular: PDFFont;
  bold: PDFFont;
  mono: PDFFont;
}

interface Column {
  title: string;
  width: number;
  align?: "left" | "right";
}

const s = sanitizeWinAnsi;

export async function renderReportPdf(input: ReportPdfInput): Promise<Uint8Array> {
  const { reportId, generatedAt, contentHash, snapshot, verifyUrl } = input;
  const doc = await PDFDocument.create();
  const fonts: Fonts = {
    regular: await doc.embedFont(StandardFonts.Helvetica),
    bold: await doc.embedFont(StandardFonts.HelveticaBold),
    mono: await doc.embedFont(StandardFonts.Courier),
  };
  const generated = new Date(generatedAt);
  doc.setTitle(s(`Maintenance record - ${snapshot.asset.name}`));
  doc.setSubject(`KronoKeep verified report ${reportId}`);
  doc.setAuthor("KronoKeep");
  doc.setCreator("KronoKeep generate-report");
  doc.setProducer("KronoKeep");
  doc.setCreationDate(generated);
  doc.setModificationDate(generated);

  const w = new Writer(doc, fonts);

  // --- Page 1 header band ---------------------------------------------------------------------
  w.page.drawRectangle({ x: 0, y: PAGE_H - BAND_H, width: PAGE_W, height: BAND_H, color: TEAL });
  w.page.drawRectangle({ x: 0, y: PAGE_H - BAND_H - 3, width: PAGE_W, height: 3, color: BRASS });
  w.page.drawText("KronoKeep", { x: MARGIN, y: PAGE_H - 40, size: 22, font: fonts.bold, color: WHITE });
  w.page.drawText("Verified maintenance record", {
    x: MARGIN, y: PAGE_H - 58, size: 11, font: fonts.regular, color: BRASS_LIGHT,
  });
  w.textRight(s(`Generated ${formatDateTime(generatedAt)}`), PAGE_W - MARGIN, PAGE_H - 40, 9, fonts.regular, WHITE);
  w.textRight(s(`Report ${reportId}`), PAGE_W - MARGIN, PAGE_H - 54, 8, fonts.mono, BRASS_LIGHT);
  w.y = PAGE_H - BAND_H - 30;

  // --- Asset block ------------------------------------------------------------------------------
  const asset = snapshot.asset;
  for (const line of wrapText(s(asset.name), CONTENT_W, (t) => fonts.bold.widthOfTextAtSize(t, 18))) {
    w.page.drawText(line, { x: MARGIN, y: w.y, size: 18, font: fonts.bold, color: INK });
    w.y -= 22;
  }
  w.y -= 4;
  const makeModel = [asset.brand, asset.model].filter((x) => x).join(" ");
  const facts: Array<[string, string]> = [
    ["Category", categoryLabel(asset.category)],
    ["Brand / model", makeModel || "-"],
    ["Serial no.", asset.serial_no ?? "-"],
    ["Purchase date", asset.purchase_date ? formatLocalDate(asset.purchase_date) : "-"],
  ];
  if (asset.category === "vehicle" || asset.current_odometer_km != null) {
    facts.push(["Current odometer", asset.current_odometer_km == null ? "-" : formatKm(asset.current_odometer_km)]);
  }
  const labelW = 110;
  for (const [label, value] of facts) {
    w.page.drawText(s(label.toUpperCase()), { x: MARGIN, y: w.y, size: 7.5, font: fonts.bold, color: MUTED });
    const lines = wrapText(s(value), CONTENT_W - labelW, (t) => fonts.regular.widthOfTextAtSize(t, 10));
    for (const line of lines) {
      w.page.drawText(line, { x: MARGIN + labelW, y: w.y, size: 10, font: fonts.regular, color: INK });
      w.y -= 14;
    }
    w.y -= 2;
  }
  w.y -= 10;

  // --- Verification box -------------------------------------------------------------------------
  drawVerificationBox(w, { reportId, generatedAt, contentHash, verifyUrl });

  // --- Service history --------------------------------------------------------------------------
  w.heading("Service history");
  const logColumns: Column[] = [
    { title: "Date", width: 60 },
    { title: "Task", width: 95 },
    { title: "Odometer", width: 58, align: "right" },
    { title: "Shop", width: 82 },
    { title: "Cost", width: 70, align: "right" },
    { title: "Notes", width: CONTENT_W - 365 },
  ];
  let total = 0;
  const logRows = snapshot.service_logs.map((l) => {
    if (l.cost_centavos != null) total += l.cost_centavos;
    return [
      formatDate(l.performed_at),
      l.task ?? AD_HOC_TASK,
      l.odometer_km == null ? "-" : formatKm(l.odometer_km),
      l.shop ?? "-",
      l.cost_centavos == null ? "-" : formatPesos(l.cost_centavos),
      l.notes ?? "",
    ];
  });
  w.table("Service history", logColumns, logRows);
  w.ensure(28);
  w.y -= 14;
  const n = snapshot.service_logs.length;
  w.textRight(
    s(`${n} ${n === 1 ? "entry" : "entries"}  ·  Total spent ${formatPesos(total)}`),
    PAGE_W - MARGIN - 4, w.y, 9.5, fonts.bold, INK, // lines up with the right-aligned Cost column
  );
  w.y -= 26;

  // --- Schedules --------------------------------------------------------------------------------
  w.heading("Maintenance schedules");
  if (snapshot.schedules.length === 0) {
    w.paragraph("No maintenance schedules on record.", 9, MUTED);
  } else {
    w.table("Maintenance schedules", [
      { title: "Task", width: 190 },
      { title: "Interval", width: 160 },
      { title: "Last done", width: CONTENT_W - 350 },
    ], snapshot.schedules.map((sc) => [
      sc.task,
      formatInterval(sc.interval_days, sc.interval_km),
      formatLastDone(sc.last_done_at, sc.last_done_km),
    ]));
  }
  w.y -= 18;
  w.paragraph(
    "This record was compiled by KronoKeep from entries made by the owner. The QR code confirms that this " +
      "document matches the record held on the KronoKeep server and has not been revoked; it does not " +
      "certify the quality of the work itself. Prices paid for the asset and predicted due dates are not included.",
    7.5,
    MUTED,
  );

  // --- Footers ------------------------------------------------------------------------------------
  const pages = doc.getPages();
  pages.forEach((page, i) => {
    page.drawLine({
      start: { x: MARGIN, y: MARGIN - 6 }, end: { x: PAGE_W - MARGIN, y: MARGIN - 6 }, thickness: 0.5, color: RULE,
    });
    const text = s(`Report ${reportId}  ·  SHA-256 ${contentHash.slice(0, 16)}…  ·  Page ${i + 1} of ${pages.length}`);
    const width = fonts.regular.widthOfTextAtSize(text, 7);
    page.drawText(text, { x: (PAGE_W - width) / 2, y: MARGIN - 20, size: 7, font: fonts.regular, color: MUTED });
  });

  return await doc.save();
}

function drawVerificationBox(
  w: Writer,
  v: { reportId: string; generatedAt: string; contentHash: string; verifyUrl: string },
) {
  const { fonts } = w;
  const pad = 12;
  const qrSize = 104;
  const textX = MARGIN + pad + qrSize + 16;
  const textW = PAGE_W - MARGIN - pad - textX;

  // Lay out the text first so the box can be sized before anything is drawn over it.
  type Line = { text: string; size: number; font: PDFFont; color: ReturnType<typeof rgb>; gap: number };
  const lines: Line[] = [];
  const push = (text: string, size: number, font: PDFFont, color: ReturnType<typeof rgb>, gap = 0) => {
    for (const t of wrapText(s(text), textW, (x) => font.widthOfTextAtSize(x, size))) {
      lines.push({ text: t, size, font, color, gap: 0 });
    }
    lines[lines.length - 1].gap = gap;
  };
  push("Verify this record", 11, fonts.bold, TEAL_DARK, 4);
  push(
    "Scan the QR code or open the link below. A VALID result means this PDF matches the record held by " +
      "KronoKeep and has not been revoked by the owner.",
    8, fonts.regular, MUTED, 6,
  );
  push("REPORT ID", 6.5, fonts.bold, MUTED);
  push(v.reportId, 8, fonts.mono, INK, 4);
  push("GENERATED", 6.5, fonts.bold, MUTED);
  push(formatDateTime(v.generatedAt), 8, fonts.regular, INK, 4);
  push("SHA-256 OF RECORD", 6.5, fonts.bold, MUTED);
  push(v.contentHash, 7.5, fonts.mono, INK, 4);
  push(v.verifyUrl, 6.5, fonts.mono, TEAL);
  const textH = lines.reduce((h, l) => h + l.size * 1.3 + l.gap, 0);
  const boxH = Math.max(qrSize, textH) + 2 * pad;

  w.ensure(boxH + 10);
  const top = w.y;
  w.page.drawRectangle({
    x: MARGIN, y: top - boxH, width: CONTENT_W, height: boxH,
    color: TEAL_TINT, borderColor: TEAL, borderWidth: 1,
  });
  w.page.drawRectangle({ x: MARGIN, y: top - boxH, width: 4, height: boxH, color: BRASS });
  drawQr(w.page, v.verifyUrl, MARGIN + pad + 2, top - pad - qrSize, qrSize);

  let y = top - pad;
  for (const l of lines) {
    y -= l.size;
    w.page.drawText(l.text, { x: textX, y, size: l.size, font: l.font, color: l.color });
    y -= l.size * 0.3 + l.gap;
  }
  w.y = top - boxH - 26;
}

/** Vector QR: white quiet zone, then one rectangle per horizontal run of dark modules. */
function drawQr(page: PDFPage, text: string, x: number, y: number, size: number) {
  const modules = QRCode.create(text, { errorCorrectionLevel: "M" }).modules;
  const n = modules.size;
  const quiet = 4;
  const cell = size / (n + 2 * quiet);
  page.drawRectangle({ x, y, width: size, height: size, color: WHITE });
  for (let row = 0; row < n; row++) {
    let col = 0;
    while (col < n) {
      if (!modules.get(row, col)) {
        col++;
        continue;
      }
      const start = col;
      while (col < n && modules.get(row, col)) col++;
      page.drawRectangle({
        x: x + (quiet + start) * cell,
        y: y + size - (quiet + row + 1) * cell,
        width: (col - start) * cell,
        height: cell,
        color: INK,
      });
    }
  }
}

/** Cursor over the current page, with pagination. */
class Writer {
  doc: PDFDocument;
  fonts: Fonts;
  page: PDFPage;
  y: number;

  constructor(doc: PDFDocument, fonts: Fonts) {
    this.doc = doc;
    this.fonts = fonts;
    this.page = doc.addPage([PAGE_W, PAGE_H]);
    this.y = PAGE_H - MARGIN;
  }

  /** Top of the content area on continuation pages. */
  static readonly CONTINUED_TOP = PAGE_H - MARGIN - 22;

  newPage() {
    this.page = this.doc.addPage([PAGE_W, PAGE_H]);
    this.page.drawText("KronoKeep", { x: MARGIN, y: PAGE_H - MARGIN, size: 10, font: this.fonts.bold, color: TEAL });
    this.page.drawText("Verified maintenance record", {
      x: MARGIN + this.fonts.bold.widthOfTextAtSize("KronoKeep", 10) + 8,
      y: PAGE_H - MARGIN, size: 9, font: this.fonts.regular, color: MUTED,
    });
    this.page.drawRectangle({ x: MARGIN, y: PAGE_H - MARGIN - 8, width: CONTENT_W, height: 1.5, color: BRASS });
    this.y = Writer.CONTINUED_TOP;
  }

  ensure(height: number) {
    if (this.y - height < BOTTOM) this.newPage();
  }

  textRight(text: string, right: number, y: number, size: number, font: PDFFont, color: ReturnType<typeof rgb>) {
    this.page.drawText(text, { x: right - font.widthOfTextAtSize(text, size), y, size, font, color });
  }

  heading(title: string) {
    this.ensure(60); // keep a heading with at least a table header and a row
    this.page.drawText(s(title), { x: MARGIN, y: this.y, size: 12.5, font: this.fonts.bold, color: TEAL_DARK });
    this.y -= 8;
    this.page.drawRectangle({ x: MARGIN, y: this.y, width: CONTENT_W, height: 0.5, color: RULE });
    this.page.drawRectangle({ x: MARGIN, y: this.y - 0.5, width: 36, height: 2, color: BRASS });
    this.y -= 12;
  }

  paragraph(text: string, size: number, color: ReturnType<typeof rgb>) {
    const lineH = size * 1.35;
    for (const line of wrapText(s(text), CONTENT_W, (t) => this.fonts.regular.widthOfTextAtSize(t, size))) {
      this.ensure(lineH);
      this.y -= size;
      this.page.drawText(line, { x: MARGIN, y: this.y, size, font: this.fonts.regular, color });
      this.y -= lineH - size;
    }
  }

  /** Table with a header repeated on every page; rows taller than a page are split across pages. */
  table(title: string, columns: Column[], rows: string[][]) {
    const size = 8;
    const lineH = 10;
    const padX = 4;
    const padY = 4;
    const headerH = 18;
    const font = this.fonts.regular;

    const drawHeader = () => {
      this.page.drawRectangle({ x: MARGIN, y: this.y - headerH, width: CONTENT_W, height: headerH, color: TEAL_TINT });
      let x = MARGIN;
      for (const c of columns) {
        const t = s(c.title.toUpperCase());
        const tx = c.align === "right" ? x + c.width - padX - this.fonts.bold.widthOfTextAtSize(t, 7) : x + padX;
        this.page.drawText(t, { x: tx, y: this.y - headerH + 6, size: 7, font: this.fonts.bold, color: TEAL_DARK });
        x += c.width;
      }
      this.y -= headerH;
    };
    const continuePage = () => {
      this.newPage();
      this.page.drawText(s(`${title} (continued)`), {
        x: MARGIN, y: this.y, size: 9, font: this.fonts.bold, color: MUTED,
      });
      this.y -= 10;
      drawHeader();
    };

    this.ensure(headerH + lineH + 2 * padY);
    drawHeader();
    const pageCapacity = Math.floor((Writer.CONTINUED_TOP - 10 - headerH - BOTTOM - 2 * padY) / lineH);

    rows.forEach((row, index) => {
      const cells = row.map((text, i) =>
        wrapText(s(text), columns[i].width - 2 * padX, (t) => font.widthOfTextAtSize(t, size))
      );
      const total = Math.max(...cells.map((c) => c.length));
      let start = 0;
      while (start < total) {
        const remaining = total - start;
        const avail = Math.floor((this.y - BOTTOM - 2 * padY) / lineH);
        let take: number;
        if (avail >= remaining) {
          take = remaining;
        } else if ((start === 0 && remaining <= pageCapacity) || avail < 1) {
          continuePage(); // move the whole row (or the rest of it) to a fresh page
          continue;
        } else {
          take = avail;
        }
        const h = take * lineH + 2 * padY;
        if (index % 2 === 1) {
          this.page.drawRectangle({ x: MARGIN, y: this.y - h, width: CONTENT_W, height: h, color: ZEBRA });
        }
        let x = MARGIN;
        columns.forEach((c, i) => {
          cells[i].slice(start, start + take).forEach((line, j) => {
            const ly = this.y - padY - size - j * lineH + 1;
            const lx = c.align === "right" ? x + c.width - padX - font.widthOfTextAtSize(line, size) : x + padX;
            this.page.drawText(line, { x: lx, y: ly, size, font, color: INK });
          });
          x += c.width;
        });
        this.y -= h;
        this.page.drawRectangle({ x: MARGIN, y: this.y, width: CONTENT_W, height: 0.5, color: RULE });
        start += take;
        if (start < total) continuePage();
      }
    });
  }
}
