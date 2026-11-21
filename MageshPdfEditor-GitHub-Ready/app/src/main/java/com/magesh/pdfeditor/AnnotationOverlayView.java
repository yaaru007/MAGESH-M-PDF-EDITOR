package com.magesh.pdfeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class AnnotationOverlayView extends View {

    public enum Mode {
        VIEW, DRAW, HIGHLIGHT, REDACT
    }

    private static class Stroke {
        final Path path;
        final Paint paint;
        Stroke(Path path, Paint paint) {
            this.path = path;
            this.paint = paint;
        }
    }

    private static class Box {
        final RectF rect;
        final Paint paint;
        Box(RectF rect, Paint paint) {
            this.rect = rect;
            this.paint = paint;
        }
    }

    private Mode mode = Mode.VIEW;
    private final List<Stroke> strokes = new ArrayList<>();
    private final List<Box> boxes = new ArrayList<>();
    private Path currentPath;
    private Paint currentPaint;
    private RectF currentRect;
    private float startX, startY;

    public AnnotationOverlayView(Context context) {
        super(context);
    }

    public AnnotationOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode getMode() {
        return mode;
    }

    public boolean hasEdits() {
        return !strokes.isEmpty() || !boxes.isEmpty();
    }

    public void clear() {
        strokes.clear();
        boxes.clear();
        currentPath = null;
        currentRect = null;
        invalidate();
    }

    public Bitmap exportOverlayBitmap() {
        if (getWidth() <= 0 || getHeight() <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        draw(c);
        return bmp;
    }

    private Paint createPaint(Mode m) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (m == Mode.DRAW) {
            p.setColor(Color.parseColor("#10B981"));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(7f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        } else if (m == Mode.HIGHLIGHT) {
            p.setColor(Color.argb(95, 245, 158, 11));
            p.setStyle(Paint.Style.FILL);
        } else if (m == Mode.REDACT) {
            p.setColor(Color.BLACK);
            p.setStyle(Paint.Style.FILL);
        }
        return p;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Box b : boxes) {
            canvas.drawRect(b.rect, b.paint);
        }
        if (currentRect != null && currentPaint != null) {
            canvas.drawRect(currentRect, currentPaint);
        }
        for (Stroke s : strokes) {
            canvas.drawPath(s.path, s.paint);
        }
        if (currentPath != null && currentPaint != null) {
            canvas.drawPath(currentPath, currentPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mode == Mode.VIEW) return false;
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                currentPaint = createPaint(mode);
                if (mode == Mode.DRAW) {
                    currentPath = new Path();
                    currentPath.moveTo(x, y);
                } else {
                    startX = x;
                    startY = y;
                    currentRect = new RectF(x, y, x, y);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (mode == Mode.DRAW && currentPath != null) {
                    currentPath.lineTo(x, y);
                } else if (currentRect != null) {
                    currentRect.set(Math.min(startX, x), Math.min(startY, y),
                            Math.max(startX, x), Math.max(startY, y));
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                if (mode == Mode.DRAW && currentPath != null) {
                    strokes.add(new Stroke(currentPath, currentPaint));
                    currentPath = null;
                } else if (currentRect != null) {
                    boxes.add(new Box(currentRect, currentPaint));
                    currentRect = null;
                }
                invalidate();
                return true;
        }
        return false;
    }
}
