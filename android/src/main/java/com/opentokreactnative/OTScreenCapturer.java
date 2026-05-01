package com.opentokreactnative;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import com.opentok.android.BaseVideoCapturer;

public class OTScreenCapturer extends BaseVideoCapturer {

    private boolean capturing = false;
    private boolean hostLifecyclePause = false;
    private Activity currentActivity;

    private int fps = 15;
    private int width = 20;
    private int height = 20;
    private int[] frame;

    private Canvas canvas;
    private Handler mHandler = new Handler();

    private Runnable newFrame = new Runnable() {
        @Override
        public void run() {
            if (capturing && ScreenCaptureImageActivity.latestBitmap != null &&
            !ScreenCaptureImageActivity.latestBitmap.isRecycled()) {

                if (frame == null ||
                        ScreenCaptureImageActivity.latestBitmap.getWidth() != width ||
                        ScreenCaptureImageActivity.latestBitmap.getHeight() != height) {

                    OTScreenCapturer.this.width = ScreenCaptureImageActivity.latestBitmap.getWidth();
                    OTScreenCapturer.this.height = ScreenCaptureImageActivity.latestBitmap.getHeight();

                    //canvas = new Canvas(ScreenCaptureImageActivity.latestBitmap);
                    frame = new int[width * height];
                }
                ScreenCaptureImageActivity.latestBitmap.getPixels(frame, 0, width, 0, 0, width, height);
                provideIntArrayFrame(frame, ARGB, width, height, 0, false);
            }
            mHandler.postDelayed(newFrame, 1000 / fps);
        }
    };

    public OTScreenCapturer(Activity currentActivity) {
        this.currentActivity = currentActivity;
    }

    private void requestProjectionStop() {
        if (currentActivity == null) {
            Log.e("ScreenShare", "[LegacyCapturer] requestProjectionStop: currentActivity is null; cannot dispatch ACTION_STOP");
            return;
        }
        if (!ScreenCaptureMediaProjectionService.isProjectionActive()) {
            Log.d("ScreenShare", "[LegacyCapturer] requestProjectionStop: projection already inactive, skipping ACTION_STOP");
            return;
        }
        Intent stopIntent = new Intent(currentActivity, ScreenCaptureMediaProjectionService.class);
        stopIntent.setAction(ScreenCaptureMediaProjectionService.ACTION_STOP);
        currentActivity.startService(stopIntent);
    }

    @Override
    public void init() {

    }

    @Override
    public int startCapture() {
        hostLifecyclePause = false;
        if (capturing) {
            Log.d("ScreenShare", "[LegacyCapturer] startCapture: already capturing, ignoring duplicate start");
            return 0;
        }
        if (ScreenCaptureMediaProjectionService.isProjectionActive()) {
            Log.d("ScreenShare", "[LegacyCapturer] startCapture: projection already active, skipping consent activity launch");
            capturing = true;
            mHandler.postDelayed(newFrame, 1000 / fps);
            return 0;
        }
        if (ScreenCaptureImageActivity.consentInProgress) {
            Log.d("ScreenShare", "[LegacyCapturer] startCapture: consent already in progress, skipping duplicate activity launch");
            capturing = true;
            mHandler.postDelayed(newFrame, 1000 / fps);
            return 0;
        }
        Log.d("ScreenShare", "[LegacyCapturer] startCapture: launching ScreenCaptureImageActivity currentActivity=" + (currentActivity != null ? currentActivity.getClass().getSimpleName() : "null"));
        capturing = true;
        Intent i = new Intent(this.currentActivity, ScreenCaptureImageActivity.class);
        currentActivity.startActivity(i);
        mHandler.postDelayed(newFrame, 1000 / fps);
        return 0;
    }

    @Override
    public int stopCapture() {
        Log.d("ScreenShare", "[LegacyCapturer] stopCapture: called captureActivity=" + (ScreenCaptureImageActivity.captureActivity != null ? "set" : "null") + " projectionActive=" + ScreenCaptureMediaProjectionService.isProjectionActive() + " hostLifecyclePause=" + hostLifecyclePause);
        capturing = false;
        mHandler.removeCallbacks(newFrame);
        if (hostLifecyclePause && ScreenCaptureMediaProjectionService.isProjectionActive()) {
            // App going to background while screen share is active — keep the
            // MediaProjection service running so it can resume without a new consent dialog.
            Log.d("ScreenShare", "[LegacyCapturer] stopCapture: projection still active, pausing frame loop only");
        } else {
            // Explicit stop: always ask service to stop MediaProjection.
            Log.d("ScreenShare", "[LegacyCapturer] stopCapture: dispatching ACTION_STOP to projection service");
            requestProjectionStop();

            if (ScreenCaptureImageActivity.latestBitmap != null) {
                ScreenCaptureImageActivity.latestBitmap.recycle();
                ScreenCaptureImageActivity.latestBitmap = null;
            }
            if (ScreenCaptureImageActivity.captureActivity != null) {
                Log.d("ScreenShare", "[LegacyCapturer] stopCapture: finishing capture activity");
                ScreenCaptureImageActivity.captureActivity.finish();
                ScreenCaptureImageActivity.captureActivity = null;
            }
            ScreenCaptureImageActivity.consentInProgress = false;
        }
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
        hostLifecyclePause = true;
        Log.d("ScreenShare", "[LegacyCapturer] onPause: suspending frame loop");
        mHandler.removeCallbacks(newFrame);
    }

    @Override
    public void onResume() {
        hostLifecyclePause = false;
        Log.d("ScreenShare", "[LegacyCapturer] onResume: projectionActive=" + ScreenCaptureMediaProjectionService.isProjectionActive() + " capturing=" + capturing);
        if (ScreenCaptureMediaProjectionService.isProjectionActive() && capturing) {
            Log.d("ScreenShare", "[LegacyCapturer] onResume: resuming frame loop");
            mHandler.postDelayed(newFrame, 1000 / fps);
        }
    }
}
