package org.ftd.gyn;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLayoutChangeListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.sql.BatchUpdateException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Created by sdduser on 17-12-15.
 */

public class IrisguiActicity extends Activity
        implements TextureView.SurfaceTextureListener, Camera.ErrorCallback,
        Camera.PreviewCallback, DataCallback {

    private static final String TAG = "Iris-Gui";

    // -----------------------------------------------------
    // statics
    static IrisguiActicity app = null;
    // -----------------------------------------------------

    // -----------------------------------------------------
    // ui
    private TextView tvStat = null;
    private TextView tvData = null;
    private TextView tvBuffer = null;
    private ImageView mImage;
    private EditText mEditOpen;
    private EditText mEditFrameRate;
    private EditText mEditFrameWidth;
    private EditText mEditFrameHeight;
    private EditText mEditRegisterAddr;
    private EditText mEditRegisterValue;

    private TextView mTvFrameRate;
    private TextView mTvFormat;
    private TextView mTvResolution;
    private TextView mTvx;
    private TextView mTvFoucs;

    private Button mSetFrameRate;
    private Button mSetFormat;
    private Spinner mFormats;
    private Spinner mInterfaceMode;
    private Spinner mFocusMode;
    private Button mSetResolution;
    private Button mDumpRaw;
    private ImageView mImageContent;
    private Button mGetBuffer;
    private Button mSetFocus;

    private SurfaceTexture mSurfaceTexture;
    private TextureView mTextureView;

    private static final boolean DEBUG = BuildConfig.IS_DEBUG;

    private Camera mCamera;
    private Camera.CameraInfo mCameraInfo;
    private Camera.Parameters mParams;

    private boolean mOpened = false;

    protected int mDefaultCameraId            = 2;//0: BACK, 1: FRONT, 2: IRIS
    private int mCurrentCameraId              = mDefaultCameraId;

    private final Object mSurfaceTextureLock  = new Object();

    private int mPreviewWidth                 = 0;
    private int mPreviewHeight                = 0;
    private int mLed1Level                    = 128;
    private int mLed2Level                    = 128;
    private int mCurrentFormatValue           = 0;//0x11: yuv420sp, 0x99: raw
    private int mCurrentFoucsMode             = 0;

    private Matrix mMatrix                    = null;
    private float mAspectRatio                = 4f / 3f;
    private int mDefaultWith                  = 1944;
    private int mDefaultHeight                = 1944;

    private int mCurrentWith                  = mDefaultWith;
    private int mCurrentHeight                = mDefaultHeight;

    private float mDefaultframeRate           = 30.0f;
    private float mCurrentframeRate           = mDefaultframeRate;

    private boolean mOrientationResize;
    private boolean mPrevOrientationResize;
    private boolean mAspectRatioResize;

    private static final String ADDRESS_PREFIX = "0x";

    private byte[] data;
    private byte[] alpha8Data;
    private byte[] one_frame_data;

    //fot test camera @hide api
    private Method startStreamMethod = null;
    private Method stopStreamMethod = null;
    private Method setFrameRateMethod = null;
    private Method setFormatMethod = null;
    private Method setLedMethod = null;
    private Method setResolutionMethod = null;
    private Method setFocusMethod = null;
    private Method closeMethod = null;
    private Method readRegisterMethod = null;
    private Method writeRegisterMethod = null;

    private Method registerMethod = null;
    private Method dumpMethod = null;

    private EventHandler mEventHandler;
    private DataCallback mDataCallback;

    private static final int IRIS_MSG_CONTINUE_RAW_FRAME    = 0x001;
    private static final int IRIS_MSG_ONE_RAW_FRAME    = 0x002;

    // -----------------------------------------------------
    //flag
    private boolean USE_JAVA_API             = true;

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    enum focus_mode_type {
        FOCUS_MODE_AUTO
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native int ApiInit();
    public native int ApiDeinit();

    public native int RawCam_Open(Object iris_this, int id);
    public native int RawCam_Close();
    public native int RawCam_StartStream();
    public native int RawCam_StopStream();
    public native int RawCam_ReadRegister(int addr);
    public native int RawCam_WriteRegister(int addr, int value);
    public native int RawCam_SetLed(int led1, int led2, int ledType);
    public native void RawCam_GetBuffer();
    public native int RawCam_RegisterFrameCallback();
    public native int RawCam_SetFps(float fixedFps);
    public native int RawCam_SetResolution(int width, int height);
    public native int RawCam_SetFocus(int mode);
    public native int RawCam_SetFormat(int format);

    // ------------------------------------------------------
    public IrisguiActicity() {
        app = this;
    }

    public boolean checkNull(boolean show) {
        if (mCamera == null && show) {
            Toast.makeText(this, R.string.open_camera_note, Toast.LENGTH_SHORT).show();
        }
        return mCamera == null;
    }

    public boolean checkStringNull(String str, int resId) {
        boolean isEmpty = TextUtils.isEmpty(str);
        if (isEmpty) {
            Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
        }
        return isEmpty;
    }

    private void prompt_user(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }

        ApiInit();

        mCameraInfo = initCameraInfo();
        mainScreen();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (USE_JAVA_API) {
            if (!checkNull(false)) {
                mCamera.setPreviewCallback(null);

                boolean previewEnabled;
                try {
                    Method previewEnabledMethod = mCamera.getClass().getMethod("previewEnabled");
                    previewEnabled = (boolean) previewEnabledMethod.invoke(mCamera);
                    if (previewEnabled) {
                        doStopStream();
                        doClose();
                    }
                } catch (NoSuchMethodException
                        | IllegalArgumentException
                        | InvocationTargetException
                        | IllegalAccessException e) {
                    e.printStackTrace();
                }
                if (DEBUG)
                    Log.i(TAG, "camera has been destroyed");
            }
        } else {
            if (mOpened) {
                RawCam_StopStream();
                RawCam_Close();
            }
        }

        ApiDeinit();
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (USE_JAVA_API) {
            if (!checkNull(false)) {
                doStopStream();
                doClose();
            }
        } else {
            if (mOpened) {
                RawCam_StopStream();
                RawCam_Close();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        //doOpen();
    }

    private void initMethod() {
        try {
            startStreamMethod = mCamera.getClass().getMethod("RawCam_StartStream");
            stopStreamMethod = mCamera.getClass().getMethod("RawCam_StopStream");
            setFrameRateMethod = mCamera.getParameters().getClass().getMethod("RawCam_SetFrameRate",
                    new Class[]{int.class});
            setFormatMethod = mCamera.getParameters().getClass().getMethod("RawCam_SetFormat",
                    new Class[]{int.class});
            setLedMethod = mCamera.getParameters().getClass().getMethod("RawCam_SetLed",
                    new Class[]{int.class, int.class});
            setResolutionMethod = mCamera.getParameters().getClass().getMethod("RawCam_SetResolution",
                    new Class[]{int.class, int.class});
            closeMethod = mCamera.getClass().getMethod("RawCam_Close");
            readRegisterMethod = mCamera.getParameters().getClass().getMethod("RawCam_ReadRegister",
                    new Class[]{int.class, int.class, int.class});
            writeRegisterMethod = mCamera.getParameters().getClass().getMethod("RawCam_WriteRegister",
                    new Class[]{int.class, int.class, int.class});

            registerMethod = mCamera.getParameters().getClass().getMethod("setSensorParams",
                    new Class[]{int.class, int.class, int.class, int.class});

            dumpMethod = mCamera.getParameters().getClass().getMethod("dump");

        } catch (NoSuchMethodException
                | IllegalArgumentException e) {
            e.printStackTrace();
        }
    }

    private void setUpUI() {
        if (USE_JAVA_API) {
            mTvFrameRate.setVisibility(View.VISIBLE);
            mTvFormat.setVisibility(View.VISIBLE);
            mTvResolution.setVisibility(View.VISIBLE);
            mTvx.setVisibility(View.VISIBLE);
            mEditOpen.setVisibility(View.VISIBLE);
            mEditFrameRate.setVisibility(View.VISIBLE);
            mEditFrameWidth.setVisibility(View.VISIBLE);
            mEditFrameHeight.setVisibility(View.VISIBLE);
            mSetFrameRate.setVisibility(View.VISIBLE);
            mSetFormat.setVisibility(View.VISIBLE);
            mFormats.setVisibility(View.VISIBLE);
            mSetResolution.setVisibility(View.VISIBLE);
            mGetBuffer.setVisibility(View.GONE);
            tvBuffer.setVisibility(View.GONE);
            //mDumpRaw.setVisibility(View.VISIBLE);

            mTvFoucs.setVisibility(View.GONE);
            mFocusMode.setVisibility(View.GONE);
            mSetFocus.setVisibility(View.GONE);
        } else {
            //FrameRate
            mTvFrameRate.setVisibility(View.VISIBLE);
            mEditFrameRate.setVisibility(View.VISIBLE);
            mSetFrameRate.setVisibility(View.VISIBLE);

            //Format
            //mTvFormat.setVisibility(View.GONE);
            //mFormats.setVisibility(View.GONE);
            //mSetFormat.setVisibility(View.GONE);

            //open
            //mEditOpen.setVisibility(View.GONE);

            tvBuffer.setVisibility(View.GONE);
            mGetBuffer.setVisibility(View.GONE);

            //mDumpRaw.setVisibility(View.GONE);

            //Resolution
            mTvResolution.setVisibility(View.GONE);
            mEditFrameWidth.setVisibility(View.GONE);
            mTvx.setVisibility(View.GONE);
            mEditFrameHeight.setVisibility(View.GONE);
            mSetResolution.setVisibility(View.GONE);

            //Foucs
            mTvFoucs.setVisibility(View.VISIBLE);
            mFocusMode.setVisibility(View.VISIBLE);
            mSetFocus.setVisibility(View.VISIBLE);
        }
    }

    //-----------------------------------------------------
    // create main screen
    private void mainScreen() {
        // Inflate our UI from its XML layout description.
        setContentView(R.layout.activity_iris_main);

        mTvFrameRate = (TextView) findViewById(R.id.tvFrameRate);
        mTvFormat = (TextView) findViewById(R.id.tvFormat);
        mTvResolution = (TextView) findViewById(R.id.tvResolution);
        mTvx = (TextView) findViewById(R.id.x);
        mTvFoucs = (TextView) findViewById(R.id.tvFoucs);

        // Hook up button presses to the appropriate event handler.
        mEditOpen = (EditText) findViewById(R.id.editOpen);
        mEditFrameRate = (EditText) findViewById(R.id.editFrameRate);
        mEditFrameWidth = (EditText) findViewById(R.id.editFrameWidth);
        mEditFrameHeight = (EditText) findViewById(R.id.editFrameHeight);
        mEditRegisterAddr = (EditText) findViewById(R.id.editRegisterAddr);

        mEditRegisterAddr.setFocusable(true);
        mEditRegisterAddr.setFocusableInTouchMode(true);
        mEditRegisterAddr.requestFocus();

        //mEditRegisterAddr.setText(ADDRESS_PREFIX);
        //mEditRegisterAddr.setSelection(ADDRESS_PREFIX.length());
        mEditRegisterValue = (EditText) findViewById(R.id.editRegisterValue);
        //mEditRegisterValue.setText(ADDRESS_PREFIX);
        //mEditRegisterValue.setSelection(ADDRESS_PREFIX.length());
        ((Button) findViewById(R.id.open)).setOnClickListener(mOpenListener);
        ((Button) findViewById(R.id.close)).setOnClickListener(mCloseListener);
        ((Button) findViewById(R.id.stopStream)).setOnClickListener(mStopStreamListener);
        (mSetFrameRate = (Button) findViewById(R.id.setFrameRate)).setOnClickListener(mSetFrameRateListener);
        (mFormats = (Spinner) findViewById(R.id.imageFormat)).setOnItemSelectedListener(mFormatChangeListener);
        (mSetFormat = (Button) findViewById(R.id.setFormat)).setOnClickListener(mSetFormatListener);
        ((Button) findViewById(R.id.startStream)).setOnClickListener(mStartStreamListener);
        ((Button) findViewById(R.id.stopStream)).setOnClickListener(mStopStreamListener);
        ((SeekBar) findViewById(R.id.setLed1)).setOnSeekBarChangeListener(mSetLedListener);
        ((SeekBar) findViewById(R.id.setLed2)).setOnSeekBarChangeListener(mSetLedListener);
        ((SeekBar) findViewById(R.id.setLed3)).setOnSeekBarChangeListener(mSetLedListener);
        ((SeekBar) findViewById(R.id.setLed4)).setOnSeekBarChangeListener(mSetLedListener);
        (mSetResolution = (Button) findViewById(R.id.setResolution)).setOnClickListener(mSetResolutionListener);
        (mDumpRaw = (Button) findViewById(R.id.dumpToFile)).setOnClickListener(mDumpRawListener);
        ((Button) findViewById(R.id.readRegister)).setOnClickListener(mRegisterListener);
        ((Button) findViewById(R.id.writeRegister)).setOnClickListener(mRegisterListener);
        (mInterfaceMode = (Spinner) findViewById(R.id.interfaceMode)).setOnItemSelectedListener(mInterfaceChangeListener);
        if (USE_JAVA_API) {
            mInterfaceMode.setSelection(0);
        } else {
            mInterfaceMode.setSelection(1);
        }
        ((Button) findViewById(R.id.setInterface)).setOnClickListener(mSetInterfaceListener);

        (mGetBuffer = (Button) findViewById(R.id.getBuffer)).setOnClickListener(mGetBufferListener);

        (mFocusMode = (Spinner) findViewById(R.id.focusMode)).setOnItemSelectedListener(mFoucsModeChangeListener);
        (mSetFocus = (Button) findViewById(R.id.setFocus)).setOnClickListener(mSetFoucsListener);

        mTextureView = (TextureView) findViewById(R.id.preview_content);
        mTextureView.setSurfaceTextureListener(this);
        mTextureView.removeOnLayoutChangeListener(mLayoutListener);
        mTextureView.addOnLayoutChangeListener(mLayoutListener);

        mImageContent = (ImageView) findViewById(R.id.image_content);

        mOrientationResize = false;
        mPrevOrientationResize = false;

        // ui items
        tvStat = (TextView) findViewById(R.id.textStatus);
        tvData = (TextView) findViewById(R.id.textData);
        tvBuffer = (TextView) findViewById(R.id.textBuffer);

        setUpUI();
    }

    private Camera.CameraInfo initCameraInfo() {
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        int cameraCount = Camera.getNumberOfCameras();
        for (int camIdx = 0; camIdx < cameraCount; camIdx++) {
            Camera.getCameraInfo(camIdx, cameraInfo);
            if (cameraInfo.facing == mDefaultCameraId) {
                mCurrentCameraId = camIdx;
                return cameraInfo;
            }
        }
        return null;
    }

    private OnLayoutChangeListener mLayoutListener = new OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int left, int top, int right,
                                   int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
            int width = right - left;
            int height = bottom - top;

            if(DEBUG) {
                Log.d(TAG, "width:" + width + ", height:" + height
                        + ", right:" + right + ", left:" + left
                        + ", bottom:" + bottom + ", top:" + top
                        + ", mPreviewWidth:" + mPreviewWidth + ", mPreviewHeight:" + mPreviewHeight
                        + ", mOrientationResize:" + mOrientationResize + ", mPrevOrientationResize:" + mPrevOrientationResize
                        + ", mAspectRatioResize:" + mAspectRatioResize);
            }
            if (mPreviewWidth != width || mPreviewHeight != height
                    || (mOrientationResize != mPrevOrientationResize)
                    || mAspectRatioResize) {
                mPreviewWidth = width;
                mPreviewHeight = height;
                setTransformMatrix(width, height);
                mAspectRatioResize = false;
            }
        }
    };

    private void setTransformMatrix(int width, int height) {
        mMatrix = mTextureView.getTransform(mMatrix);
        float scaleX = 1f, scaleY = 1f;
        float scaledTextureWidth, scaledTextureHeight;
        if (mOrientationResize) {
            scaledTextureWidth = height * mAspectRatio;
            if (scaledTextureWidth > width) {
                scaledTextureWidth = width;
                scaledTextureHeight = scaledTextureWidth / mAspectRatio;
            } else {
                scaledTextureHeight = height;
            }
        } else {
            if (width > height) {
                scaledTextureWidth = Math.max(width, (height * mAspectRatio));
                scaledTextureHeight = Math.max(height, (width / mAspectRatio));
            } else {
                scaledTextureWidth = Math.max(width, (height / mAspectRatio));
                scaledTextureHeight = Math.max(height, (width * mAspectRatio));
            }
        }

        scaleX = scaledTextureWidth / width;
        scaleY = scaledTextureHeight / height;
        mMatrix.setScale(scaleX, scaleY, (float) width / 2, (float) height / 2);
        mTextureView.setTransform(mMatrix);

        // Calculate the new preview rectangle.
        RectF previewRect = new RectF(0, 0, width, height);
        mMatrix.mapRect(previewRect);
    }

    private Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) w / h;
        if (sizes == null)
            return null;

        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        // Try to find an size match aspect ratio and size
        for (Camera.Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE)
                continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    private void setAspectRatio(float ratio) {
        if (ratio <= 0.0)
            throw new IllegalArgumentException();

        if (mOrientationResize
                && getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT) {
            ratio = 1 / ratio;
        }

        if (DEBUG)
            Log.d(TAG, "setAspectRatio() ratio[" + ratio + "] mAspectRatio["
                + mAspectRatio + "]");
        mAspectRatio = ratio;
        mAspectRatioResize = true;
        mTextureView.requestLayout();
    }

    private void cameraOrientationPreviewResize(boolean orientation) {
        mPrevOrientationResize = mOrientationResize;
        mOrientationResize = orientation;
    }

    private void setPreviewFrameLayoutCameraOrientation() {
        // if camera mount angle is 0 or 180, we want to resize preview
        if (mCameraInfo.orientation % 180 == 0) {
            cameraOrientationPreviewResize(true);
        } else {
            cameraOrientationPreviewResize(false);
        }
    }

    private void resizeForPreviewAspectRatio(Camera.Parameters params) {
        setPreviewFrameLayoutCameraOrientation();
        Camera.Size size = params.getPreviewSize();
        if (DEBUG)
            Log.d(TAG, "Width = " + size.width + "Height = " + size.height);
        setAspectRatio((float) size.width / size.height);
    }

    private static int getDisplayRotation(Context context) {
        int rotation = ((Activity) context).getWindowManager()
                .getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                return 0;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
        }
        return 0;
    }

    private static int getDisplayOrientation(int degrees, int cameraId) {
        // See android.hardware.Camera.setDisplayOrientation for
        // documentation.
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360; // compensate the mirror
        } else { // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    private void setDisplayOrientation() {
        int mDisplayRotation = getDisplayRotation(this);
        int mDisplayOrientation = getDisplayOrientation(mDisplayRotation, mCurrentCameraId);
        int mCameraDisplayOrientation = mDisplayOrientation;
        // Change the camera display orientation
        mCamera.setDisplayOrientation(mCameraDisplayOrientation);
    }

    // ------------------------------------------------------
    // callback for SET FRAME RATE button press
    private OnClickListener mSetFrameRateListener = new OnClickListener() {
        public void onClick(View v) {
            try {
                mCurrentframeRate = Float.parseFloat(mEditFrameRate.getText().toString());
            } catch (NumberFormatException | NullPointerException e) {
                e.printStackTrace();
                Log.e(TAG, "frame rate is empty, use default.");
            }
            if (DEBUG) Log.d(TAG, "frameRate:" + mCurrentframeRate);
            if (USE_JAVA_API) {
                if (checkNull(true)) return;
                doSetFrameRate((int)mCurrentframeRate);
            } else {
                int ret = RawCam_SetFps(mCurrentframeRate);
                if (ret == 0)
                    prompt_user(R.string.prompt_suc);
                else
                    prompt_user(R.string.prompt_fail);
            }
        }
    };

    // ------------------------------------------------------
    // callback for Spinner change
    private OnItemSelectedListener mFormatChangeListener = new OnItemSelectedListener() {

        @Override
        public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
            mCurrentFormatValue = i;
        }

        @Override
        public void onNothingSelected(AdapterView<?> adapterView) {
            // Another interface callback
        }
    };

    // ------------------------------------------------------
    // callback for Spinner change
    private OnItemSelectedListener mInterfaceChangeListener = new OnItemSelectedListener() {

        @Override
        public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
            if (i == 0) {
                USE_JAVA_API = true;
            } else if (i == 1){
                USE_JAVA_API = false;
            }
            if (DEBUG) Log.d(TAG, "user interface:" + USE_JAVA_API);
        }

        @Override
        public void onNothingSelected(AdapterView<?> adapterView) {
            // Another interface callback
        }
    };

    // ------------------------------------------------------
    // callback for foucs Spinner change
    private OnItemSelectedListener mFoucsModeChangeListener = new OnItemSelectedListener() {

        @Override
        public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
            mCurrentFoucsMode = i;
        }

        @Override
        public void onNothingSelected(AdapterView<?> adapterView) {
            // Another interface callback
        }
    };

    // ------------------------------------------------------
    // callback for SET FOUCS button press
    private OnClickListener mSetFoucsListener = new OnClickListener() {
        public void onClick(View v) {
            if (DEBUG) Log.d(TAG, "mCurrentFoucsMode:" + mCurrentFoucsMode);
            int ret = RawCam_SetFocus(mCurrentFoucsMode);
            if (ret == 0)
                prompt_user(R.string.prompt_suc);
            else
                prompt_user(R.string.prompt_fail);
        }
    };

    // ------------------------------------------------------
    // callback for OPEN button press
    private OnClickListener mOpenListener = new OnClickListener() {
        public void onClick(View v) {
            String str = mEditOpen.getText().toString();
            if (!TextUtils.isEmpty(str) && TextUtils.isDigitsOnly(str)) {
                mCurrentCameraId = Integer.parseInt(str);
            }

            if (DEBUG)
                Log.d(TAG, "USE_JAVA_API:" + USE_JAVA_API);

            if (USE_JAVA_API) {
                doOpen();
            } else {
                int ret = RawCam_Open(new WeakReference<IrisguiActicity>(IrisguiActicity.this),
                        mCurrentCameraId);
                if (ret == 0) {
                    mOpened = true;
                    dspStat("camera opened");
                }
                else
                    dspStat("open camera failed");

            }
        }
    };

    // ------------------------------------------------------
    // callback for CLOSE button press
    private OnClickListener mCloseListener = new OnClickListener() {
        public void onClick(View v) {
            if (USE_JAVA_API) {
                if (checkNull(true)) return;
                doClose();
            } else {
                int ret = RawCam_Close();
                if (ret == 0) {
                    mOpened = false;
                    dspStat("camera closed");
                    dspData("data empty");
                    dspBuffer("buffer empty");
                }
                else
                    dspStat("close camera failed");
            }
        }
    };

    // ------------------------------------------------------
    // callback for SET FORMAT button press
    private OnClickListener mSetFormatListener = new OnClickListener() {
        public void onClick(View v) {
            if (USE_JAVA_API) {
                if (checkNull(true)) return;
                doSetFormat(mCurrentFormatValue);
            } else {
                Log.d(TAG, "mCurrentFormatValue:" + mCurrentFormatValue);
                int ret = RawCam_SetFormat(mCurrentFormatValue);
                if (ret == 0)
                    prompt_user(R.string.prompt_suc);
                else
                    prompt_user(R.string.prompt_fail);
            }
        }
    };

    // ------------------------------------------------------
    // callback for SET INTERFACE button press
    private OnClickListener mSetInterfaceListener = new OnClickListener() {
        public void onClick(View v) {
            mainScreen();
        }
    };

    // ------------------------------------------------------
    // callback for GET BUFFER button press
    private OnClickListener mGetBufferListener = new OnClickListener() {
        public void onClick(View v) {
            RawCam_GetBuffer();
        }
    };

    // ------------------------------------------------------
    // callback for START STREAM  button press
    private OnClickListener mStartStreamListener = new OnClickListener() {
        public void onClick(View v) {
            if (USE_JAVA_API) {
                if (checkNull(true)) return;
                doStartStream();
            } else {
                doStartRawStream();
            }
        }
    };

    // ------------------------------------------------------
    // callback for STOP STREAM  button press
    private OnClickListener mStopStreamListener = new OnClickListener() {
        public void onClick(View v) {
            if (USE_JAVA_API) {
                if (checkNull(true)) return;
                doStopStream();
            } else {
                int ret = RawCam_StopStream();
                if (ret == 0){
                    dspStat("stream stopped ");
                    dspData("data empty");
                    dspBuffer("buffer empty");
                } else {
                    dspStat("stop stream failed ");
                }
            }
        }
    };

    // ------------------------------------------------------
    // callback for LED FLASH SeekBar change
    private OnSeekBarChangeListener mSetLedListener = new OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            int ledType = 0;
            if (seekBar.getId() == R.id.setLed1) {
                mLed1Level = progress;
                ledType = 0;
            } else if (seekBar.getId() == R.id.setLed2) {
                ledType = 0;
                mLed2Level = progress;
            } else if (seekBar.getId() == R.id.setLed3) {
                ledType = 1;
                mLed1Level = progress;
            } else if (seekBar.getId() == R.id.setLed4) {
                ledType = 1;
                mLed2Level = progress;
            }

            if (USE_JAVA_API) {
                if (checkNull(true)) return;
                doSetLed(mLed1Level, mLed2Level);
            } else {
                RawCam_SetLed(mLed1Level, mLed2Level, ledType);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            // TODO Auto-generated method stub
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // TODO Auto-generated method stub
        }
    };

    // ------------------------------------------------------
    // callback for SET RESOLUTION button press
    private OnClickListener mSetResolutionListener = new OnClickListener() {
        public void onClick(View v) {
            try {
                mCurrentWith = Integer.parseInt(mEditFrameWidth.getText().toString());
                mCurrentHeight = Integer.parseInt(mEditFrameHeight.getText().toString());
            } catch (NumberFormatException e) {
                e.printStackTrace();
                Log.e(TAG, "Resolution width and height is empty, use default.");
            }
            if (DEBUG) Log.d(TAG, "Resolution, width=" + mCurrentWith + ", height=" + mCurrentHeight);
            if (USE_JAVA_API) {
                if (checkNull(true)) return;
                doSetResolution(mCurrentWith, mCurrentHeight);
            } else {
                int ret = RawCam_SetResolution(mCurrentWith, mCurrentHeight);
                if (ret == 0)
                    prompt_user(R.string.prompt_suc);
                else
                    prompt_user(R.string.prompt_fail);
            }
        }
    };

    // ------------------------------------------------------
    // callback for SET RESOLUTION button press
    private OnClickListener mDumpRawListener = new OnClickListener() {
        public void onClick(View v) {
            if (USE_JAVA_API)
            if (checkNull(true)) return;
            dumpToFile(one_frame_data != null ? one_frame_data : data);
        }
    };

    // ------------------------------------------------------
    // callback for READ/WRITE button press
    private OnClickListener mRegisterListener = new OnClickListener() {
        public void onClick(View v) {
            if (USE_JAVA_API)
            if (checkNull(true)) return;
            int mAddr = 0;
            int mValue = 0;

            String addr = mEditRegisterAddr.getText().toString();
            if (checkStringNull(addr, R.string.register_addr_empty)) {
                return;
            }

            String newAddr = addr.replaceAll(ADDRESS_PREFIX, "");
            if (newAddr.length() > 0) {
                mAddr = hexToDecimal(newAddr);
            }

            String value = mEditRegisterValue.getText().toString();
            String newValue = value.replaceAll(ADDRESS_PREFIX, "");
            if (newValue.length() > 0) {
                mValue = hexToDecimal(newValue);
            }

            if(DEBUG)
                Log.d(TAG, "mAddr:" + mAddr + ", mValue=" + mValue);

            if (USE_JAVA_API) {
                if (v.getId() == R.id.readRegister) {
                    doRegister(mCurrentCameraId, mAddr, 0, 1);
                } else if (v.getId() == R.id.writeRegister) {
                    if (checkStringNull(value, R.string.register_addr_value)) {
                        return;
                    }
                    doRegister(mCurrentCameraId, mAddr, mValue, 0);
                }
            } else {
                if (v.getId() == R.id.readRegister) {
                    int value1 = RawCam_ReadRegister(mAddr);
                    String tmpStr = "0x" + Integer.toHexString(value1);
                    mEditRegisterValue.setText(tmpStr);
                } else if (v.getId() == R.id.writeRegister) {
                    int ret = RawCam_WriteRegister(mAddr, mValue);
                    if (ret == 0)
                        prompt_user(R.string.prompt_suc);
                    else
                        prompt_user(R.string.prompt_fail);
                }
            }
        }
    };

    // ----------------------------------------
    // covert string value to hex
    private int hexToDecimal(String value) {
        try {
            return Integer.parseInt(value,16);
        } catch(NumberFormatException e) {
            Toast.makeText(this,"Invalid Register address or value",Toast.LENGTH_SHORT).show();
        }
        return 0;
    }

    // ----------------------------------------
    // READ/WRITE register
    private void doRegister(int camId, int addr, int value, int row) {
        try {
            if (row == 0) {
                writeRegisterMethod.invoke(mParams, camId, addr, value);
                if (DEBUG)
                    Log.d(TAG, "write addr:" + addr + ", value:" + value);
            } else if (row == 1) {
                readRegisterMethod.invoke(mParams, camId, addr, value);
            }

            mCamera.setParameters(mParams);

            mEditRegisterValue.setText("");
            mEditRegisterAddr.setText("");

            //read from register
            if (row == 1) {
                mParams = mCamera.getParameters();
                //dumpMethod.invoke(mParams);
                int mValue = mParams.getInt("set-sensor-params");
                String tmpStr = "0x" + Integer.toHexString(mValue);
                if (DEBUG)
                    Log.d(TAG, "read addr:" + addr + ", mValue:" + mValue + ", tmpStr:" + tmpStr);
                mEditRegisterValue.setText(tmpStr);
            }
        } catch (IllegalArgumentException
                | InvocationTargetException
                | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    // ----------------------------------------
    // dump raw to file
    private void dumpToFile(byte[] data) {
        if (data != null) {
            File filFSpec = null;
            try {
                String timeString = new SimpleDateFormat("HH-mm-ss")
                        .format(new Date());
                String strFile = String.format("iris_raw_img_%s.%s", timeString, "raw");
                File filRoot = Environment.getExternalStorageDirectory();
                File filPath = new File(filRoot.getAbsolutePath() + "/DCIM/Camera");
                filPath.mkdirs();
                filFSpec = new File(filPath, strFile);
                FileOutputStream fos = new FileOutputStream(filFSpec);
                fos.write(data);
                fos.close();
                Toast.makeText(this, one_frame_data != null ? R.string.dumpOneOK:R.string.dumpOK, Toast.LENGTH_SHORT).show();
                one_frame_data = null;
            } catch (Throwable thrw) {
                if (DEBUG) {
                    Log.i(TAG, "Create '" + filFSpec.getAbsolutePath() + "' failed");
                    Log.i(TAG, "Error=" + thrw.getMessage());
                }
                Toast.makeText(this, R.string.dumpFailed, Toast.LENGTH_SHORT).show();
            }
        } else {
            if (DEBUG)
                Log.i(TAG, "data empty");
            Toast.makeText(this, R.string.data_empty, Toast.LENGTH_SHORT).show();
        }
    }

    // ----------------------------------------
    // set frame rate
    private void doSetFrameRate(int frameRate) {
        //RawCam_SetFrameRate
        //fot test camera @hide api
        try {
            setFrameRateMethod.invoke(mParams, frameRate);
            mCamera.setParameters(mParams);
        } catch (IllegalArgumentException
                | InvocationTargetException
                | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    // ----------------------------------------
    // open
    private void doOpen() {
        //RawCam_Open
        //fot test camera @hide api
        try {
            mCamera = Camera.open(mCurrentCameraId);
            if (mCamera == null) {
                dspErr("open failed");
                if (DEBUG) Log.e(TAG, "open failed!!!");
                return;
            }

            dspStat("camera opened");
            if (DEBUG) Log.d(TAG, "open camera successfully!!!");
            //init camera api method
            initMethod();

            mCamera.setErrorCallback(this);
            mParams = mCamera.getParameters();

            //dumpMethod.invoke(mParams);
            //resizeForPreviewAspectRatio(mParams);
            mOpened = true;
        } catch (Exception e) {
            dspErr("open camera exception:" + e);
            if (DEBUG) Log.e(TAG, "open camera exception:" + e);
        }

    }

    // ----------------------------------------
    // close
    private void doClose() {
        //RawCam_Close
        //fot test camera @hide api
        if (DEBUG) Log.d(TAG, "close iris");
        try {
            closeMethod.invoke(mCamera);
            mCamera = null;
            mParams = null;
            mOpened = false;
            dspStat("camera closed");
            dspData("data empty");
            dspBuffer("buffer empty");
        } catch (IllegalArgumentException
                | InvocationTargetException
                | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    // ----------------------------------------
    // set format
    private void doSetFormat(int format) {
        //RawCam_SetFormat
        //fot test camera @hide api
        try {
            if (DEBUG) Log.d(TAG, "format:" + format);
            setFormatMethod.invoke(mParams, format);
            mCamera.setParameters(mParams);
        } catch (IllegalArgumentException
                | InvocationTargetException
                | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    // ----------------------------------------
    // start stream
    public void doStartRawStream() {
        if (DEBUG) Log.d(TAG, "start raw stream");
        mDataCallback = this;
        RawCam_RegisterFrameCallback();
        int ret = RawCam_StartStream();
        if (ret == 0)
            dspStat("stream started ");
        else
            dspStat("start stream failed ");
    }
    // ----------------------------------------
    // start stream
    public void doStartStream() {
        if (DEBUG) Log.d(TAG, "doStartStream");

        setDisplayOrientation();
        Camera.Size optimalSize = null;

        if (DEBUG) {
            Camera.Size preview = mCamera.getParameters().getPreviewSize();
            Log.d(TAG, "current preview size:" + preview.width + "x" + preview.height);

            List<Camera.Size> previewlist = mCamera.getParameters().getSupportedPreviewSizes();
            for(int i=0;i<previewlist.size();i++) {
                Log.d(TAG, "supported preview:" + previewlist.get(i).width + "x" + previewlist.get(i).height);
            }

            Camera.Size picture = mCamera.getParameters().getPictureSize();
            Log.d(TAG, "current picture size:" + picture.width + "x" + picture.height);

            List<Camera.Size> picturelist = mCamera.getParameters().getSupportedPictureSizes();
            for(int i=0;i<picturelist.size();i++) {
                Log.d(TAG, "supported picture:" + picturelist.get(i).width + "x" + picturelist.get(i).height);
            }

            Log.d(TAG, "mPreviewWidth:" + mPreviewWidth + ", mPreviewHeight:" + mPreviewHeight);
        }

        List<Camera.Size> list = mCamera.getParameters().getSupportedPreviewSizes();
        if (list != null) {
            optimalSize = getOptimalPreviewSize(list, mPreviewWidth, mPreviewHeight);
        }

        try {
            mCamera.setPreviewTexture(mSurfaceTexture);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            tvStat.setText("ERROR" + e);
        }

        //mParams.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);

        doSetLed(mLed1Level, mLed2Level);

        //set preview size
        //mParams.setPreviewSize(optimalSize.width, optimalSize.height);
        if (DEBUG)
            Log.d(TAG, "optimalSize.width:" + optimalSize.width + ", optimalSize.height:" + optimalSize.height);

        //set 50hz
        mParams.setAntibanding("50hz");

        //apply changes
        mCamera.setParameters(mParams);
        //resizeForPreviewAspectRatio(mParams);

        //set callback
        mCamera.setPreviewCallback(this);

        //RawCam_StartStream
        //fot test camera @hide api
        try {
            if (DEBUG)
                Log.d(TAG, "start stream");
            startStreamMethod.invoke(mCamera);
            dspStat("stream started ");
        } catch (IllegalArgumentException
                | InvocationTargetException
                | IllegalAccessException e) {
            e.printStackTrace();
            dspStat("start stream failed ");
        }

//        if (mPreviewWidth != 0 && mPreviewHeight != 0) {
//            // Re-apply transform matrix for new surface texture
//            setTransformMatrix(mPreviewWidth, mPreviewHeight);
//        }
    }

    // ----------------------------------------
    // stop stream
    private void doStopStream() {
        //RawCam_StopStream
        //fot test camera @hide api
        try {
            if (DEBUG)
                Log.d(TAG, "stop stream");
            stopStreamMethod.invoke(mCamera);
            dspStat("stream stopped");
            dspData("data empty");
        } catch (IllegalArgumentException
                | InvocationTargetException
                | IllegalAccessException e) {
            e.printStackTrace();
            dspStat("stop stream failed ");
        }
    }

    // ----------------------------------------
    // set led
    private void doSetLed(int level1, int level2) {
        //RawCam_SetLed
        //fot test camera @hide api
        try {
            if (DEBUG) Log.d(TAG, "led1:" + level1 + ", led2:" + level2);
            setLedMethod.invoke(mParams, level1, level2);
            mCamera.setParameters(mParams);
        } catch (IllegalArgumentException
                | InvocationTargetException
                | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    // ----------------------------------------
    // set resolution
    private void doSetResolution(int width, int height) {
        //RawCam_SetResolution
        //fot test camera @hide api
        try {
            if (DEBUG) Log.d(TAG, "width:" + width + ", height:" + height);
            setResolutionMethod.invoke(mParams, width, height);
            mCamera.setParameters(mParams);
        } catch (IllegalArgumentException
                | InvocationTargetException
                | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        this.data = data;

        if (DEBUG) {
            Camera.Size preview = mCamera.getParameters().getPreviewSize();
            Log.d(TAG, "current preview size:" + preview.width + "x" + preview.height);
        }

        if (data != null) {
            dspData("data length:" + data.length);
        } else {
            dspData("data empty");
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        synchronized (mSurfaceTextureLock) {
            mSurfaceTexture = surface;
            //if (checkNull(true)) return;
            //doStartStream();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // TODO Auto-generated method stub
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mSurfaceTexture = null;
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // TODO Auto-generated method stub
    }

    // ----------------------------------------
    // display error msg
    private void dspErr(String s) {
        tvStat.setText("ERROR: " + s);
    }

    // ----------------------------------------
    // display status string
    private void dspStat(String s) {
        tvStat.setText(s);
    }

    // ----------------------------------------
    // display data string
    private void dspData(String s) {
        tvData.setText(s);
    }

    // ----------------------------------------
    // display buffer string
    private void dspBuffer(String s) {
        tvBuffer.setText(s);
    }

    @Override
    public void onError(int error, Camera camera) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onDataReceived(Object obj, int mode, byte[] raw, byte[] alpha8) {
        this.data = raw;
        this.alpha8Data = alpha8;

        if (data != null) {
            if (DEBUG)
                Log.d(TAG, "data length:" + data.length);
            if (mode == 0) {//continue mode
                dspData("data length:" + data.length);
                Bitmap bm = Bitmap.createBitmap(mDefaultWith, mDefaultHeight, Bitmap.Config.ALPHA_8);
                if (alpha8 != null && bm != null) {
                    mTextureView.setVisibility(View.INVISIBLE);
                    mImageContent.setVisibility(View.VISIBLE);
                    ByteBuffer bb = ByteBuffer.wrap(alpha8);
                    bm.copyPixelsFromBuffer(bb);
                    mImageContent.setImageBitmap(bm);
                } else {
                    mImageContent.setVisibility(View.INVISIBLE);
                    mTextureView.setVisibility(View.VISIBLE);
                    Log.d(TAG, "bm is null");
                }
            } else {//oneshot mode
                this.one_frame_data = data;
                dspBuffer("buffer length:" + data.length);
            }
        } else {
            dspData("data empty");
            dspBuffer("buffer empty");
        }
    }

    private class EventHandler extends Handler
    {
        private final IrisguiActicity mIr;

        public EventHandler(IrisguiActicity c, Looper looper) {
            super(looper);
            mIr = c;
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case IRIS_MSG_CONTINUE_RAW_FRAME:
                    DataCallback pContineCb = mDataCallback;
                    if (pContineCb != null) {
                        pContineCb.onDataReceived(mIr, 0, (byte[]) ((Map)msg.obj).get("raw"),
                                (byte[]) ((Map)msg.obj).get("raw"));
                    }
                    return;
                case IRIS_MSG_ONE_RAW_FRAME:
                    DataCallback pOneCb = mDataCallback;
                    if (pOneCb != null) {
                        pOneCb.onDataReceived(mIr, 1, (byte[]) ((Map)msg.obj).get("raw"),
                                (byte[]) ((Map)msg.obj).get("raw"));
                    }
                    return;
                default:
                    if (DEBUG)
                        Log.e(TAG, "Unknown message type " + msg.what);
                    return;
            }
        }
    }

    private static void postRawDataEvent(Object ref,
                        int what, int arg1, int arg2, Object rawObj, Object alpha8Obj)
    {
        IrisguiActicity c = (IrisguiActicity)((WeakReference)ref).get();
        if (c == null)
            return;

        if (c.mEventHandler != null) {
            Map<String,Object> data = new ArrayMap<>();
            data.put("raw", rawObj);
            data.put("alpha8", alpha8Obj);
            Message m = c.mEventHandler.obtainMessage(what, arg1, arg2, data);
            c.mEventHandler.sendMessage(m);
        }
    }
}
