# MAGESH M PDF EDITOR

An original, offline-first **Android PDF Studio** built in pure Java with an **Obsidian & Emerald Workbench** UI—designed from scratch with its own identity rather than mimicking Acrobat, Foxit, PDFelement, Smallpdf, iLovePDF, or PDF-XChange.

## Direct On-Device Capabilities

- **Merge & Split**: Combine multiple PDFs or extract custom page ranges
- **Rotate, Delete & Crop**: Rotate pages (90°/180°), remove pages, or trim margins
- **Extract Text & Export HTML**: Pull embedded text and export to `.txt` or `.html`
- **OCR (ML Kit)**: Recognize text from scanned PDF pages offline on-device
- **Fill & Create Forms**: Edit existing AcroForm fields or add new fillable text fields
- **E-Signature & Annotations**: Freehand ink drawing, amber highlights, permanent black redactions, and signature pad
- **Watermark, Headers, Footers & Bates**: Stamp diagonal watermarks, page numbers, and Bates prefixes
- **Conversion**: Images (JPG/PNG) → PDF and PDF page → high-res PNG
- **Security & Optimization**: 128-bit password encryption with print/copy/edit permissions, bookmarks, PDF text diff comparison, and file optimization

## Build APK via GitHub Actions

Push this repository to GitHub (e.g., `main` branch). The included `.github/workflows/build.yml` workflow automatically compiles `app-debug.apk` using **JDK 17** and **Gradle 8.7** and uploads it as a downloadable artifact (`Magesh-M-PDF-Editor-Debug-APK`).
