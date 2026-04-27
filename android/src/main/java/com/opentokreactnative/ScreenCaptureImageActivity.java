package com.opentokreactnative;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

public class ScreenCaptureImageActivity extends Activity {

    private static final int REQUEST_CODE = 100;

    public static volatile android.graphics.Bitmap latestBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startProjection();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Intent mediaProjectionIntent =
                    new Intent(this, ScreenCaptureMediaProjectionService.class);
            mediaProjectionIntent.setAction(ScreenCaptureMediaProjectionService.ACTION_START);
            mediaProjectionIntent.putExtra(ScreenCaptureMediaProjectionService.EXTRA_RESULT_DATA, data);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(mediaProjectionIntent);
            } else {
                startService(mediaProjectionIntent);
            }
        }

        finish();
    }

    private void startProjection() {
        MediaProjectionManager projectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE);
    }
}
