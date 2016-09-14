package jp.co.cyberagent.android.gpuimage.sample.utils

import android.app.AlertDialog
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import android.view.View
import com.malmstein.fenster.R
import com.malmstein.fenster.view.VideoSizeCalculator


/**
 * Displays a video file.  The VideoView class
 * can load images from various sources (such as resources or content
 * providers), takes care of computing its measurement from the video so that
 * it can be used in any layout manager, and provides various display options
 * such as scaling and tinting.
 * *Note: VideoView does not retain its full state when going into the
 * background.*  In particular, it does not restore the current play state,
 * play position, selected tracks added via
 * [android.app.Activity.onSaveInstanceState] and
 * [android.app.Activity.onRestoreInstanceState].
 * Also note that the audio session id (from [.getAudioSessionId]) may
 * change from its previously returned value when the VideoView is restored.
 */

class RecorderTextureView : TextureView {

    var extraSurfaceTextureListener: SurfaceTextureListener? = null

    interface Renderer {
        fun onPause()

        val isStarted: Boolean

        fun setVideoSize(width: Int, height: Int)

        fun setOutputSize(width: Int, height: Int)

        fun startRenderingToOutput(outputSurfaceTexture: SurfaceTexture, callback: Runnable)

        val inputTexture: SurfaceTexture
    }

    enum class ScaleType {
        SCALE_TO_FIT, CROP
    }

    // collaborators / delegates / composites .. discuss
    private var videoSizeCalculator: VideoSizeCalculator? = null
    // mCurrentState is a VideoView object's current state.
    // mTargetState is the state that a method caller intends to reach.
    // For instance, regardless the VideoView object's current state,
    // calling pause() intends to bring the object to a target state
    // of STATE_PAUSED.
    private var mCurrentState = STATE_IDLE
    private var mTargetState = STATE_IDLE

    private var mScaleType: ScaleType = ScaleType.SCALE_TO_FIT


    private var mAssetFileDescriptor: AssetFileDescriptor? = null
    private var mSurfaceTexture: SurfaceTexture? = null

    var renderer: Renderer? = null
    private var mSurfaceWidth: Int = 0
    private var mSurfaceHeight: Int = 0


    fun init(attrs: AttributeSet?) {
        attrs?.let { attrs ->
            applyCustomAttributes(context, attrs)
        }
        videoSizeCalculator = VideoSizeCalculator()
        initVideoView()
    }

    constructor(context: Context?) : super(context) {
        init(null)
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(attrs)
    }


    private fun applyCustomAttributes(context: Context, attrs: AttributeSet) {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.FensterVideoView) ?: return
        val attrsValues = intArrayOf(R.attr.scaleType)
        val scaleTypedArray = context.obtainStyledAttributes(attrs, attrsValues)
        if (scaleTypedArray != null) {
            try {
                val scaleTypeId = typedArray.getInt(0, 0)
                mScaleType = ScaleType.values()[scaleTypeId]
            } finally {
                typedArray.recycle()
            }
        } else {
            mScaleType = ScaleType.SCALE_TO_FIT
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
    }

    fun resolveAdjustedSize(desiredSize: Int, measureSpec: Int): Int {
        return View.getDefaultSize(desiredSize, measureSpec)
    }

    private fun initVideoView() {
        videoSizeCalculator?.setVideoSize(0, 0)

        surfaceTextureListener = mSTListener

        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        mCurrentState = STATE_IDLE
        mTargetState = STATE_IDLE
    }

    private fun disableFileDescriptor() {
        mAssetFileDescriptor = null
    }


    fun fitScaling() {
        when (mScaleType) {
            ScaleType.SCALE_TO_FIT -> setTransform(Matrix())
            ScaleType.CROP -> {
                val videoWidth = videoSizeCalculator?.videoWidth?.toFloat() ?: 0f
                val videoHeight = videoSizeCalculator?.videoHeight?.toFloat() ?: 0f
                val surfaceWidth = measuredWidth.toFloat()
                val surfaceHeight = measuredHeight.toFloat()

                val videoAR = videoWidth / videoHeight
                val surfaceAR = surfaceWidth / surfaceHeight
                if (videoAR > surfaceAR) {
                    val matrix = Matrix()
                    val ratio = surfaceHeight / videoHeight
                    val scale = videoWidth * ratio / surfaceWidth
                    matrix.setScale(scale, 1f, 0.5f, 0.5f)
                    setTransform(matrix)
                } else if (videoAR < surfaceAR) {
                    val matrix = Matrix()
                    val ratio = surfaceWidth / videoWidth
                    val scale = videoHeight * ratio / surfaceHeight
                    matrix.setScale(1f, scale, 0.5f, 0.5f)
                    setTransform(matrix)
                } else {
                    setTransform(Matrix())
                }
            }
        }
    }


