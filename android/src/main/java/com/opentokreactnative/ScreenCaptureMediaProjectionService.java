package com.opentokreactnative;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.nio.ByteBuffer;

public class ScreenCaptureMediaProjectionService extends Service {

    public static final String ACTION_START = "com.opentokreactnative.action.START_SCREEN_CAPTURE";
    public static final String ACTION_STOP = "com.opentokreactnative.action.STOP_SCREEN_CAPTURE";
    public static final String EXTRA_RESULT_DATA = "com.opentokreactnative.extra.RESULT_DATA";

    private static final String TAG = "OTScreenCapture";
    private static final String NOTIFICATION_CHANNEL_ID = "opentok_screen_capture";
    private static final int SERVICE_ID = 123;
    private static final String SCREENCAP_NAME = "opentok-screencap";
    private static final int VIRTUAL_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private int density;
    private Display display;
    private int width;
    private int height;
    private int rotation;
    private VirtualDisplay virtualDisplay;
    private OrientationChangeCallback orientationChangeCallback;
    private MediaProjectionStopCallback mediaProjectionStopCallback;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private ImageReader imageReader;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        captureThread = new HandlerThread("OTScreenCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Screen sharing")
                .setContentText("Screen sharing is active")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        ServiceCompat.startForeground(
                this,
                SERVICE_ID,
                notification,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        : 0
        );

        mediaProjectionManager = (MediaProjectionManager)
                getApplicationContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_START.equals(action)) {
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, resultData);
            if (mediaProjection != null) {
                startProjection();
                return Service.START_STICKY;
            }
        }

        stopProjection();
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopProjection(false);
        if (captureThread != null) {
            captureThread.quitSafely();
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Screen Capture",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void startProjection() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        density = metrics.densityDpi;
        WindowManager window = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        display = window.getDefaultDisplay();

        mediaProjectionStopCallback = new MediaProjectionStopCallback();
        mediaProjection.registerCallback(mediaProjectionStopCallback, captureHandler);

        createVirtualDisplay();

        orientationChangeCallback = new OrientationChangeCallback(this);
        if (orientationChangeCallback.canDetectOrientation()) {
            orientationChangeCallback.enable();
        }
    }

    public void stopProjection() {
        stopProjection(true);
    }

    private void stopProjection(final boolean stopService) {
        if (captureHandler == null) {
            return;
        }

        captureHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mediaProjection != null) {
                    mediaProjection.stop();
                    mediaProjection = null;
                } else {
                    releaseProjectionResources();
                }
                if (stopService) {
                    stopSelf();
                }
            }
        });
    }

    private void createVirtualDisplay() {
        createImageReader();
        virtualDisplay = mediaProjection.createVirtualDisplay(
                SCREENCAP_NAME,
                width,
                height,
                density,
                VIRTUAL_DISPLAY_FLAGS,
                imageReader.getSurface(),
                null,
                captureHandler
        );
        imageReader.setOnImageAvailableListener(new ImageAvailableListener(), captureHandler);
    }

    private void createImageReader() {
        Point size = new Point();
        display.getRealSize(size);
        width = size.x;
        height = size.y;
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
    }

    private void updateVirtualDisplaySurface() {
        ImageReader oldImageReader = imageReader;
        createImageReader();
        imageReader.setOnImageAvailableListener(new ImageAvailableListener(), captureHandler);

        if (virtualDisplay != null) {
            virtualDisplay.resize(width, height, density);
            virtualDisplay.setSurface(imageReader.getSurface());
        }

        if (oldImageReader != null) {
            oldImageReader.setOnImageAvailableListener(null, null);
            oldImageReader.close();
        }
    }

    private void releaseVirtualDisplay() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
    }

    private void releaseProjectionResources() {
        releaseVirtualDisplay();
        if (orientationChangeCallback != null) {
            orientationChangeCallback.disable();
            orientationChangeCallback = null;
        }
        if (mediaProjection != null && mediaProjectionStopCallback != null) {
            mediaProjection.unregisterCallback(mediaProjectionStopCallback);
            mediaProjectionStopCallback = null;
        }
    }

    private class OrientationChangeCallback extends OrientationEventListener {
        OrientationChangeCallback(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            final int currentRotation = display.getRotation();
            if (currentRotation != rotation) {
                rotation = currentRotation;
                try {
                    updateVirtualDisplaySurface();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to recreate virtual display", e);
                }
            }
        }
    }

    private class MediaProjectionStopCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            captureHandler.post(new Runnable() {
                @Override
                public void run() {
                    releaseProjectionResources();
                    mediaProjection = null;
                }
            });
        }
    }

    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            Bitmap bitmap = null;

            try {
                image = reader.acquireLatestImage();
                if (image == null) {
                    return;
                }

                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * width;

                bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                );
                bitmap.copyPixelsFromBuffer(buffer);

                Bitmap capturedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                Bitmap previousBitmap = ScreenCaptureImageActivity.latestBitmap;
                ScreenCaptureImageActivity.latestBitmap = capturedBitmap;
                if (previousBitmap != null && !previousBitmap.isRecycled()) {
                    previousBitmap.recycle();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to capture screen frame", e);
            } finally {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                if (image != null) {
                    image.close();
                }
            }
        }
    }
}
