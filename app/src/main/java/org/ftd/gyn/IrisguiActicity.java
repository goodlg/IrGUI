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
import java.sql.BatchUpdateException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Created by sdduser on 17-12-15.
 */

public class IrisguiActicity extends Activity
        implements TextureView.SurfaceTextureListener, Camera.ErrorCallback,
        Camera.PreviewCallback, DataCallback {

    private static final String TAG = "IrisGui";

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

    private Button mSetFrameRate;
    private Button mSetFormat;
    private Spinner mFormats;
    private Spinner mInterfaceMode;
    private Button mSetResolution;
    private Button mDumpRaw;
    private ImageView mImageContent;
    private Button mGetBuffer;

    private SurfaceTexture mSurfaceTexture;
    private TextureView mTextureView;

    private static final boolean DEBUG = false;

    private Camera mCamera;
    private Camera.CameraInfo mCameraInfo;
    private Camera.Parameters mParams;

    protected int mDefaultCameraId            = 2;//0: BACK, 1: FRONT, 2: IRIS
    private int mCurrentCameraId              = mDefaultCameraId;

    private final Object mSurfaceTextureLock  = new Object();

    private int mPreviewWidth                 = 0;
    private int mPreviewHeight                = 0;
    private int mLed1Level                    = 128;
    private int mLed2Level                    = 128;
    private int mCurrentFormatValue           = 0;//0x11: yuv420sp, 0x99: raw

    private Matrix mMatrix                    = null;
    private float mAspectRatio                = 4f / 3f;
    private boolean mOrientationResize;
    private boolean mPrevOrientationResize;
    private boolean mAspectRatioResize;

    private static final String ADDRESS_PREFIX = "0x";

    private byte[] data;

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
    public native int RawCam_SetLed(int led1, int led2);
    public native void RawCam_GetBuffer();
    public native int RawCam_RegisterFrameCallback();

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

        ApiDeinit();

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
            Log.i(TAG, "camera has been destroyed");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!checkNull(false)) {
            doStopStream();
            doClose();
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
        } else {
            mTvFrameRate.setVisibility(View.GONE);
            mTvFormat.setVisibility(View.GONE);
            mTvResolution.setVisibility(View.GONE);
            mTvx.setVisibility(View.GONE);
            mEditOpen.setVisibility(View.GONE);
            mEditFrameRate.setVisibility(View.GONE);
            mEditFrameWidth.setVisibility(View.GONE);
            mEditFrameHeight.setVisibility(View.GONE);
            mSetFrameRate.setVisibility(View.GONE);
            mSetFormat.setVisibility(View.GONE);
            mFormats.setVisibility(View.GONE);
            mSetResolution.setVisibility(View.GONE);
            mGetBuffer.setVisibility(View.VISIBLE);
            tvBuffer.setVisibility(View.VISIBLE);
            //mDumpRaw.setVisibility(View.GONE);
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
            if (checkNull(true)) return;
            int frameRate = Integer.parseInt(mEditFrameRate.getText().toString());
            doSetFrameRate(frameRate);
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
        }

        @Override
        public void onNothingSelected(AdapterView<?> adapterView) {
            // Another interface callback
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
                if (ret == 0)
                    dspStat("camera opened");
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
                if (ret == 0)
                    dspStat("camera closed");
                else
                    dspStat("close camera failed");
            }
        }
    };

    // ------------------------------------------------------
    // callback for SET FORMAT button press
    private OnClickListener mSetFormatListener = new OnClickListener() {
        public void onClick(View v) {
            if (checkNull(true)) return;
            doSetFormat(mCurrentFormatValue);
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
            if (seekBar.getId() == R.id.setLed1) {
                mLed1Level = progress;
            } else if (seekBar.getId() == R.id.setLed2) {
                mLed2Level = progress;
            }
            if (USE_JAVA_API) {
                if (checkNull(true)) return;
                doSetLed(mLed1Level, mLed2Level);
            } else {
                RawCam_SetLed(mLed1Level, mLed2Level);
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
            if (checkNull(true)) return;
            int width = Integer.parseInt(mEditFrameWidth.getText().toString());
            int height = Integer.parseInt(mEditFrameHeight.getText().toString());
            doSetResolution(width, height);
        }
    };

    // ------------------------------------------------------
    // callback for SET RESOLUTION button press
    private OnClickListener mDumpRawListener = new OnClickListener() {
        public void onClick(View v) {
            if (checkNull(true)) return;
            dumpToFile(data);
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
                    RawCam_WriteRegister(mAddr, mValue);
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
                String timeString = new SimpleDateFormat("HH:mm:ss")
                        .format(new Date());
                String strFile = String.format("iris_raw_img_%s.%s", timeString, "raw");
                File filRoot = Environment.getExternalStorageDirectory();
                File filPath = new File(filRoot.getAbsolutePath() + "/DCIM/Camera");
                filPath.mkdirs();
                filFSpec = new File(filPath, strFile);
                FileOutputStream fos = new FileOutputStream(filFSpec);
                fos.write(data);
                fos.close();
                Toast.makeText(this, R.string.dumpOK, Toast.LENGTH_SHORT).show();
            } catch (Throwable thrw) {
                Log.i(TAG, "Create '" + filFSpec.getAbsolutePath() + "' failed");
                Log.i(TAG, "Error=" + thrw.getMessage());
                Toast.makeText(this, R.string.dumpFailed, Toast.LENGTH_SHORT).show();
            }
        } else {
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
            Log.d(TAG, "frameRate:" + frameRate);
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
                Log.e(TAG, "open failed!!!");
                return;
            }

            dspStat("camera opened");
            Log.d(TAG, "open camera successfully!!!");
            //init camera api method
            initMethod();

            mCamera.setErrorCallback(this);
            mParams = mCamera.getParameters();

            //dumpMethod.invoke(mParams);
            //resizeForPreviewAspectRatio(mParams);
        } catch (Exception e) {
            dspErr("open camera exception:" + e);
            Log.e(TAG, "open camera exception:" + e);
        }

    }

    // ----------------------------------------
    // close
    private void doClose() {
        //RawCam_Close
        //fot test camera @hide api
        Log.d(TAG, "close iris");
        try {
            closeMethod.invoke(mCamera);
            mCamera = null;
            mParams = null;
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
            Log.d(TAG, "format:" + format);
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
        Log.d(TAG, "start raw stream");
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
        Log.d(TAG, "doStartStream");

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
            Log.d(TAG, "led1:" + level1 + ", led2:" + level2);
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
            Log.d(TAG, "width:" + width + ", height:" + height);
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
//            Bitmap bmSnap = BitmapFactory.decodeByteArray(data, 0, data.length);
//            if (bmSnap != null) {
//                mTextureView.setVisibility(View.INVISIBLE);
//                mImageContent.setImageBitmap(bmSnap);
//            } else {
//                mTextureView.setVisibility(View.VISIBLE);
//                Log.d(TAG, "bmSnap is null");
//            }
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
    public void onDataReceived(Object obj, int mode, byte[] data) {
        this.data = data;

        if (data != null) {
            if (DEBUG)
                Log.d(TAG, "data length:" + data.length);
            if (mode == 0) {//continue mode
                dspData("data length:" + data.length);
            } else {//oneshot mode
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
                        pContineCb.onDataReceived(mIr, 0, (byte[])msg.obj);
                    }
                    return;
                case IRIS_MSG_ONE_RAW_FRAME:
                    DataCallback pOneCb = mDataCallback;
                    if (pOneCb != null) {
                        pOneCb.onDataReceived(mIr, 1, (byte[])msg.obj);
                    }
                    return;
                default:
                    Log.e(TAG, "Unknown message type " + msg.what);
                    return;
            }
        }
    }

    private static void postRawDataEvent(Object ref,
                        int what, int arg1, int arg2, Object obj)
    {
        IrisguiActicity c = (IrisguiActicity)((WeakReference)ref).get();
        if (c == null)
            return;

        if (c.mEventHandler != null) {
            Message m = c.mEventHandler.obtainMessage(what, arg1, arg2, obj);
            c.mEventHandler.sendMessage(m);
        }
    }
}