    private val mSTListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            mSurfaceTexture = surface
            mSurfaceWidth = width
            mSurfaceHeight = height
            extraSurfaceTextureListener?.onSurfaceTextureAvailable(surface, width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            renderer?.setOutputSize(width, height)
            mSurfaceWidth = width
            mSurfaceHeight = height
            extraSurfaceTextureListener?.onSurfaceTextureSizeChanged(surface, width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            mSurfaceTexture = null

            release(true)
            extraSurfaceTextureListener?.onSurfaceTextureDestroyed(surface)
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            if (mSurfaceTexture !== surface) {
                mSurfaceTexture = surface
            }
            extraSurfaceTextureListener?.onSurfaceTextureUpdated(surface)
        }
    }

    /*
     * release the media player in any state
     */
    private fun release(clearTargetState: Boolean) {
        if (renderer != null) {
            if (renderer!!.isStarted) {
                renderer!!.onPause()
                renderer = null
            }
        }
    }

    companion object {

        val TAG = "TextureVideoView"
        val VIDEO_BEGINNING = 0

        // all possible internal states
        private val STATE_ERROR = -1
        private val STATE_IDLE = 0
        private val STATE_PREPARING = 1
        private val STATE_PREPARED = 2
        private val STATE_PLAYING = 3
        private val STATE_PAUSED = 4
        private val STATE_PLAYBACK_COMPLETED = 5
        private val MILLIS_IN_SEC = 1000

        private fun createErrorDialog(context: Context, completionListener: MediaPlayer.OnCompletionListener?, mediaPlayer: MediaPlayer, errorMessage: Int): AlertDialog {
            return AlertDialog.Builder(context).setMessage(errorMessage).setPositiveButton(
                    android.R.string.ok
            ) { dialog, whichButton ->
                /* If we get here, there is no onError listener, so
                                     * at least inform them that the video is over.
                                     */
                completionListener?.onCompletion(mediaPlayer)
            }.setCancelable(false).create()
        }

        private fun getErrorMessage(frameworkError: Int): Int {
            var messageId = R.string.fen__play_error_message

            if (frameworkError == MediaPlayer.MEDIA_ERROR_IO) {
                Log.e(TAG, "TextureVideoView error. File or network related operation errors.")
            } else if (frameworkError == MediaPlayer.MEDIA_ERROR_MALFORMED) {
                Log.e(TAG, "TextureVideoView error. Bitstream is not conforming to the related coding standard or file spec.")
            } else if (frameworkError == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
                Log.e(TAG, "TextureVideoView error. Media server died. In this case, the application must release the MediaPlayer object and instantiate a new one.")
            } else if (frameworkError == MediaPlayer.MEDIA_ERROR_TIMED_OUT) {
                Log.e(TAG, "TextureVideoView error. Some operation takes too long to complete, usually more than 3-5 seconds.")
            } else if (frameworkError == MediaPlayer.MEDIA_ERROR_UNKNOWN) {
                Log.e(TAG, "TextureVideoView error. Unspecified media player error.")
            } else if (frameworkError == MediaPlayer.MEDIA_ERROR_UNSUPPORTED) {
                Log.e(TAG, "TextureVideoView error. Bitstream is conforming to the related coding standard or file spec, but the media framework does not support the feature.")
            } else if (frameworkError == MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK) {
                Log.e(TAG, "TextureVideoView error. The video is streamed and its container is not valid for progressive playback i.e the video's index (e.g moov atom) is not at the start of the file.")
                messageId = R.string.fen__play_progressive_error_message
            }
            return messageId
        }
    }
}
