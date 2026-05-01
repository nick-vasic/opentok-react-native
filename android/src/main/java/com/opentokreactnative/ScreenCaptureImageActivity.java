package com.opentokreactnative;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;


public class ScreenCaptureImageActivity extends Activity {

    private static final String TAG = ScreenCaptureImageActivity.class.getName();
    private static final int REQUEST_CODE = 100;

    public static ScreenCaptureImageActivity captureActivity;
    public static Bitmap latestBitmap;
    public static volatile boolean consentInProgress;


    /****************************************** Activity Lifecycle methods ************************/
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.captureActivity = this;
        Log.d("ScreenShare", "[Activity] onCreate: starting projection flow");

        if (ScreenCaptureMediaProjectionService.isProjectionActive()) {
            Log.d("ScreenShare", "[Activity] onCreate: projection already active, finishing without showing consent dialog");
            finish();
            return;
        }


        // start projection
        startProjection();

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d("ScreenShare", "[Activity] onActivityResult: requestCode=" + requestCode + " resultCode=" + resultCode + " data=" + (data != null ? "present" : "null"));
        if (requestCode == REQUEST_CODE) {
            consentInProgress = false;
            if (resultCode == RESULT_OK && data != null) {
                // User approved — start the projection
                Log.d("ScreenShare", "[Activity] onActivityResult: user APPROVED, sending ACTION_START");
                Intent mediaProjectionIntent = new Intent(this, ScreenCaptureMediaProjectionService.class);
                mediaProjectionIntent.setAction(ScreenCaptureMediaProjectionService.ACTION_START);
                mediaProjectionIntent.putExtra(ScreenCaptureMediaProjectionService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(mediaProjectionIntent);
                } else {
                    startService(mediaProjectionIntent);
                }
            } else {
                // User cancelled — no service to start/stop because we don't pre-start it.
                Log.d("ScreenShare", "[Activity] onActivityResult: user CANCELLED, no service action");
            }

            /*if (sMediaProjection != null) {
                File externalFilesDir = getExternalFilesDir(null);
                if (externalFilesDir != null) {
                    STORE_DIRECTORY = externalFilesDir.getAbsolutePath() + "/screenshots/";
                    File storeDirectory = new File(STORE_DIRECTORY);
                    if (!storeDirectory.exists()) {
                        boolean success = storeDirectory.mkdirs();
                        if (!success) {
                            Log.e(TAG, "failed to create file storage directory.");
                            return;
                        }
                    }
                } else {
                    Log.e(TAG, "failed to create file storage directory, getExternalFilesDir is null.");
                    return;
                }


            }*/
        }
        // Does this need to finish?
        Log.d("ScreenShare", "[Activity] onActivityResult: finishing activity");
        this.finish();
    }

    /****************************************** UI Widget Callbacks *******************************/
    private void startProjection() {
        if (consentInProgress) {
            Log.d("ScreenShare", "[Activity] startProjection: consent already in progress, skipping duplicate dialog launch");
            return;
        }
        consentInProgress = true;

        // Request consent first; service is started only after RESULT_OK.
        Log.d("ScreenShare", "[Activity] startProjection: showing consent dialog");

        MediaProjectionManager projectionManager =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        Intent captureIntent;
        Log.d("ScreenShare", "[Activity] startProjection: creating capture intent for Android version " + Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            MediaProjectionConfig config = MediaProjectionConfig.createConfigForDefaultDisplay();
            captureIntent = projectionManager.createScreenCaptureIntent(config);
        } else {
            captureIntent = projectionManager.createScreenCaptureIntent();
        }
        startActivityForResult(captureIntent, REQUEST_CODE);
    }

    public void stopProjection() {
        // Send stop action to service
        Log.d("ScreenShare", "[Activity] stopProjection: sending ACTION_STOP");
        if (!ScreenCaptureMediaProjectionService.isProjectionActive()) {
            Log.d("ScreenShare", "[Activity] stopProjection: projection already inactive, skipping ACTION_STOP");
            return;
        }
        Intent mediaProjectionIntent =  new Intent(this,
                ScreenCaptureMediaProjectionService.class);
        mediaProjectionIntent.setAction(ScreenCaptureMediaProjectionService.ACTION_STOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(mediaProjectionIntent);
        } else {
            startService(mediaProjectionIntent);
        }
    }

}
