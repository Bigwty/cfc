package org.cimbar.camerafilecopy;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ProgressBar;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

public class MainActivity extends CameraActivity implements CvCameraViewListener2 {
    private static final String TAG = "cfc::MainActivity";
    private static final int CREATE_FILE = 11;

    private Toast introToast;

    private CameraBridgeViewBase mOpenCvCameraView;
    private final Handler startupHandler = new Handler(Looper.getMainLooper());
    private long startupDeadline;
    private boolean startupComplete;
    private final Runnable startupTick = new Runnable() {
        @Override
        public void run() {
            long remaining = Math.max(0, startupDeadline - SystemClock.elapsedRealtime());
            ProgressBar progress = findViewById(R.id.startup_progress);
            progress.setProgress((int) (4000 - remaining));
            if (remaining > 0) {
                startupHandler.postDelayed(this, Math.min(40, remaining));
                return;
            }
            startupComplete = true;
            findViewById(R.id.startup).setVisibility(View.GONE);
            mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
            mOpenCvCameraView.enableView();
            if (introToast != null) introToast.show();
        }
    };
    private String dataPath;
    private String activePath;

    public MainActivity() {
        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);

        //! [ocv_loader_init]
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");
            System.loadLibrary("cfc-cpp");
        } else {
            Log.e(TAG, "OpenCV initialization failed!");
            (Toast.makeText(this, getString(R.string.camera_init_failed), Toast.LENGTH_LONG)).show();
            return;
        }
        //! [ocv_loader_init]

        //! [keep_screen]
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        //! [keep_screen]

        this.dataPath = this.getFilesDir().getPath();
        //this.dataPath = this.getExternalFilesDir(null).getPath(); // for manual testing

        setContentView(R.layout.activity_main);

        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.main_surface);
        mOpenCvCameraView.setVisibility(SurfaceView.INVISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        startupDeadline = savedInstanceState == null
                ? SystemClock.elapsedRealtime() + 4000
                : savedInstanceState.getLong("startupDeadline", SystemClock.elapsedRealtime() + 4000);

        introToast = Toast.makeText(this, R.string.scan_hint, Toast.LENGTH_LONG);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putLong("startupDeadline", startupDeadline);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onPause()
    {
        startupHandler.removeCallbacks(startupTick);
        if (introToast != null) introToast.cancel();
        shutdownJNI();
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (mOpenCvCameraView != null) {
            if (startupComplete) mOpenCvCameraView.enableView();
            else startupHandler.post(startupTick);
        }
    }

    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return mOpenCvCameraView == null ? Collections.emptyList() : Collections.singletonList(mOpenCvCameraView);
    }

    @Override
    public void onDestroy() {
        startupHandler.removeCallbacks(startupTick);
        shutdownJNI();
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
    }

    @Override
    public void onCameraViewStopped() {
    }

    @Override
    public Mat onCameraFrame(CvCameraViewFrame frame) {
        // get current camera frame as OpenCV Mat object
        Mat mat = frame.rgba();

        // native call to process current camera frame
        String res = processImageJNI(mat.getNativeObjAddr(), this.dataPath);

        // res will contain a file path if we completed a transfer. Ask the user where to save it
        if (!res.isEmpty()) {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(getMimeType(res));
            intent.putExtra(Intent.EXTRA_TITLE, res);
            // can't get putExtra to work for extra values, so we'll save it in the class
            this.activePath = this.dataPath + "/" + res;
            startActivityForResult(intent, CREATE_FILE);
        }

        // return processed frame for live preview
        return mat;
    }

    // mimetype helper
    protected static String getMimeType(String filename) {
        String mt = null;
        int extIdx = filename.lastIndexOf('.');
        if (extIdx >= 0) {
            String ext = filename.substring(extIdx + 1).toLowerCase();
            if (!ext.isEmpty()) {
                mt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            }
        }
        if (mt == null) {
            mt = "*/*";
        }
        return mt;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode == RESULT_OK && requestCode == CREATE_FILE) {
            if (this.activePath == null)
                return;

            // copy this.activePath (tempfile) to the user-specified location
            try (
                    InputStream istream = new FileInputStream(this.activePath);
                    OutputStream ostream = getContentResolver().openOutputStream(data.getData())
            ) {
                byte[] buf = new byte[8192];
                int length;
                while ((length = istream.read(buf)) > 0) {
                    ostream.write(buf, 0, length);
                }
                ostream.flush();
                Toast.makeText(this, R.string.file_saved, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Log.e(TAG, "failed to write file " + e.toString());
                Toast.makeText(this, R.string.file_save_failed, Toast.LENGTH_LONG).show();
            } finally {
                try {
                    new File(this.activePath).delete();
                } catch (Exception e) {}
                this.activePath = null;
            }
        }
    }

    private native String processImageJNI(long mat, String path);
    private native void shutdownJNI();

}
