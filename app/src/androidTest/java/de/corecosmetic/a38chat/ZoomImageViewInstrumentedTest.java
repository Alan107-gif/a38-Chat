package de.corecosmetic.a38chat;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class ZoomImageViewInstrumentedTest {
    @Test
    public void outsideTapAndPinchZoom() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<ZoomImageView> reference = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            ZoomImageView view = new ZoomImageView(instrumentation.getTargetContext());
            Bitmap bitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888);
            view.setImageDrawable(new BitmapDrawable(instrumentation.getTargetContext().getResources(), bitmap));
            view.layout(0, 0, 500, 500);
            reference.set(view);
        });
        instrumentation.waitForIdleSync();

        ZoomImageView view = reference.get();
        AtomicBoolean outsideTap = new AtomicBoolean(false);
        long outsideDownTime = SystemClock.uptimeMillis();
        instrumentation.runOnMainSync(() -> {
            view.setOutsideTapListener(() -> outsideTap.set(true));
            dispatch(view, MotionEvent.ACTION_DOWN, outsideDownTime, outsideDownTime, point(250f, 20f));
            dispatch(view, MotionEvent.ACTION_UP, outsideDownTime, outsideDownTime + 40L, point(250f, 20f));
        });
        assertTrue("Tap outside the displayed bitmap must close the viewer", outsideTap.get());

        float[] before = matrixValues(view.getImageMatrix());
        long pinchDownTime = SystemClock.uptimeMillis();
        instrumentation.runOnMainSync(() -> {
            dispatch(view, MotionEvent.ACTION_DOWN, pinchDownTime, pinchDownTime, point(210f, 250f));
            dispatch(view, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    pinchDownTime, pinchDownTime + 40L, point(210f, 250f), point(290f, 250f));
            dispatch(view, MotionEvent.ACTION_MOVE, pinchDownTime, pinchDownTime + 80L,
                    point(170f, 250f), point(330f, 250f));
            dispatch(view, MotionEvent.ACTION_MOVE, pinchDownTime, pinchDownTime + 120L,
                    point(120f, 250f), point(380f, 250f));
            dispatch(view, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    pinchDownTime, pinchDownTime + 160L, point(120f, 250f), point(380f, 250f));
            dispatch(view, MotionEvent.ACTION_UP, pinchDownTime, pinchDownTime + 200L, point(120f, 250f));
        });
        float[] after = matrixValues(view.getImageMatrix());
        assertTrue("Pinch-out must increase the image scale: before=" + before[Matrix.MSCALE_X]
                        + " after=" + after[Matrix.MSCALE_X],
                after[Matrix.MSCALE_X] > before[Matrix.MSCALE_X]);
        assertTrue("Pinch-out must preserve uniform scaling",
                Math.abs(after[Matrix.MSCALE_X] - after[Matrix.MSCALE_Y]) < 0.001f);
    }

    private static float[][] point(float x, float y) {
        return new float[][]{{x, y}};
    }

    private static void dispatch(ZoomImageView view, int action, long downTime, long eventTime, float[][]... groups) {
        int pointerCount = groups.length;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[pointerCount];
        for (int index = 0; index < pointerCount; index++) {
            float[][] group = groups[index];
            MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
            pointer.id = index;
            pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
            properties[index] = pointer;

            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
            coords.x = group[0][0];
            coords.y = group[0][1];
            coords.pressure = 1f;
            coords.size = 1f;
            coordinates[index] = coords;
        }
        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                pointerCount,
                properties,
                coordinates,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
        );
        try {
            view.onTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static float[] matrixValues(Matrix matrix) {
        float[] values = new float[9];
        matrix.getValues(values);
        return values;
    }
}
