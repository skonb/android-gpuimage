package jp.co.cyberagent.android.gpuimage.sample

import android.annotation.TargetApi
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.util.Log
import jp.co.cyberagent.android.gpuimage.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.GPUImageRenderer
import jp.co.cyberagent.android.gpuimage.Rotation
import jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.*

/**
 * Created by skonb on 2016/07/10.
 */

@TargetApi(11)
class GPUImageOffScreenRecordableTextureRenderer: GPUImageRenderer, SurfaceTexture.OnFrameAvailableListener {


    protected var outputTexture: SurfaceTexture? = null
    protected var egl: EGL10? = null
    protected var eglDisplay: EGLDisplay? = null
    protected var eglContext: EGLContext? = null
    protected var eglSurface: EGLSurface? = null

    var isStarted: Boolean = false
        protected set
    internal var mNoFilter = GPUImageFilter()
    protected var screenCubeBuffer: FloatBuffer? = null
    protected var screenTextureBuffer: FloatBuffer? = null

    constructor(filter: GPUImageFilter?) : super(filter) {
        screenCubeBuffer = ByteBuffer.allocateDirect(CUBE.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        screenCubeBuffer?.put(CUBE)?.position(0)
        screenTextureBuffer = ByteBuffer.allocateDirect(SCREEN_TEXTURE.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        screenTextureBuffer?.put(SCREEN_TEXTURE)?.position(0)
    }


    fun startRenderingToOutput(outputTexture: SurfaceTexture, onInputTextureAvailableCallback: Runnable) {
        this.outputTexture = outputTexture
        this.onInputTextureAvailableCallback = onInputTextureAvailableCallback
        if (mImageHeight != 0 && mImageWidth != 0) {
            outputTexture.setDefaultBufferSize(mImageWidth, mImageHeight)
        }
        mNoFilter.runOnDraw {
            mNoFilter.init()
            mNoFilter.isExternalOES = true
        }
        Thread(Runnable { this@GPUImageOffScreenRecordableTextureRenderer.run() }).start()
    }

    fun run() {
        isStarted = true
        try {
            //Waiting for subclass constructor
            Thread.sleep(100)
        } catch (e: InterruptedException) {

        }

        initGL()
        initGLComponents()
        if (mFilter != null) {
            if (!mFilter.isInitialized) {
                mFilter.runOnDraw { mFilter.init() }
            }
            mFilter.glTexture = GLES20.GL_TEXTURE1
        }
        Log.d(LOG_TAG, "OpenGL init OK.")
        while (isStarted) {
            runAll(mRunOnDraw)
            val loopStart = System.currentTimeMillis()
            pingFps()

            if (draw()) {
                egl?.eglSwapBuffers(eglDisplay, eglSurface)
            }
            runAll(mRunOnDrawEnd)
            val waitDelta = 16 - (System.currentTimeMillis() - loopStart)    // Targeting 60 fps, no need for faster
            if (waitDelta > 0) {
                try {
                    Thread.sleep(waitDelta)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    continue
                }

            }
        }

        deinitGLComponents()
        deinitGL()
        if (shouldReInit) {
            shouldReInit = false
            run()
        }
    }

    private var lastFpsOutput: Long = 0
    private var frames: Int = 0

    protected fun pingFps() {
        if (lastFpsOutput == 0L)
            lastFpsOutput = System.currentTimeMillis()

        frames++

        if (System.currentTimeMillis() - lastFpsOutput > 1000) {
            lastFpsOutput = System.currentTimeMillis()
            frames = 0
        }
    }


    /**
     * Call when activity pauses. This stops the rendering thread and deinitializes OpenGL.
     */
    fun onPause() {
        isStarted = false
    }


    protected fun initGL() {
        egl = EGLContext.getEGL() as EGL10
        eglDisplay = egl?.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)

        val version = IntArray(2)
        egl?.eglInitialize(eglDisplay, version)

        val eglConfig = chooseEglConfig()
        eglContext = createContext(egl!!, eglDisplay!!, eglConfig!!)

        eglSurface = egl?.eglCreateWindowSurface(eglDisplay, eglConfig, outputTexture, null)

        if (eglSurface == null || eglSurface === EGL10.EGL_NO_SURFACE) {
            throw RuntimeException("GL Error: " + GLUtils.getEGLErrorString(egl?.eglGetError() ?: GLES20.GL_NO_ERROR))
        }

        if (egl?.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext) != true) {
            throw RuntimeException("GL Make current error: " + GLUtils.getEGLErrorString(egl?.eglGetError() ?: GLES20.GL_NO_ERROR))
        }
    }

