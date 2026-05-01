package com.opentokreactnative;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;


public class ScreenCaptureMediaProjectionService extends Service {


    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_PREPARE = "ACTION_PREPARE";
    public static final String EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA";

    private static final String NOTIFICATION_CHANNEL_ID = "Screen Capture Channel";
    private static final int SERVICE_ID = 123;

    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private int mDensity;
    private Display mDisplay;
    private int mWidth;
    private int mHeight;
    private int mRotation;
    private VirtualDisplay mVirtualDisplay;
    private OrientationChangeCallback mOrientationChangeCallback;
    private Handler mHandler;
    private MediaProjectionManager mProjectionManager;
    private ImageReader mImageReader;
    private static final String SCREENCAP_NAME = "screencap";
    private static volatile boolean projectionActive = false;
    private static final int VIRTUAL_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

    public static boolean isProjectionActive() {
        return projectionActive;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // start capture handling thread
        new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                mHandler = new Handler();
                Looper.loop();
            }
        }.start();

        startForeground(
                SERVICE_ID,
                new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID).build()
        );

        // use applicationContext to avoid memory leak on Android 10.
        mediaProjectionManager = (MediaProjectionManager)
                getApplicationContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Screen Capture Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w("ScreenShare", "[Service] onStartCommand: null intent (system restart), ignoring");
            return Service.START_NOT_STICKY;
        }
        String action = intent.getAction();
        Log.d("ScreenShare", "[Service] onStartCommand: action=" + action + " mediaProjection=" + (mediaProjection != null ? "set" : "null"));
        if (ACTION_PREPARE.equals(action)) {
            Log.d("ScreenShare", "[Service] ACTION_PREPARE: service in foreground, awaiting consent dialog result");
            return Service.START_STICKY;
        } else if (ACTION_START.equals(action)) {
            Intent resultData;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            } else {
                resultData = (Intent) intent.getParcelableExtra(EXTRA_RESULT_DATA);
            }
            Log.d("ScreenShare", "[Service] ACTION_START: resultData=" + (resultData != null ? "present" : "null"));
            if (resultData != null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, resultData);
                Log.d("ScreenShare", "[Service] getMediaProjection result: " + (mediaProjection != null ? "ok" : "null"));
            }
            if (mediaProjection != null) {
                startProjection();
            } else {
                Log.e("ScreenShare", "[Service] ACTION_START: mediaProjection is null, cannot start projection");
            }
            return Service.START_STICKY;
        } else {
            Log.d("ScreenShare", "[Service] ACTION_STOP (or unknown action=" + action + "): stopping projection");
            stopProjection();
            return Service.START_NOT_STICKY;
        }
    }

    private void startProjection() {
        Log.d("ScreenShare", "[Service] startProjection: called");
        // display metrics
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mDensity = metrics.densityDpi;
        WindowManager window = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mDisplay = window.getDefaultDisplay();

        // register media projection stop callback BEFORE createVirtualDisplay (required on API 34+)
        mediaProjection.registerCallback(new MediaProjectionStopCallback(), mHandler);
        Log.d("ScreenShare", "[Service] startProjection: callback registered");

        // create virtual display depending on device width / height
        createVirtualDisplay();
        projectionActive = true;
        Log.d("ScreenShare", "[Service] startProjection: virtual display created " + mWidth + "x" + mHeight);

        // register orientation change callback
        mOrientationChangeCallback = new OrientationChangeCallback(this);
        if (mOrientationChangeCallback.canDetectOrientation()) {
            mOrientationChangeCallback.enable();
        }
    }

    public void stopProjection() {
        Log.d("ScreenShare", "[Service] stopProjection: called, mediaProjection=" + (mediaProjection != null ? "set" : "null"));
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mediaProjection != null) {
                    Log.d("ScreenShare", "[Service] stopProjection: stopping mediaProjection");
                    mediaProjection.stop();
                    mediaProjection = null;
                }
                projectionActive = false;
                Log.d("ScreenShare", "[Service] stopProjection: calling stopSelf");
                stopSelf();
            }
        });
    }

    /****************************************** Factoring Virtual Display creation ****************/
    private void createVirtualDisplay() {
        // get width and height
        Point size = new Point();
        mDisplay.getRealSize(size);
        mWidth = size.x;
        mHeight = size.y;

        // start capture reader
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = mediaProjection.createVirtualDisplay(SCREENCAP_NAME, mWidth, mHeight,
                mDensity, VIRTUAL_DISPLAY_FLAGS, mImageReader.getSurface(), null, mHandler);
        mImageReader.setOnImageAvailableListener(new ImageAvailableListener(), mHandler);
    }


    /************************* Media Projection Callback Listeners *******************************/
    private class OrientationChangeCallback extends OrientationEventListener {

        OrientationChangeCallback(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            final int rotation = mDisplay.getRotation();
            if (rotation != mRotation) {
                mRotation = rotation;
                try {
                    // clean up
                    if (mVirtualDisplay != null) mVirtualDisplay.release();
                    if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);

                    // re-create virtual display depending on device width / height
                    createVirtualDisplay();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class MediaProjectionStopCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            Log.d("ScreenShare", "[Service] MediaProjectionStopCallback.onStop: projection stopped by system/user");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mVirtualDisplay != null) { mVirtualDisplay.release(); mVirtualDisplay = null; Log.d("ScreenShare", "[Service] onStop: virtualDisplay released"); }
                    if (mImageReader != null) { mImageReader.setOnImageAvailableListener(null, null); mImageReader = null; Log.d("ScreenShare", "[Service] onStop: imageReader released"); }
                    if (mOrientationChangeCallback != null) mOrientationChangeCallback.disable();
                    if (mediaProjection != null) {
                        mediaProjection.unregisterCallback(MediaProjectionStopCallback.this);
                        mediaProjection = null;
                        Log.d("ScreenShare", "[Service] onStop: mediaProjection unregistered and nulled");
                    }
                    projectionActive = false;
                    Log.d("ScreenShare", "[Service] onStop: calling stopSelf");
                    stopSelf();
                }
            });
        }
    }

    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            FileOutputStream fos = null;
            Bitmap bitmap = null;

            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * mWidth;

                    // create bitmap
                    bitmap = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.ARGB_8888);

                    bitmap.copyPixelsFromBuffer(buffer);

                    Bitmap realSizeBitmap = Bitmap.createBitmap(bitmap, 0, 0, mWidth, bitmap.getHeight());
                    ScreenCaptureImageActivity.latestBitmap = realSizeBitmap;
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                    }
                }

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
