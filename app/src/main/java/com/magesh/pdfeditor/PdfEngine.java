package com.magesh.pdfeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.multipdf.Splitter;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PdfEngine {

    public static void init(Context context) {
        PDFBoxResourceLoader.init(context.getApplicationContext());
    }

    public static void mergePdfs(Context ctx, List<Uri> uris, File outFile) throws Exception {
        PDFMergerUtility merger = new PDFMergerUtility();
        merger.setDestinationFileName(outFile.getAbsolutePath());
        List<InputStream> streams = new ArrayList<>();
        try {
            for (Uri uri : uris) {
                InputStream is = ctx.getContentResolver().openInputStream(uri);
                if (is != null) {
                    streams.add(is);
                    merger.addSource(is);
                }
            }
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly());
        } finally {
            for (InputStream is : streams) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static void splitPdf(Context ctx, Uri uri, int startPage1Based, int endPage1Based, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            int total = doc.getNumberOfPages();
            int start = Math.max(1, startPage1Based);
            int end = Math.min(total, endPage1Based);
            if (start > end) throw new IllegalArgumentException("Invalid page range.");

            Splitter splitter = new Splitter();
            splitter.setStartPage(start);
            splitter.setEndPage(end);
            splitter.setSplitAtPage(end - start + 1);
            List<PDDocument> parts = splitter.split(doc);
            if (!parts.isEmpty()) {
                parts.get(0).save(outFile);
            }
            for (PDDocument p : parts) p.close();
        }
    }

    public static void rotatePages(Context ctx, Uri uri, int pageIndex0Based, int degrees, boolean allPages, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            if (allPages) {
                for (PDPage page : doc.getPages()) {
                    page.setRotation((page.getRotation() + degrees) % 360);
                }
            } else if (pageIndex0Based >= 0 && pageIndex0Based < doc.getNumberOfPages()) {
                PDPage page = doc.getPage(pageIndex0Based);
                page.setRotation((page.getRotation() + degrees) % 360);
            }
            doc.save(outFile);
        }
    }

    public static void deletePage(Context ctx, Uri uri, int pageIndex0Based, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            if (doc.getNumberOfPages() <= 1) {
                throw new IllegalStateException("Cannot delete the only remaining page.");
            }
            doc.removePage(pageIndex0Based);
            doc.save(outFile);
        }
    }

    public static void cropMargins(Context ctx, Uri uri, float marginPt, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            for (PDPage page : doc.getPages()) {
                PDRectangle box = page.getCropBox();
                float w = box.getWidth() - 2 * marginPt;
                float h = box.getHeight() - 2 * marginPt;
                if (w > 20 && h > 20) {
                    page.setCropBox(new PDRectangle(box.getLowerLeftX() + marginPt, box.getLowerLeftY() + marginPt, w, h));
                }
            }
            doc.save(outFile);
        }
    }

    public static String extractText(Context ctx, Uri uri) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            return text == null || text.trim().isEmpty()
                    ? "(No embedded text found. Try OCR Scan for scanned PDFs.)"
                    : text.trim();
        }
    }

    public static void addWatermark(Context ctx, Uri uri, String watermarkText, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.28f);

            for (PDPage page : doc.getPages()) {
                PDRectangle rect = page.getMediaBox();
                try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setGraphicsStateParameters(gs);
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 44);
                    cs.setNonStrokingColor(16, 185, 129);
                    cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(45), rect.getWidth() * 0.2f, rect.getHeight() * 0.35f));
                    cs.showText(watermarkText);
                    cs.endText();
                }
            }
            doc.save(outFile);
        }
    }

    public static void addHeaderFooterBates(Context ctx, Uri uri, String header, String footer, String batesPrefix, boolean pageNumbers, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            int total = doc.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDPage page = doc.getPage(i);
                PDRectangle rect = page.getMediaBox();
                try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setFont(PDType1Font.HELVETICA, 10);
                    cs.setNonStrokingColor(40, 40, 40);
                    if (header != null && !header.isEmpty()) {
                        cs.beginText();
                        cs.newLineAtOffset(36, rect.getHeight() - 28);
                        cs.showText(header);
                        cs.endText();
                    }
                    StringBuilder bottom = new StringBuilder();
                    if (footer != null && !footer.isEmpty()) bottom.append(footer).append("   ");
                    if (pageNumbers) bottom.append("Page ").append(i + 1).append(" of ").append(total).append("   ");
                    if (batesPrefix != null && !batesPrefix.isEmpty()) {
                        bottom.append(String.format("%s%04d", batesPrefix, i + 1));
                    }
                    if (bottom.length() > 0) {
                        cs.beginText();
                        cs.newLineAtOffset(36, 22);
                        cs.showText(bottom.toString().trim());
                        cs.endText();
                    }
                }
            }
            doc.save(outFile);
        }
    }

    public static void addTextOverlay(Context ctx, Uri uri, int pageIndex, String text, float xPct, float yPct, float fontSize, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            PDPage page = doc.getPage(pageIndex);
            PDRectangle rect = page.getMediaBox();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, fontSize);
                cs.setNonStrokingColor(16, 185, 129);
                cs.newLineAtOffset(rect.getWidth() * xPct, rect.getHeight() * (1f - yPct));
                cs.showText(text);
                cs.endText();
            }
            doc.save(outFile);
        }
    }

    public static void flattenOverlayBitmap(Context ctx, Uri uri, int pageIndex, Bitmap overlay, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            PDPage page = doc.getPage(pageIndex);
            PDRectangle rect = page.getMediaBox();
            PDImageXObject pdImage = LosslessFactory.createFromImage(doc, overlay);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.drawImage(pdImage, 0, 0, rect.getWidth(), rect.getHeight());
            }
            doc.save(outFile);
        }
    }

    public static void imagesToPdf(Context ctx, List<Uri> imageUris, File outFile) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (Uri imgUri : imageUris) {
                try (InputStream is = ctx.getContentResolver().openInputStream(imgUri)) {
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    if (bmp == null) continue;
                    PDPage page = new PDPage(new PDRectangle(bmp.getWidth(), bmp.getHeight()));
                    doc.addPage(page);
                    PDImageXObject pdImg = LosslessFactory.createFromImage(doc, bmp);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.drawImage(pdImg, 0, 0, bmp.getWidth(), bmp.getHeight());
                    }
                    bmp.recycle();
                }
            }
            doc.save(outFile);
        }
    }

    public static void exportPageAsPng(Context ctx, Uri uri, int pageIndex, File outFile) throws Exception {
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
             PdfRenderer renderer = new PdfRenderer(pfd);
             PdfRenderer.Page page = renderer.openPage(pageIndex)) {
            Bitmap bmp = Bitmap.createBitmap(page.getWidth() * 2, page.getHeight() * 2, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(android.graphics.Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bmp.recycle();
        }
    }

    public static void encryptPdf(Context ctx, Uri uri, String userPassword, String ownerPassword, boolean allowPrint, boolean allowCopy, boolean allowModify, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            AccessPermission ap = new AccessPermission();
            ap.setCanPrint(allowPrint);
            ap.setCanExtractContent(allowCopy);
            ap.setCanModify(allowModify);
            StandardProtectionPolicy spp = new StandardProtectionPolicy(ownerPassword, userPassword, ap);
            spp.setEncryptionKeyLength(128);
            doc.protect(spp);
            doc.save(outFile);
        }
    }

    public static Map<String, String> getFormFields(Context ctx, Uri uri) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form != null) {
                for (PDField field : form.getFields()) {
                    map.put(field.getFullyQualifiedName(), field.getValueAsString());
                }
            }
        }
        return map;
    }

    public static void fillFormFields(Context ctx, Uri uri, Map<String, String> values, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form != null) {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    PDField f = form.getField(e.getKey());
                    if (f != null) f.setValue(e.getValue());
                }
            }
            doc.save(outFile);
        }
    }

    public static void addFillableTextField(Context ctx, Uri uri, int pageIndex, String fieldName, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form == null) {
                form = new PDAcroForm(doc);
                doc.getDocumentCatalog().setAcroForm(form);
            }
            PDTextField textField = new PDTextField(form);
            textField.setPartialName(fieldName);
            textField.getWidgets().get(0).setRectangle(new PDRectangle(72, 620, 240, 28));
            textField.getWidgets().get(0).setPage(doc.getPage(pageIndex));
            doc.getPage(pageIndex).getAnnotations().add(textField.getWidgets().get(0));
            form.getFields().add(textField);
            doc.save(outFile);
        }
    }

    public static List<String> getBookmarks(Context ctx, Uri uri) throws Exception {
        List<String> list = new ArrayList<>();
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                PDOutlineItem current = outline.getFirstChild();
                while (current != null) {
                    list.add(current.getTitle());
                    current = current.getNextSibling();
                }
            }
        }
        return list;
    }

    public static void addBookmark(Context ctx, Uri uri, int pageIndex, String title, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline == null) {
                outline = new PDDocumentOutline();
                doc.getDocumentCatalog().setDocumentOutline(outline);
            }
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(title);
            item.setDestination(doc.getPage(pageIndex));
            outline.addLast(item);
            doc.save(outFile);
        }
    }

    public static void compressPdf(Context ctx, Uri uri, File outFile) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is, MemoryUsageSetting.setupTempFileOnly())) {
            doc.setAllSecurityToBeRemoved(false);
            doc.save(outFile);
        }
    }

    public static String comparePdfs(Context ctx, Uri uriA, Uri uriB) throws Exception {
        String textA = extractText(ctx, uriA);
        String textB = extractText(ctx, uriB);
        String[] linesA = textA.split("\\r?\\n");
        String[] linesB = textB.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        int max = Math.max(linesA.length, linesB.length);
        int diffs = 0;
        for (int i = 0; i < max; i++) {
            String a = i < linesA.length ? linesA[i].trim() : "";
            String b = i < linesB.length ? linesB[i].trim() : "";
            if (!a.equals(b)) {
                diffs++;
                sb.append("Line ").append(i + 1).append(":\n  - A: ").append(a).append("\n  + B: ").append(b).append("\n\n");
                if (diffs >= 50) {
                    sb.append("... (truncated after 50 differences)");
                    break;
                }
            }
        }
        if (diffs == 0) return "Both PDFs have identical extracted text.";
        return "Found " + diffs + " differing line(s):\n\n" + sb;
    }
}