    protected fun deinitGL() {
        egl?.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
        egl?.eglDestroySurface(eglDisplay, eglSurface)
        egl?.eglDestroyContext(eglDisplay, eglContext)
        egl?.eglTerminate(eglDisplay)
        Log.d(LOG_TAG, "OpenGL deinit OK.")
    }

    internal var shouldReInit = false

    fun reinitGL(surfaceTexture: SurfaceTexture?) {
        if (surfaceTexture != null) {
            outputTexture = surfaceTexture
        }
        shouldReInit = true
        isStarted = false
    }

    private fun createContext(egl: EGL10, eglDisplay: EGLDisplay, eglConfig: EGLConfig): EGLContext {
        val attribList = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
        return egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attribList)
    }

    protected fun chooseEglConfig(): EGLConfig? {
        val configsCount = IntArray(1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val configSpec = config

        if (egl?.eglChooseConfig(eglDisplay, configSpec, configs, 1, configsCount) != true) {
            throw IllegalArgumentException("Failed to choose config: " + GLUtils.getEGLErrorString(egl?.eglGetError() ?: GLES20.GL_NO_ERROR))
        } else if (configsCount[0] > 0) {
            return configs[0]
        }

        return null
    }

    protected val config: IntArray
        get() = intArrayOf(EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 8, EGL10.EGL_STENCIL_SIZE, 0, EGL10.EGL_NONE)


    protected var onInputTextureAvailableCallback: Runnable? = null

    // Texture to be shown in backgrund
    private val textures = IntArray(1)

    var inputTexture: SurfaceTexture? = null
        private set
    private val videoTextureTransform = FloatArray(16)
    private var frameAvailable = false

    private val frameBuffers = IntArray(1)
    private val renderBuffers = IntArray(1)
    private val offScreenTextures = IntArray(1)
    private var frameBufferPrepared: Boolean = false

    private fun setupTexture() {
        GLES20.glGenTextures(1, textures, 0)
        checkGlError("Texture generate")

        inputTexture = SurfaceTexture(textures[0])
        inputTexture!!.setOnFrameAvailableListener(this)
        if (mImageWidth != 0 && mImageHeight != 0) {
            inputTexture!!.setDefaultBufferSize(mImageWidth, mImageHeight)
        }
        if (onInputTextureAvailableCallback != null) {
            onInputTextureAvailableCallback!!.run()
        }
    }


    protected fun draw(): Boolean {

        synchronized(this) {
            if (frameAvailable) {
                inputTexture!!.updateTexImage()
                inputTexture!!.getTransformMatrix(videoTextureTransform)
                frameAvailable = false
            }
        }
        if (!frameBufferPrepared) {
            prepareFramebuffer(mImageWidth, mImageHeight)
        }
        if (!frameBufferPrepared) {
            return false
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffers[0])
        GLES20.glViewport(0, 0, mImageWidth, mImageHeight)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        mNoFilter.onDraw(textures[0], mGLCubeBuffer, mGLTextureBuffer)
        onDrawAfterNoFilter()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, mOutputWidth, mOutputHeight)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        mFilter.onDraw(offScreenTextures[0], screenCubeBuffer, screenTextureBuffer)
        onDrawAfterFilter()

        return true

    }

    protected fun onDrawAfterNoFilter() {

    }

    protected fun onDrawAfterFilter() {

    }


    protected fun initGLComponents() {
        setupTexture()
    }

    protected fun deinitGLComponents() {
        GLES20.glDeleteTextures(1, textures, 0)
        releaseFramebuffer()
        if (Build.VERSION.SDK_INT >= 14) {
            inputTexture!!.release()
        }
        inputTexture!!.setOnFrameAvailableListener(null)
    }

    fun setOutputSize(width: Int, height: Int) {
        mOutputWidth = width
        mOutputHeight = height
        mFilter.onOutputSizeChanged(width, height)
        if (outputTexture != null) {
            outputTexture!!.setDefaultBufferSize(width, height)
        }
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width != mImageWidth || height != mImageHeight) {
            mImageWidth = width
            mImageHeight = height
            frameBufferPrepared = false
            if (inputTexture != null) {
                inputTexture!!.setDefaultBufferSize(mImageWidth, mImageHeight)
            }
        }
        mNoFilter.runOnDraw {
            //NoFilter's output is a framebuffer, therefore it's size is the video size, not the display size.
            adjustImageScaling()
            mNoFilter.onOutputSizeChanged(width, height)
        }
    }


    fun checkGlError(op: String) {
        var error: Int
        do {
            error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                Log.e("SurfaceTest", op + ": glError " + GLUtils.getEGLErrorString(error))
            }
        } while (error != GLES20.GL_NO_ERROR)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        synchronized(this) {
            frameAvailable = true
        }
    }

    fun releaseFramebuffer() {
        if (offScreenTextures[0] > 0) {
            GLES20.glDeleteTextures(1, offScreenTextures, 0)
            offScreenTextures[0] = -1
        }
        if (renderBuffers[0] > 0) {
            GLES20.glDeleteRenderbuffers(1, renderBuffers, 0)
            renderBuffers[0] = -1

        }
        if (frameBuffers[0] > 0) {
            GLES20.glDeleteFramebuffers(1, frameBuffers, 0)
            frameBuffers[0] = -1
        }
        frameBufferPrepared = false
    }

    private fun prepareFramebuffer(width: Int, height: Int) {
        if (width == 0 || height == 0) return
        releaseFramebuffer()
        // Create a outputTexture object and bind it.  This will be the color buffer.
        GLES20.glGenTextures(1, offScreenTextures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, offScreenTextures[0])
        checkGlError("genTexture")
        // Create outputTexture storage.
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        checkGlError("texImage")

        // Set parameters.  We're probably using non-power-of-two dimensions, so
        // some values may not be available for use.
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE)

        // Create framebuffer object and bind it.
        GLES20.glGenFramebuffers(1, frameBuffers, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffers[0])
        checkGlError("genFramebuffer")

        // Create a depth buffer and bind it.
        GLES20.glGenRenderbuffers(1, renderBuffers, 0)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, renderBuffers[0])
        checkGlError("genRenderbuffer")

        // Allocate storage for the depth buffer.
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16,
                width, height)
        checkGlError("renderbufferStorage")

        // Attach the depth buffer and the outputTexture (color buffer) to the framebuffer object.
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER, renderBuffers[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, offScreenTextures[0], 0)

        // See if GLES is happy with all this.
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Framebuffer not complete, status=" + status)
        }

        // Switch back to the default framebuffer.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        frameBufferPrepared = true
    }

    override fun adjustImageScaling() {
        var texture = TextureRotationUtil.TEXTURE_NO_ROTATION
        when (mRotation) {
            Rotation.NORMAL -> {
            }
            Rotation.ROTATION_90 -> texture = TextureRotationUtil.TEXTURE_ROTATED_90
            Rotation.ROTATION_180 -> texture = TextureRotationUtil.TEXTURE_ROTATED_180
            Rotation.ROTATION_270 -> texture = TextureRotationUtil.TEXTURE_ROTATED_270
        }
        mGLCubeBuffer.clear()
        mGLCubeBuffer.put(CUBE).position(0)
        mGLTextureBuffer.clear()
        mGLTextureBuffer.put(texture).position(0)
    }


    val filter: GPUImageFilter
        get() = mFilter

    public override fun getFrameWidth(): Int {
        return super.getFrameWidth()
    }

    public override fun getFrameHeight(): Int {
        return super.getFrameHeight()
    }

    companion object {
        val NO_IMAGE = -1
        internal val CUBE = floatArrayOf(-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f)
        internal val SCREEN_CUBE = floatArrayOf(1.0f, 1.0f, -1.0f, 1.0f, 1.0f, -1.0f, -1.0f, -1.0f)
        internal val SCREEN_TEXTURE = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f)

        protected val EGL_OPENGL_ES2_BIT = 4
        private val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        protected val LOG_TAG = "GPUImageTextureRenderer"
    }


}
