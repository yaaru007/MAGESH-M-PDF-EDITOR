package com.magesh.pdfeditor;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView tvFileStatus, tvPageIndicator, tvEmptyCanvas;
    private ImageView ivPdfPage;
    private AnnotationOverlayView annotationOverlay;
    private ProgressBar progressBar;
    private View paneWorkbench, paneTools;
    private MaterialButton btnTabWorkbench, btnTabTools;

    private Uri currentPdfUri;
    private int currentPageIndex = 0;
    private int totalPages = 0;
    private Bitmap currentBitmap;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<Intent> openPdfLauncher;
    private ActivityResultLauncher<Intent> mergePdfsLauncher;
    private ActivityResultLauncher<Intent> imagesToPdfLauncher;
    private ActivityResultLauncher<Intent> comparePdfLauncher;
    private ActivityResultLauncher<Intent> wordToPdfLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        PdfEngine.init(this);

        bindViews();
        registerLaunchers();
        setupToolHub();
        setupListeners();

        Intent incoming = getIntent();
        if (incoming != null && Intent.ACTION_VIEW.equals(incoming.getAction()) && incoming.getData() != null) {
            loadPdf(incoming.getData());
        }
    }

    private void bindViews() {
        tvFileStatus = findViewById(R.id.tvFileStatus);
        tvPageIndicator = findViewById(R.id.tvPageIndicator);
        tvEmptyCanvas = findViewById(R.id.tvEmptyCanvas);
        ivPdfPage = findViewById(R.id.ivPdfPage);
        annotationOverlay = findViewById(R.id.annotationOverlay);
        progressBar = findViewById(R.id.progressBar);
        paneWorkbench = findViewById(R.id.paneWorkbench);
        paneTools = findViewById(R.id.paneTools);
        btnTabWorkbench = findViewById(R.id.btnTabWorkbench);
        btnTabTools = findViewById(R.id.btnTabTools);
    }

    private void registerLaunchers() {
        openPdfLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null && res.getData().getData() != null) {
                loadPdf(res.getData().getData());
            }
        });

        mergePdfsLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null) {
                List<Uri> uris = extractMultipleUris(res.getData());
                if (uris.size() < 2) {
                    toast("Select at least 2 PDFs to merge.");
                    return;
                }
                runTask("Merging " + uris.size() + " PDFs…", () -> {
                    File out = createOutputFile("Merged_", ".pdf");
                    PdfEngine.mergePdfs(this, uris, out);
                    return out;
                });
            }
        });

        imagesToPdfLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null) {
                List<Uri> uris = extractMultipleUris(res.getData());
                if (uris.isEmpty()) return;
                runTask("Converting images to PDF…", () -> {
                    File out = createOutputFile("ImagesToPdf_", ".pdf");
                    PdfEngine.imagesToPdf(this, uris, out);
                    return out;
                });
            }
        });

        wordToPdfLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null && res.getData().getData() != null) {
                Uri docxUri = res.getData().getData();
                runTask("Converting Word (.docx) to PDF…", () -> {
                    File out = createOutputFile("WordToPdf_", ".pdf");
                    PdfEngine.wordToPdf(this, docxUri, out);
                    return out;
                });
            }
        });

        comparePdfLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null && res.getData().getData() != null) {
                Uri secondUri = res.getData().getData();
                if (currentPdfUri == null) return;
                setBusy(true);
                executor.execute(() -> {
                    try {
                        String diff = PdfEngine.comparePdfs(this, currentPdfUri, secondUri);
                        mainHandler.post(() -> {
                            setBusy(false);
                            showTextModal("PDF Version Comparison", diff, "comparison.txt");
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            setBusy(false);
                            toast("Compare failed: " + e.getMessage());
                        });
                    }
                });
            }
        });
    }

    private void setupListeners() {
        findViewById(R.id.btnOpenPdf).setOnClickListener(v -> pickSinglePdf(openPdfLauncher));
        findViewById(R.id.btnSharePdf).setOnClickListener(v -> shareCurrentPdf());

        btnTabWorkbench.setOnClickListener(v -> switchTab(true));
        btnTabTools.setOnClickListener(v -> switchTab(false));

        findViewById(R.id.btnModeView).setOnClickListener(v -> setOverlayMode(AnnotationOverlayView.Mode.VIEW));
        findViewById(R.id.btnModeEditText).setOnClickListener(v -> {
            if (!requirePdf()) return;
            setOverlayMode(AnnotationOverlayView.Mode.EDIT_TEXT);
            toast("Tap anywhere on the PDF page to add or overwrite text.");
        });
        findViewById(R.id.btnFindReplace).setOnClickListener(v -> promptFindReplace());
        findViewById(R.id.btnModeDraw).setOnClickListener(v -> setOverlayMode(AnnotationOverlayView.Mode.DRAW));
        findViewById(R.id.btnModeHighlight).setOnClickListener(v -> setOverlayMode(AnnotationOverlayView.Mode.HIGHLIGHT));
        findViewById(R.id.btnModeRedact).setOnClickListener(v -> setOverlayMode(AnnotationOverlayView.Mode.REDACT));
        findViewById(R.id.btnClearOverlay).setOnClickListener(v -> annotationOverlay.clear());
        findViewById(R.id.btnFlattenSave).setOnClickListener(v -> saveCanvasEdits());

        annotationOverlay.setOnTapPointListener((xPct, yPct) -> {
            if (requirePdf()) {
                promptAddStyledTextAt(xPct, yPct);
            }
        });

        findViewById(R.id.btnPrevPage).setOnClickListener(v -> {
            if (currentPageIndex > 0) {
                currentPageIndex--;
                renderCurrentPage();
            }
        });
        findViewById(R.id.btnNextPage).setOnClickListener(v -> {
            if (currentPageIndex < totalPages - 1) {
                currentPageIndex++;
                renderCurrentPage();
            }
        });

        // Quick-Action Studio Rail
        findViewById(R.id.btnQuickPdfToWord).setOnClickListener(v -> runPdfToWord());
        findViewById(R.id.btnQuickWordToPdf).setOnClickListener(v -> launchWordToPdf());
        findViewById(R.id.btnQuickMerge).setOnClickListener(v -> launchMerge());
        findViewById(R.id.btnQuickSplit).setOnClickListener(v -> promptSplit());
        findViewById(R.id.btnQuickRotate).setOnClickListener(v -> promptRotate());
        findViewById(R.id.btnQuickExtract).setOnClickListener(v -> runExtractText(false));
        findViewById(R.id.btnQuickForms).setOnClickListener(v -> promptFillForms());
        findViewById(R.id.btnQuickWatermark).setOnClickListener(v -> promptWatermark());
        findViewById(R.id.btnQuickOcr).setOnClickListener(v -> runOcrOnCurrentPage());
    }

    private void switchTab(boolean workbench) {
        paneWorkbench.setVisibility(workbench ? View.VISIBLE : View.GONE);
        paneTools.setVisibility(workbench ? View.GONE : View.VISIBLE);
        btnTabWorkbench.setTextColor(getColor(workbench ? R.color.emerald_accent : R.color.text_secondary));
        btnTabTools.setTextColor(getColor(workbench ? R.color.text_secondary : R.color.emerald_accent));
    }

    private void setOverlayMode(AnnotationOverlayView.Mode mode) {
        annotationOverlay.setMode(mode);
        toast("Mode: " + mode.name());
    }

    private void setupToolHub() {
        List<ToolItem> tools = new ArrayList<>();
        // Editing
        tools.add(new ToolItem("edit_text_tap", "Edit Text on Page (Tap)", "Editing", "Tap anywhere on the PDF page to add or overwrite text with size & color."));
        tools.add(new ToolItem("find_replace", "Find & Replace Word", "Editing", "Search a word on the current PDF page and replace it in place."));
        tools.add(new ToolItem("watermark", "Add Watermark", "Editing", "Apply diagonal semi-transparent watermark across all pages."));
        tools.add(new ToolItem("headers_bates", "Headers, Footers & Bates", "Editing", "Add headers, footers, page numbers, or Bates numbering."));
        // Conversion
        tools.add(new ToolItem("pdf_to_word", "PDF → Word (.docx)", "Conversion", "Convert PDF pages into an editable Microsoft Word (.docx) file."));
        tools.add(new ToolItem("word_to_pdf", "Word (.docx) → PDF", "Conversion", "Convert any .docx Word document into a multi-page PDF."));
        tools.add(new ToolItem("img_to_pdf", "Images to PDF (JPG/PNG)", "Conversion", "Create a multi-page PDF from selected images."));
        tools.add(new ToolItem("pdf_to_png", "Export Page as PNG", "Conversion", "Render the current PDF page as a high-res PNG image."));
        tools.add(new ToolItem("extract_text", "Export to Text", "Conversion", "Extract embedded text from the entire PDF."));
        tools.add(new ToolItem("export_html", "Export to HTML", "Conversion", "Convert extracted PDF text into a clean HTML document."));
        // Page Management
        tools.add(new ToolItem("merge", "Merge PDFs", "Page Management", "Combine multiple PDF documents into one file."));
        tools.add(new ToolItem("split", "Split PDF", "Page Management", "Extract a custom page range into a new PDF."));
        tools.add(new ToolItem("rotate", "Rotate Pages", "Page Management", "Rotate current page or all pages by 90°, 180°, or 270°."));
        tools.add(new ToolItem("delete_page", "Delete Current Page", "Page Management", "Remove the active page from the PDF."));
        tools.add(new ToolItem("crop", "Crop Page Margins", "Page Management", "Trim uniform margins from all pages."));
        // Annotation & Review
        tools.add(new ToolItem("draw", "Draw Ink / Annotate", "Annotation", "Switch to freehand emerald ink drawing on the canvas."));
        tools.add(new ToolItem("highlight", "Highlight Area", "Annotation", "Draw translucent amber highlights on the canvas."));
        // Forms
        tools.add(new ToolItem("fill_forms", "Fill PDF Forms", "Forms", "Edit existing AcroForm text fields and save."));
        tools.add(new ToolItem("create_form", "Create Fillable Field", "Forms", "Add a new interactive text field to the current page."));
        tools.add(new ToolItem("signature", "Add E-Signature", "Forms", "Draw your signature and stamp it onto the current page."));
        // OCR
        tools.add(new ToolItem("ocr", "OCR Scanned PDF", "OCR", "Recognize text from scanned pages using on-device ML Kit."));
        // Security
        tools.add(new ToolItem("encrypt", "Password & Permissions", "Security", "Encrypt PDF and restrict print, copy, or modify permissions."));
        tools.add(new ToolItem("redact", "Redact Sensitive Area", "Security", "Draw permanent black redaction boxes and flatten."));
        // Optimization
        tools.add(new ToolItem("compress", "Optimize & Compress", "Optimization", "Rewrite PDF structure cleanly for smaller, faster sharing."));
        // Other
        tools.add(new ToolItem("bookmarks", "Bookmarks & TOC", "Other", "View existing PDF bookmarks or add one for this page."));
        tools.add(new ToolItem("compare", "Compare Two PDFs", "Other", "Diff extracted text between the active PDF and another."));

        RecyclerView rv = findViewById(R.id.rvTools);
        rv.setLayoutManager(new LinearLayoutManager(this));
        ToolAdapter adapter = new ToolAdapter(tools, item -> {
            switchTab(true);
            dispatchTool(item.id);
        });
        rv.setAdapter(adapter);

        EditText etSearch = findViewById(R.id.etSearchTools);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void dispatchTool(String id) {
        switch (id) {
            case "edit_text_tap":
                if (requirePdf()) {
                    setOverlayMode(AnnotationOverlayView.Mode.EDIT_TEXT);
                    toast("Tap anywhere on the PDF page to add or overwrite text.");
                }
                break;
            case "find_replace": promptFindReplace(); break;
            case "watermark": promptWatermark(); break;
            case "headers_bates": promptHeadersBates(); break;
            case "pdf_to_word": runPdfToWord(); break;
            case "word_to_pdf": launchWordToPdf(); break;
            case "merge": launchMerge(); break;
            case "split": promptSplit(); break;
            case "rotate": promptRotate(); break;
            case "delete_page": confirmDeletePage(); break;
            case "crop": promptCrop(); break;
            case "draw": setOverlayMode(AnnotationOverlayView.Mode.DRAW); break;
            case "highlight": setOverlayMode(AnnotationOverlayView.Mode.HIGHLIGHT); break;
            case "redact": setOverlayMode(AnnotationOverlayView.Mode.REDACT); break;
            case "fill_forms": promptFillForms(); break;
            case "create_form": promptCreateFormField(); break;
            case "signature": promptSignature(); break;
            case "img_to_pdf": launchImagesToPdf(); break;
            case "pdf_to_png": exportCurrentPagePng(); break;
            case "extract_text": runExtractText(false); break;
            case "export_html": runExtractText(true); break;
            case "ocr": runOcrOnCurrentPage(); break;
            case "encrypt": promptEncrypt(); break;
            case "compress": runCompress(); break;
            case "bookmarks": promptBookmarks(); break;
            case "compare": launchCompare(); break;
        }
    }

    private void pickSinglePdf(ActivityResultLauncher<Intent> launcher) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        launcher.launch(i);
    }

    private void launchWordToPdf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        wordToPdfLauncher.launch(i);
    }

    private void launchMerge() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        mergePdfsLauncher.launch(i);
    }

    private void launchImagesToPdf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        imagesToPdfLauncher.launch(i);
    }

    private void launchCompare() {
        if (!requirePdf()) return;
        toast("Pick the second PDF to compare against.");
        pickSinglePdf(comparePdfLauncher);
    }

    private void loadPdf(Uri uri) {
        currentPdfUri = uri;
        currentPageIndex = 0;
        annotationOverlay.clear();
        renderCurrentPage();
    }

    private void renderCurrentPage() {
        if (currentPdfUri == null) return;
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(currentPdfUri, "r")) {
            if (pfd == null) return;
            try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                totalPages = renderer.getPageCount();
                if (currentPageIndex >= totalPages) currentPageIndex = Math.max(0, totalPages - 1);
                try (PdfRenderer.Page page = renderer.openPage(currentPageIndex)) {
                    int w = Math.max(page.getWidth() * 2, 1080);
                    int h = Math.max(page.getHeight() * 2, (int) (1080f * page.getHeight() / Math.max(1, page.getWidth())));
                    if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
                    currentBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    currentBitmap.eraseColor(Color.WHITE);
                    page.render(currentBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    ivPdfPage.setImageBitmap(currentBitmap);
                    tvEmptyCanvas.setVisibility(View.GONE);
                }
            }
            tvFileStatus.setText(getFileName(currentPdfUri) + " • " + totalPages + " pages");
            tvPageIndicator.setText("Page " + (currentPageIndex + 1) + " / " + totalPages);
        } catch (Exception e) {
            toast("Unable to render PDF: " + e.getMessage());
        }
    }

    private void saveCanvasEdits() {
        if (!requirePdf()) return;
        if (!annotationOverlay.hasEdits()) {
            toast("Draw, highlight, or redact on the page first.");
            return;
        }
        Bitmap overlay = annotationOverlay.exportOverlayBitmap();
        if (overlay == null) return;
        runTask("Flattening annotations into PDF…", () -> {
            File out = createOutputFile("Annotated_", ".pdf");
            PdfEngine.flattenOverlayBitmap(this, currentPdfUri, currentPageIndex, overlay, out);
            overlay.recycle();
            return out;
        });
    }

    private void promptAddStyledTextAt(float xPct, float yPct) {
        LinearLayout ll = dialogContainer();
        EditText etText = inputField("Text to write on PDF", "");
        EditText etSize = inputField("Font size (e.g. 14)", "14");
        Spinner spColor = new Spinner(this);
        String[] colors = {"Black", "Emerald", "Dark Blue", "Crimson"};
        spColor.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, colors));
        CheckBox cbWhiteout = new CheckBox(this);
        cbWhiteout.setText("Cover/erase existing text behind");
        cbWhiteout.setChecked(true);

        ll.addView(etText);
        ll.addView(etSize);
        ll.addView(spColor);
        ll.addView(cbWhiteout);

        new AlertDialog.Builder(this)
                .setTitle("Edit Text on Page " + (currentPageIndex + 1))
                .setView(ll)
                .setPositiveButton("Apply", (d, w) -> {
                    String txt = etText.getText().toString().trim();
                    if (txt.isEmpty()) return;
                    float size = parseIntSafe(etSize.getText().toString(), 14);
                    int sel = spColor.getSelectedItemPosition();
                    int r = 0, g = 0, b = 0;
                    if (sel == 1) { r = 16; g = 185; b = 129; }
                    else if (sel == 2) { r = 30; g = 64; b = 175; }
                    else if (sel == 3) { r = 220; g = 38; b = 38; }
                    int fr = r, fg = g, fb = b;
                    runTask("Writing text to PDF…", () -> {
                        File out = createOutputFile("TextEdited_", ".pdf");
                        PdfEngine.addStyledTextAt(this, currentPdfUri, currentPageIndex, txt, xPct, yPct, size, fr, fg, fb, cbWhiteout.isChecked(), out);
                        return out;
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptFindReplace() {
        if (!requirePdf()) return;
        LinearLayout ll = dialogContainer();
        EditText etFind = inputField("Word to find on Page " + (currentPageIndex + 1), "");
        EditText etReplace = inputField("Replace with", "");
        ll.addView(etFind);
        ll.addView(etReplace);

        new AlertDialog.Builder(this)
                .setTitle("Find & Replace Word")
                .setView(ll)
                .setPositiveButton("Replace", (d, w) -> {
                    String find = etFind.getText().toString();
                    String rep = etReplace.getText().toString();
                    if (find.isEmpty()) return;
                    setBusy(true);
                    executor.execute(() -> {
                        try {
                            File out = createOutputFile("Replaced_", ".pdf");
                            int count = PdfEngine.findAndReplaceWord(this, currentPdfUri, currentPageIndex, find, rep, out);
                            mainHandler.post(() -> {
                                setBusy(false);
                                if (count == 0) {
                                    toast("Word not found on this page.");
                                } else {
                                    toast("Replaced " + count + " match(es).");
                                    Uri outUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
                                    loadPdf(outUri);
                                }
                            });
                        } catch (Exception e) {
                            mainHandler.post(() -> {
                                setBusy(false);
                                toast("Replace error: " + e.getMessage());
                            });
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runPdfToWord() {
        if (!requirePdf()) return;
        setBusy(true);
        toast("Converting PDF to Word (.docx)…");
        executor.execute(() -> {
            try {
                File out = createOutputFile("Converted_", ".docx");
                PdfEngine.pdfToWord(this, currentPdfUri, out);
                mainHandler.post(() -> {
                    setBusy(false);
                    toast("Saved Word file: " + out.getName());
                    shareFile(out, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false);
                    toast("PDF → Word failed: " + e.getMessage());
                });
            }
        });
    }

    private void promptSplit() {
        if (!requirePdf()) return;
        LinearLayout ll = dialogContainer();
        EditText etStart = inputField("Start page (1-" + totalPages + ")", "1");
        EditText etEnd = inputField("End page (1-" + totalPages + ")", String.valueOf(totalPages));
        ll.addView(etStart);
        ll.addView(etEnd);

        new AlertDialog.Builder(this)
                .setTitle("Split PDF Range")
                .setView(ll)
                .setPositiveButton("Split", (d, w) -> {
                    int s = parseIntSafe(etStart.getText().toString(), 1);
                    int e = parseIntSafe(etEnd.getText().toString(), totalPages);
                    runTask("Splitting pages " + s + "–" + e + "…", () -> {
                        File out = createOutputFile("Split_", ".pdf");
                        PdfEngine.splitPdf(this, currentPdfUri, s, e, out);
                        return out;
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptRotate() {
        if (!requirePdf()) return;
        String[] options = {"Rotate Current Page 90° CW", "Rotate Current Page 180°", "Rotate All Pages 90° CW"};
        new AlertDialog.Builder(this)
                .setTitle("Rotate Pages")
                .setItems(options, (d, which) -> {
                    int deg = (which == 1) ? 180 : 90;
                    boolean all = (which == 2);
                    runTask("Rotating…", () -> {
                        File out = createOutputFile("Rotated_", ".pdf");
                        PdfEngine.rotatePages(this, currentPdfUri, currentPageIndex, deg, all, out);
                        return out;
                    });
                })
                .show();
    }

    private void confirmDeletePage() {
        if (!requirePdf()) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete Page " + (currentPageIndex + 1) + "?")
                .setMessage("This creates a new PDF without the current page.")
                .setPositiveButton("Delete", (d, w) -> runTask("Deleting page…", () -> {
                    File out = createOutputFile("PageDeleted_", ".pdf");
                    PdfEngine.deletePage(this, currentPdfUri, currentPageIndex, out);
                    return out;
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptCrop() {
        if (!requirePdf()) return;
        EditText etMargin = inputField("Margin to trim (points, e.g. 24)", "24");
        new AlertDialog.Builder(this)
                .setTitle("Crop Page Margins")
                .setView(wrapPadding(etMargin))
                .setPositiveButton("Crop", (d, w) -> {
                    float m = parseIntSafe(etMargin.getText().toString(), 24);
                    runTask("Cropping margins…", () -> {
                        File out = createOutputFile("Cropped_", ".pdf");
                        PdfEngine.cropMargins(this, currentPdfUri, m, out);
                        return out;
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptWatermark() {
        if (!requirePdf()) return;
        EditText et = inputField("Watermark text", "MAGESH M PDF EDITOR");
        new AlertDialog.Builder(this)
                .setTitle("Add Watermark")
                .setView(wrapPadding(et))
                .setPositiveButton("Apply", (d, w) -> {
                    String txt = et.getText().toString().trim();
                    if (txt.isEmpty()) return;
                    runTask("Applying watermark…", () -> {
                        File out = createOutputFile("Watermarked_", ".pdf");
                        PdfEngine.addWatermark(this, currentPdfUri, txt, out);
                        return out;
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptHeadersBates() {
        if (!requirePdf()) return;
        LinearLayout ll = dialogContainer();
        EditText etHeader = inputField("Header text (optional)", "");
        EditText etFooter = inputField("Footer text (optional)", "");
        EditText etBates = inputField("Bates prefix (e.g. DOC-)", "");
        CheckBox cbNum = new CheckBox(this);
        cbNum.setText("Include Page X of Y");
        cbNum.setChecked(true);
        ll.addView(etHeader);
        ll.addView(etFooter);
        ll.addView(etBates);
        ll.addView(cbNum);

        new AlertDialog.Builder(this)
                .setTitle("Headers, Footers & Bates")
                .setView(ll)
                .setPositiveButton("Apply", (d, w) -> runTask("Stamping headers/footers…", () -> {
                    File out = createOutputFile("Stamped_", ".pdf");
                    PdfEngine.addHeaderFooterBates(this, currentPdfUri,
                            etHeader.getText().toString().trim(),
                            etFooter.getText().toString().trim(),
                            etBates.getText().toString().trim(),
                            cbNum.isChecked(), out);
                    return out;
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptFillForms() {
        if (!requirePdf()) return;
        setBusy(true);
        executor.execute(() -> {
            try {
                Map<String, String> fields = PdfEngine.getFormFields(this, currentPdfUri);
                mainHandler.post(() -> {
                    setBusy(false);
                    if (fields.isEmpty()) {
                        toast("No fillable AcroForm fields found in this PDF.");
                        return;
                    }
                    LinearLayout ll = dialogContainer();
                    Map<String, EditText> inputs = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : fields.entrySet()) {
                        EditText et = inputField(e.getKey(), e.getValue() == null ? "" : e.getValue());
                        ll.addView(et);
                        inputs.put(e.getKey(), et);
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Fill PDF Form Fields")
                            .setView(ll)
                            .setPositiveButton("Save Form", (d, w) -> {
                                Map<String, String> updated = new LinkedHashMap<>();
                                for (Map.Entry<String, EditText> e : inputs.entrySet()) {
                                    updated.put(e.getKey(), e.getValue().getText().toString());
                                }
                                runTask("Saving form fields…", () -> {
                                    File out = createOutputFile("FormFilled_", ".pdf");
                                    PdfEngine.fillFormFields(this, currentPdfUri, updated, out);
                                    return out;
                                });
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false);
                    toast("Form read error: " + e.getMessage());
                });
            }
        });
    }

    private void promptCreateFormField() {
        if (!requirePdf()) return;
        EditText et = inputField("Field name (e.g. FullName)", "Field1");
        new AlertDialog.Builder(this)
                .setTitle("Create Fillable Text Field")
                .setView(wrapPadding(et))
                .setPositiveButton("Create", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) return;
                    runTask("Creating fillable field…", () -> {
                        File out = createOutputFile("FormCreated_", ".pdf");
                        PdfEngine.addFillableTextField(this, currentPdfUri, currentPageIndex, name, out);
                        return out;
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptSignature() {
        if (!requirePdf()) return;
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_signature, null);
        AnnotationOverlayView pad = view.findViewById(R.id.signaturePad);
        pad.setMode(AnnotationOverlayView.Mode.DRAW);

        new AlertDialog.Builder(this)
                .setTitle("Draw E-Signature")
                .setView(view)
                .setPositiveButton("Apply to Page", (d, w) -> {
                    Bitmap sig = pad.exportOverlayBitmap();
                    if (sig == null) return;
                    runTask("Stamping signature…", () -> {
                        File out = createOutputFile("Signed_", ".pdf");
                        PdfEngine.flattenOverlayBitmap(this, currentPdfUri, currentPageIndex, sig, out);
                        sig.recycle();
                        return out;
                    });
                })
                .setNeutralButton("Clear", (d, w) -> promptSignature())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runExtractText(boolean asHtml) {
        if (!requirePdf()) return;
        setBusy(true);
        executor.execute(() -> {
            try {
                String txt = PdfEngine.extractText(this, currentPdfUri);
                String content = asHtml
                        ? "<!DOCTYPE html><html><body style='font-family:sans-serif;padding:24px;white-space:pre-wrap;'>"
                        + txt.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        + "</body></html>"
                        : txt;
                mainHandler.post(() -> {
                    setBusy(false);
                    showTextModal(asHtml ? "HTML Export Preview" : "Extracted PDF Text",
                            content, asHtml ? "exported.html" : "extracted.txt");
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false);
                    toast("Extract failed: " + e.getMessage());
                });
            }
        });
    }

    private void runOcrOnCurrentPage() {
        if (!requirePdf() || currentBitmap == null) return;
        setBusy(true);
        InputImage image = InputImage.fromBitmap(currentBitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    setBusy(false);
                    String res = visionText.getText();
                    if (res.trim().isEmpty()) res = "(No text detected on this page.)";
                    showTextModal("OCR Scan (Page " + (currentPageIndex + 1) + ")", res, "ocr_page_" + (currentPageIndex + 1) + ".txt");
                })
                .addOnFailureListener(e -> {
                    setBusy(false);
                    toast("OCR error: " + e.getMessage());
                });
    }

    private void exportCurrentPagePng() {
        if (!requirePdf()) return;
        setBusy(true);
        executor.execute(() -> {
            try {
                File out = createOutputFile("Page" + (currentPageIndex + 1) + "_", ".png");
                PdfEngine.exportPageAsPng(this, currentPdfUri, currentPageIndex, out);
                mainHandler.post(() -> {
                    setBusy(false);
                    toast("Saved PNG: " + out.getName());
                    shareFile(out, "image/png");
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false);
                    toast("PNG export failed: " + e.getMessage());
                });
            }
        });
    }

    private void promptEncrypt() {
        if (!requirePdf()) return;
        LinearLayout ll = dialogContainer();
        EditText etUser = inputField("Open Password (optional)", "");
        EditText etOwner = inputField("Owner Password", "magesh123");
        CheckBox cbPrint = new CheckBox(this); cbPrint.setText("Allow Printing"); cbPrint.setChecked(true);
        CheckBox cbCopy = new CheckBox(this); cbCopy.setText("Allow Copy Text"); cbCopy.setChecked(false);
        CheckBox cbModify = new CheckBox(this); cbModify.setText("Allow Editing"); cbModify.setChecked(false);
        ll.addView(etUser); ll.addView(etOwner); ll.addView(cbPrint); ll.addView(cbCopy); ll.addView(cbModify);

        new AlertDialog.Builder(this)
                .setTitle("Password & Permissions")
                .setView(ll)
                .setPositiveButton("Encrypt", (d, w) -> runTask("Encrypting PDF…", () -> {
                    File out = createOutputFile("Protected_", ".pdf");
                    PdfEngine.encryptPdf(this, currentPdfUri,
                            etUser.getText().toString(),
                            etOwner.getText().toString(),
                            cbPrint.isChecked(), cbCopy.isChecked(), cbModify.isChecked(), out);
                    return out;
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runCompress() {
        if (!requirePdf()) return;
        runTask("Optimizing PDF…", () -> {
            File out = createOutputFile("Optimized_", ".pdf");
            PdfEngine.compressPdf(this, currentPdfUri, out);
            return out;
        });
    }

    private void promptBookmarks() {
        if (!requirePdf()) return;
        setBusy(true);
        executor.execute(() -> {
            try {
                List<String> bms = PdfEngine.getBookmarks(this, currentPdfUri);
                mainHandler.post(() -> {
                    setBusy(false);
                    String msg = bms.isEmpty() ? "No bookmarks yet." : String.join("\n• ", bms);
                    EditText et = inputField("New bookmark title for Page " + (currentPageIndex + 1), "");
                    LinearLayout ll = dialogContainer();
                    TextView tv = new TextView(this);
                    tv.setText("Existing:\n• " + msg);
                    tv.setTextColor(getColor(R.color.text_secondary));
                    ll.addView(tv);
                    ll.addView(et);
                    new AlertDialog.Builder(this)
                            .setTitle("Bookmarks & TOC")
                            .setView(ll)
                            .setPositiveButton("Add Bookmark", (d, w) -> {
                                String title = et.getText().toString().trim();
                                if (title.isEmpty()) return;
                                runTask("Adding bookmark…", () -> {
                                    File out = createOutputFile("Bookmarked_", ".pdf");
                                    PdfEngine.addBookmark(this, currentPdfUri, currentPageIndex, title, out);
                                    return out;
                                });
                            })
                            .setNegativeButton("Close", null)
                            .show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false);
                    toast("Bookmark error: " + e.getMessage());
                });
            }
        });
    }

    private void showTextModal(String title, String text, String exportName) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_text_output, null);
        EditText et = view.findViewById(R.id.etExtractedOutput);
        et.setText(text);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(view)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(title, et.getText().toString()));
                    toast("Copied to clipboard.");
                })
                .setNeutralButton("Export File", (d, w) -> {
                    try {
                        File out = new File(getOutputDir(), exportName);
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            fos.write(et.getText().toString().getBytes(StandardCharsets.UTF_8));
                        }
                        shareFile(out, exportName.endsWith(".html") ? "text/html" : "text/plain");
                    } catch (Exception e) {
                        toast("Export failed: " + e.getMessage());
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private interface PdfJob {
        File run() throws Exception;
    }

    private void runTask(String message, PdfJob job) {
        toast(message);
        setBusy(true);
        executor.execute(() -> {
            try {
                File out = job.run();
                Uri outUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
                mainHandler.post(() -> {
                    setBusy(false);
                    toast("Saved: " + out.getName());
                    loadPdf(outUri);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false);
                    toast("Error: " + e.getMessage());
                });
            }
        });
    }

    private void shareCurrentPdf() {
        if (!requirePdf()) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/pdf");
        share.putExtra(Intent.EXTRA_STREAM, currentPdfUri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share PDF"));
    }

    private void shareFile(File file, String mime) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(mime);
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Export " + file.getName()));
    }

    private File getOutputDir() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir == null) dir = getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File createOutputFile(String prefix, String ext) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(getOutputDir(), prefix + ts + ext);
    }

    private List<Uri> extractMultipleUris(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private boolean requirePdf() {
        if (currentPdfUri == null) {
            toast("Open a PDF first.");
            return false;
        }
        return true;
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private LinearLayout dialogContainer() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(44, 24, 44, 12);
        return ll;
    }

    private View wrapPadding(View v) {
        LinearLayout ll = dialogContainer();
        ll.addView(v);
        return ll;
    }

    private EditText inputField(String hint, String initial) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(initial);
        return et;
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private String getFileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return "Document.pdf";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
    }
}
