package de.corecosmetic.a38chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

final class ProfileImageEditorView extends View {
    static final int IMAGE_SIZE = 32;

    enum Tool {
        BRUSH,
        ERASER,
        CIRCLE
    }

    private final Bitmap bitmap;
    private final Paint bitmapPaint = new Paint();
    private final Paint backgroundPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final RectF drawingBounds = new RectF();
    private Bitmap gestureBase;
    private Tool tool = Tool.BRUSH;
    private int brushWidth = 1;
    private int color = Color.BLACK;
    private int opacity = 255;
    private boolean circleFilled;
    private boolean dirty;
    private int lastPixelX;
    private int lastPixelY;
    private int circleCenterX;
    private int circleCenterY;
    private boolean gestureActive;

    ProfileImageEditorView(Context context, Bitmap existing) {
        super(context);
        bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);
        if (existing != null) {
            Canvas canvas = new Canvas(bitmap);
            bitmapPaint.setFilterBitmap(false);
            canvas.drawBitmap(existing, null, new RectF(0, 0, IMAGE_SIZE, IMAGE_SIZE), bitmapPaint);
        }

        bitmapPaint.setAntiAlias(false);
        bitmapPaint.setFilterBitmap(false);
        backgroundPaint.setColor(Color.rgb(238, 241, 245));
        gridPaint.setColor(Color.argb(62, 15, 23, 42));
        gridPaint.setStrokeWidth(1f);
        borderPaint.setColor(Color.argb(150, 15, 23, 42));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
        setContentDescription("32 by 32 profile image drawing area");
    }

    void setTool(Tool tool) {
        this.tool = tool == null ? Tool.BRUSH : tool;
    }

    Tool getTool() {
        return tool;
    }

    void setBrushWidth(int width) {
        brushWidth = Math.max(1, Math.min(5, width));
    }

    void setColor(int color) {
        this.color = Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
    }

    void setOpacity(int opacity) {
        this.opacity = Math.max(0, Math.min(255, opacity));
    }

    void setCircleFilled(boolean filled) {
        circleFilled = filled;
    }

    boolean hasUnsavedChanges() {
        return dirty;
    }

    Bitmap snapshot() {
        return bitmap.copy(Bitmap.Config.ARGB_8888, false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float side = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom());
        float left = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - side) / 2f;
        float top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - side) / 2f;
        drawingBounds.set(left, top, left + side, top + side);

        canvas.drawRect(drawingBounds, backgroundPaint);
        canvas.drawBitmap(bitmap, null, drawingBounds, bitmapPaint);
        float step = side / IMAGE_SIZE;
        for (int i = 1; i < IMAGE_SIZE; i++) {
            float position = i * step;
            canvas.drawLine(left + position, top, left + position, top + side, gridPaint);
            canvas.drawLine(left, top + position, left + side, top + position, gridPaint);
        }
        canvas.drawRect(drawingBounds, borderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (drawingBounds.isEmpty()) {
            return false;
        }
        if (action == MotionEvent.ACTION_DOWN && !drawingBounds.contains(event.getX(), event.getY())) {
            return false;
        }
        if (action != MotionEvent.ACTION_DOWN && !gestureActive) {
            return false;
        }

        int pixelX = coordinateToPixel(event.getX(), drawingBounds.left, drawingBounds.width());
        int pixelY = coordinateToPixel(event.getY(), drawingBounds.top, drawingBounds.height());
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                gestureActive = true;
                requestParentIntercept(true);
                lastPixelX = pixelX;
                lastPixelY = pixelY;
                if (tool == Tool.CIRCLE) {
                    recycleGestureBase();
                    gestureBase = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    circleCenterX = pixelX;
                    circleCenterY = pixelY;
                    drawCirclePreview(pixelX, pixelY);
                } else {
                    drawStroke(pixelX, pixelY, pixelX, pixelY);
                    dirty = true;
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tool == Tool.CIRCLE) {
                    drawCirclePreview(pixelX, pixelY);
                } else {
                    drawStroke(lastPixelX, lastPixelY, pixelX, pixelY);
                    lastPixelX = pixelX;
                    lastPixelY = pixelY;
                    dirty = true;
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (tool == Tool.CIRCLE) {
                    drawCirclePreview(pixelX, pixelY);
                    recycleGestureBase();
                    dirty = true;
                }
                requestParentIntercept(false);
                gestureActive = false;
                performClick();
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (tool == Tool.CIRCLE && gestureBase != null) {
                    restoreGestureBase();
                    recycleGestureBase();
                    invalidate();
                }
                requestParentIntercept(false);
                gestureActive = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private int coordinateToPixel(float value, float start, float length) {
        int pixel = (int)Math.floor((value - start) * IMAGE_SIZE / length);
        return Math.max(0, Math.min(IMAGE_SIZE - 1, pixel));
    }

    private void requestParentIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private void drawStroke(int fromX, int fromY, int toX, int toY) {
        int dx = Math.abs(toX - fromX);
        int dy = Math.abs(toY - fromY);
        int steps = Math.max(1, Math.max(dx, dy));
        Canvas canvas = new Canvas(bitmap);
        Paint paint = drawingPaint(tool == Tool.ERASER, true);
        for (int i = 0; i <= steps; i++) {
            float fraction = i / (float)steps;
            float x = fromX + (toX - fromX) * fraction + 0.5f;
            float y = fromY + (toY - fromY) * fraction + 0.5f;
            canvas.drawCircle(x, y, Math.max(0.5f, brushWidth / 2f), paint);
        }
    }

    private void drawCirclePreview(int edgeX, int edgeY) {
        if (gestureBase == null) {
            return;
        }
        restoreGestureBase();
        float dx = edgeX - circleCenterX;
        float dy = edgeY - circleCenterY;
        float radius = Math.max(0.5f, (float)Math.sqrt(dx * dx + dy * dy));
        Paint paint = drawingPaint(false, circleFilled);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawCircle(circleCenterX + 0.5f, circleCenterY + 0.5f, radius, paint);
    }

    private Paint drawingPaint(boolean erasing, boolean filled) {
        Paint paint = new Paint();
        paint.setAntiAlias(false);
        paint.setStrokeWidth(brushWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
        if (erasing) {
            paint.setColor(Color.TRANSPARENT);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        } else {
            paint.setColor(Color.argb(opacity, Color.red(color), Color.green(color), Color.blue(color)));
        }
        return paint;
    }

    private void restoreGestureBase() {
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        canvas.drawBitmap(gestureBase, 0, 0, bitmapPaint);
    }

    private void recycleGestureBase() {
        if (gestureBase != null) {
            gestureBase.recycle();
            gestureBase = null;
        }
    }
}
