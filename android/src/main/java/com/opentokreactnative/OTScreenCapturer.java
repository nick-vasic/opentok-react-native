package com.opentokreactnative;

import android.content.Context;
import android.graphics.Bitmap;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.opentok.android.BaseVideoCapturer;

public class OTScreenCapturer extends BaseVideoCapturer {

    private boolean capturing = false;
    private final Context context;

    private int fps = 15;
    private int width = 20;
    private int height = 20;
    private int[] frame;

    private Handler mHandler = new Handler(Looper.getMainLooper());

    private Runnable newFrame = new Runnable() {
        @Override
        public void run() {
            Bitmap bitmap = ScreenCaptureImageActivity.latestBitmap;
            if (capturing && bitmap != null && !bitmap.isRecycled()) {
                int bitmapWidth = bitmap.getWidth();
                int bitmapHeight = bitmap.getHeight();

                if (frame == null ||
                        OTScreenCapturer.this.width != bitmapWidth ||
                        OTScreenCapturer.this.height != bitmapHeight) {

                    OTScreenCapturer.this.width = bitmapWidth;
                    OTScreenCapturer.this.height = bitmapHeight;
                    frame = new int[bitmapWidth * bitmapHeight];
                }

                bitmap.getPixels(frame, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight);
                provideIntArrayFrame(frame, ARGB, bitmapWidth, bitmapHeight, 0, false);

            }
            if (capturing) {
                mHandler.postDelayed(newFrame, 1000 / fps);
            }
        }
    };

    public OTScreenCapturer(View view) {
        this.context = view.getContext();
    }

    @Override
    public void init() {

    }

    @Override
    public int startCapture() {
        capturing = true;
        Intent intent = new Intent(context, ScreenCaptureImageActivity.class);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
        mHandler.postDelayed(newFrame, 1000 / fps);
        return 0;
    }

    @Override
    public int stopCapture() {
        capturing = false;
        mHandler.removeCallbacks(newFrame);
        Intent intent = new Intent(context, ScreenCaptureMediaProjectionService.class);
        context.stopService(intent);
        return 0;
    }

    @Override
    public boolean isCaptureStarted() {
        return capturing;
    }

    @Override
    public CaptureSettings getCaptureSettings() {

        CaptureSettings settings = new CaptureSettings();
        settings.fps = fps;
        settings.width = width;
        settings.height = height;
        settings.format = ARGB;
        return settings;
    }

    @Override
    public void destroy() {

    }

    @Override
    public void onPause() {

    }

    @Override
    public void onResume() {

    }

}
