package de.corecosmetic.a38chat;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

public final class ZoomImageView extends ImageView {
    private static final float MAX_ZOOM_MULTIPLIER = 5f;
    private static final float TAP_SLOP = 18f;

    private final Matrix imageTransform = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private float fittedScale = 1f;
    private float currentScale = 1f;
    private float lastX;
    private float lastY;
    private float downX;
    private float downY;
    private boolean moved;
    private Runnable outsideTapListener;

    public ZoomImageView(Context context) {
        this(context, null);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                Drawable drawable = getDrawable();
                if (drawable == null) {
                    return false;
                }
                float targetScale = Math.max(fittedScale, Math.min(fittedScale * MAX_ZOOM_MULTIPLIER, currentScale * detector.getScaleFactor()));
                float factor = targetScale / currentScale;
                imageTransform.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                currentScale = targetScale;
                constrainImage();
                setImageMatrix(imageTransform);
                moved = true;
                return true;
            }
        });
    }

    void setOutsideTapListener(Runnable listener) {
        outsideTapListener = listener;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        fitImage();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::fitImage);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && !scaleDetector.isInProgress() && currentScale > fittedScale) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    if (Math.abs(event.getX() - downX) > TAP_SLOP || Math.abs(event.getY() - downY) > TAP_SLOP) {
                        moved = true;
                    }
                    imageTransform.postTranslate(dx, dy);
                    constrainImage();
                    setImageMatrix(imageTransform);
                }
                lastX = event.getX();
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved && !scaleDetector.isInProgress()) {
                    RectF displayed = displayedImageBounds();
                    if (displayed != null && !displayed.contains(event.getX(), event.getY()) && outsideTapListener != null) {
                        outsideTapListener.run();
                    }
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
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

    private void fitImage() {
        Drawable drawable = getDrawable();
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (drawable == null || width <= 0 || height <= 0 || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            return;
        }

        float sx = width / (float)drawable.getIntrinsicWidth();
        float sy = height / (float)drawable.getIntrinsicHeight();
        fittedScale = Math.min(sx, sy);
        currentScale = fittedScale;
        float displayedWidth = drawable.getIntrinsicWidth() * fittedScale;
        float displayedHeight = drawable.getIntrinsicHeight() * fittedScale;
        float dx = getPaddingLeft() + (width - displayedWidth) / 2f;
        float dy = getPaddingTop() + (height - displayedHeight) / 2f;

        imageTransform.reset();
        imageTransform.postScale(fittedScale, fittedScale);
        imageTransform.postTranslate(dx, dy);
        setImageMatrix(imageTransform);
    }

    private RectF displayedImageBounds() {
        Drawable drawable = getDrawable();
        if (drawable == null) {
            return null;
        }
        RectF bounds = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        imageTransform.mapRect(bounds);
        return bounds;
    }

    private void constrainImage() {
        RectF bounds = displayedImageBounds();
        if (bounds == null) {
            return;
        }
        float left = getPaddingLeft();
        float top = getPaddingTop();
        float right = getWidth() - getPaddingRight();
        float bottom = getHeight() - getPaddingBottom();
        float dx;
        float dy;

        if (bounds.width() <= right - left) {
            dx = (left + right) / 2f - bounds.centerX();
        } else if (bounds.left > left) {
            dx = left - bounds.left;
        } else if (bounds.right < right) {
            dx = right - bounds.right;
        } else {
            dx = 0f;
        }

        if (bounds.height() <= bottom - top) {
            dy = (top + bottom) / 2f - bounds.centerY();
        } else if (bounds.top > top) {
            dy = top - bounds.top;
        } else if (bounds.bottom < bottom) {
            dy = bottom - bounds.bottom;
        } else {
            dy = 0f;
        }
        imageTransform.postTranslate(dx, dy);
    }
}
