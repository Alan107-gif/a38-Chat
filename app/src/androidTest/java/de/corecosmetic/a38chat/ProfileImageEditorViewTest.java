package de.corecosmetic.a38chat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ProfileImageEditorViewTest {
    @Test
    public void enlargedFingerCanvasProducesExactPixelArtBitmap() {
        AtomicReference<Bitmap> result = new AtomicReference<>();
        AtomicInteger brushPixels = new AtomicInteger();
        AtomicInteger erasedPixels = new AtomicInteger();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ProfileImageEditorView editor = new ProfileImageEditorView(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    null
            );
            int canvasSize = 640;
            editor.measure(
                    View.MeasureSpec.makeMeasureSpec(canvasSize, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(canvasSize, View.MeasureSpec.EXACTLY)
            );
            editor.layout(0, 0, canvasSize, canvasSize);
            Bitmap rendered = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888);
            editor.draw(new Canvas(rendered));
            rendered.recycle();

            editor.setTool(ProfileImageEditorView.Tool.BRUSH);
            editor.setBrushWidth(3);
            editor.setColor(Color.RED);
            editor.setOpacity(255);
            dispatch(editor, MotionEvent.ACTION_DOWN, 180f, 180f);
            dispatch(editor, MotionEvent.ACTION_MOVE, 460f, 460f);
            dispatch(editor, MotionEvent.ACTION_UP, 460f, 460f);

            Bitmap brushSnapshot = editor.snapshot();
            brushPixels.set(nonTransparentPixels(brushSnapshot));
            brushSnapshot.recycle();

            editor.setTool(ProfileImageEditorView.Tool.ERASER);
            editor.setBrushWidth(5);
            dispatch(editor, MotionEvent.ACTION_DOWN, 320f, 320f);
            dispatch(editor, MotionEvent.ACTION_UP, 320f, 320f);
            Bitmap erasedSnapshot = editor.snapshot();
            erasedPixels.set(nonTransparentPixels(erasedSnapshot));
            erasedSnapshot.recycle();

            editor.setTool(ProfileImageEditorView.Tool.CIRCLE);
            editor.setCircleFilled(true);
            editor.setColor(Color.BLUE);
            editor.setOpacity(180);
            dispatch(editor, MotionEvent.ACTION_DOWN, 150f, 500f);
            dispatch(editor, MotionEvent.ACTION_MOVE, 260f, 500f);
            dispatch(editor, MotionEvent.ACTION_UP, 260f, 500f);
            result.set(editor.snapshot());
        });

        Bitmap bitmap = result.get();
        assertEquals(32, bitmap.getWidth());
        assertEquals(32, bitmap.getHeight());
        assertTrue(brushPixels.get() > 10);
        assertTrue(erasedPixels.get() < brushPixels.get());
        assertTrue(nonTransparentPixels(bitmap) > erasedPixels.get());
        bitmap.recycle();
    }

    private static void dispatch(View view, int action, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private static int nonTransparentPixels(Bitmap bitmap) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    count++;
                }
            }
        }
        return count;
    }
}
