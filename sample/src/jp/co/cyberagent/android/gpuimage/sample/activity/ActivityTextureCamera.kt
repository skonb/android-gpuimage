package jp.co.cyberagent.android.gpuimage.sample.activity

import android.app.Activity
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import jp.co.cyberagent.android.gpuimage.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.GPUImageTextureRenderer
import jp.co.cyberagent.android.gpuimage.Rotation
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools
import jp.co.cyberagent.android.gpuimage.sample.GPUImageRecordableTextureRenderer
import jp.co.cyberagent.android.gpuimage.sample.R
import jp.co.cyberagent.android.gpuimage.sample.utils.CameraHelper
import kotlinx.android.synthetic.main.activity_texture_camera.*

/**
 * Created by skonb on 2016/07/10.
 */

class ActivityTextureCamera : Activity() {


    private var mCameraHelper: CameraHelper? = null
    private var mCamera: CameraLoader? = null
    private var renderer: GPUImageRecordableTextureRenderer? = null
    private val mFilterAdjuster: GPUImageFilterTools.FilterAdjuster? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_texture_camera)
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
            }

            override fun onStopTrackingTouch(p0: SeekBar?) {
            }
        })
        button_choose_filter?.setOnClickListener {

        }
        button_capture?.setOnClickListener {

        }

        renderer = GPUImageRecordableTextureRenderer(GPUImageFilter())
        mCameraHelper = CameraHelper(this)
        mCamera = CameraLoader()
        preview_texture?.extraSurfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
                mCamera?.onPause()
                return true
            }

            override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
                renderer?.setOutputSize(width, height)
                renderer?.startRenderingToOutput(surface, {
                    mCamera?.onResume()
                })
            }

            override fun onSurfaceTextureSizeChanged(surface0: SurfaceTexture?, width: Int, height: Int) {
                renderer?.setOutputSize(width, height)
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {

            }
        }

        img_switch_camera.setOnClickListener {

        }
        if (!mCameraHelper!!.hasFrontCamera() || !mCameraHelper!!.hasBackCamera()) {
            img_switch_camera.visibility = View.GONE
        }

        preview_texture?.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    renderer?.recording = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    renderer?.recording = false
                }
                else -> {
                    null
                }
            }
            true
        }
    }

    override fun onPause() {
        super.onPause()
        mCamera?.onPause()
        renderer?.onPause()
    }


    private inner class CameraLoader {

        private var mCurrentCameraId = 0
        private var mCameraInstance: Camera? = null

        fun onResume() {
            setUpCamera(mCurrentCameraId)
        }

        fun onPause() {
            releaseCamera()
        }

        fun switchCamera() {
            releaseCamera()
            mCurrentCameraId = (mCurrentCameraId + 1) % mCameraHelper!!.numberOfCameras
            setUpCamera(mCurrentCameraId)
        }

        private fun setUpCamera(id: Int) {
            mCameraInstance = getCameraInstance(id)
            val parameters = mCameraInstance?.parameters
            // TODO adjust by getting supportedPreviewSizes and then choosing
            // the best one for screen size (best fill screen)
            val sizes = parameters?.supportedPreviewSizes
            getOptimalPreviewSize(sizes, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)?.let { optimalSize ->
                val previewWidth = optimalSize.width
                val previewHeight = optimalSize.height
                parameters!!.setPreviewSize(previewWidth, previewHeight)
                renderer?.setVideoSize(previewWidth, previewHeight)
            }

            if (parameters?.supportedFocusModes?.contains(
                    Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true) {
                parameters?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }
            mCameraInstance?.parameters = parameters

            val orientation = mCameraHelper?.getCameraDisplayOrientation(
                    this@ActivityTextureCamera, mCurrentCameraId)
            val cameraInfo = CameraHelper.CameraInfo2()
            mCameraHelper?.getCameraInfo(mCurrentCameraId, cameraInfo)
            val flipHorizontal = cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
            cameraInfo.orientation
            mCameraInstance?.setPreviewTexture(renderer?.inputTexture)
            mCameraInstance?.let {
                setCameraDisplayOrientation(this@ActivityTextureCamera, mCurrentCameraId, it)
            }
            mCameraInstance?.startPreview()

        }

        /** A safe way to get an instance of the Camera object.  */
        private fun getCameraInstance(id: Int): Camera {
            var c: Camera? = null
            try {
                c = mCameraHelper!!.openCamera(id)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return c!!
        }

        private fun releaseCamera() {
            mCameraInstance?.setPreviewTexture(null)
            mCameraInstance?.stopPreview()
            mCameraInstance?.release()
            mCameraInstance = null
        }
    }

    private fun getOptimalPreviewSize(sizes: List<Camera.Size>?, w: Int, h: Int): Camera.Size? {
        val ASPECT_TOLERANCE = 0.05
        val targetRatio = w.toDouble() / h

        if (sizes == null) return null

        var optimalSize: Camera.Size? = null

        var minDiff = java.lang.Double.MAX_VALUE

        val targetHeight = h / 2

        // Find size
        for (size in sizes) {
            val ratio = size.width.toDouble() / size.height.toDouble()
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size
                minDiff = Math.abs(size.height - targetHeight).toDouble()
            }
        }

        if (optimalSize == null) {
            minDiff = java.lang.Double.MAX_VALUE
            for (size in sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size
                    minDiff = Math.abs(size.height - targetHeight).toDouble()
                }
            }
        }
        return optimalSize
    }

    fun setCameraDisplayOrientation(activity: Activity, icameraId: Int, camera: Camera) {
        val cameraInfo = Camera.CameraInfo()

        Camera.getCameraInfo(icameraId, cameraInfo)

        val rotation = activity.windowManager.defaultDisplay.rotation

        var degrees = 0 // k

        when (rotation) {
            Surface.ROTATION_0 -> degrees = 0
            Surface.ROTATION_90 -> degrees = 90
            Surface.ROTATION_180 -> degrees = 180
            Surface.ROTATION_270 -> degrees = 270
        }

        var result: Int

        if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            // cameraType=CAMERATYPE.FRONT;

            result = (cameraInfo.orientation + degrees) % 360
            result = (360 - result) % 360 // compensate the mirror

        } else { // back-facing

            result = (cameraInfo.orientation - degrees + 360) % 360

        }
        // displayRotate=result;
        camera.setDisplayOrientation(result)
        renderer?.rotation = when (result) {
            0 -> Rotation.NORMAL
            90 -> Rotation.ROTATION_90
            180 -> Rotation.ROTATION_180
            270 -> Rotation.ROTATION_270
            else -> Rotation.NORMAL
        }

    }
}
