/*
 * Copyright (C) 2012 CyberAgent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.co.cyberagent.android.gpuimage;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

import jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil;

import static jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.TEXTURE_NO_ROTATION;
import static jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.TEXTURE_ROTATED_180;
import static jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.TEXTURE_ROTATED_270;
import static jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.TEXTURE_ROTATED_90;

@TargetApi(11)
public class GPUImageTextureRenderer extends GPUImageRenderer implements SurfaceTexture.OnFrameAvailableListener {
    static final float[] SCREEN_CUBE = {
            1.0f, 1.0f,
            -1.0f, 1.0f,
            1.0f, -1.0f,
            -1.0f, -1.0f
    };
    static final float[] SCREEN_TEXTURE = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
    };

    public GPUImageTextureRenderer() {
        this(new GPUImageFilter());
    }

    public GPUImageTextureRenderer(final GPUImageFilter filter) {
        super(filter);
        screenCubeBuffer = ByteBuffer.allocateDirect(CUBE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        screenCubeBuffer.put(CUBE).position(0);
        screenTextureBuffer = ByteBuffer.allocateDirect(SCREEN_TEXTURE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        screenTextureBuffer.put(SCREEN_TEXTURE).position(0);
    }

    protected static final int EGL_OPENGL_ES2_BIT = 4;
    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
    protected static final String LOG_TAG = "GPUImageTextureRenderer";
    protected SurfaceTexture outputTexture;
    protected EGL10 egl;
    protected EGLDisplay eglDisplay;
    protected EGLContext eglContext;
    protected EGLSurface eglSurface;

    protected boolean running;
    GPUImageFilter mNoFilter = new GPUImageFilter();
    protected FloatBuffer screenCubeBuffer;
    protected FloatBuffer screenTextureBuffer;


    public void startRenderingToOutput(SurfaceTexture outputTexture, Runnable onInputTextureAvailableCallback) {
        this.outputTexture = outputTexture;
        this.onInputTextureAvailableCallback = onInputTextureAvailableCallback;
        if (mImageHeight != 0 && mImageWidth != 0) {
            outputTexture.setDefaultBufferSize(mImageWidth, mImageHeight);
        }
        mNoFilter.runOnDraw(new Runnable() {
            @Override
            public void run() {
                mNoFilter.init();
                mNoFilter.setExternalOES(true);
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                GPUImageTextureRenderer.this.run();
            }
        }).start();
    }

    public boolean isStarted() {
        return running;
    }

    public void run() {
        running = true;
        try {
            //Waiting for subclass constructor
            Thread.sleep(100);
        } catch (InterruptedException e) {

        }
        initGL();
        initGLComponents();
        if (mFilter != null) {
            if (!mFilter.isInitialized()) {
                mFilter.runOnDraw(new Runnable() {
                    @Override
                    public void run() {
                        mFilter.init();
                    }
                });
            }
            mFilter.setGLTexture(GLES20.GL_TEXTURE1);
        }
        Log.d(LOG_TAG, "OpenGL init OK.");
        while (running) {
            runAll(mRunOnDraw);
            long loopStart = System.currentTimeMillis();
            pingFps();

            if (draw()) {
                egl.eglSwapBuffers(eglDisplay, eglSurface);
            }
            runAll(mRunOnDrawEnd);
            long waitDelta = 16 - (System.currentTimeMillis() - loopStart);    // Targeting 60 fps, no need for faster
            if (waitDelta > 0) {
                try {
                    Thread.sleep(waitDelta);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    continue;
                }
            }
        }

        deinitGLComponents();
        deinitGL();
        if (shouldReInit) {
            shouldReInit = false;
            run();
        }
    }

    private long lastFpsOutput = 0;
    private int frames;

    protected void pingFps() {
        if (lastFpsOutput == 0)
            lastFpsOutput = System.currentTimeMillis();

        frames++;

        if (System.currentTimeMillis() - lastFpsOutput > 1000) {
            lastFpsOutput = System.currentTimeMillis();
            frames = 0;
        }
    }


    /**
     * Call when activity pauses. This stops the rendering thread and deinitializes OpenGL.
     */
    public void onPause() {
        running = false;
    }


    protected void initGL() {
        egl = (EGL10) EGLContext.getEGL();
        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

        int[] version = new int[2];
        egl.eglInitialize(eglDisplay, version);

        EGLConfig eglConfig = chooseEglConfig();
        eglContext = createContext(egl, eglDisplay, eglConfig);

        eglSurface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, outputTexture, null);

        if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
            throw new RuntimeException("GL Error: " + GLUtils.getEGLErrorString(egl.eglGetError()));
        }

        if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("GL Make current error: " + GLUtils.getEGLErrorString(egl.eglGetError()));
        }
    }

    protected void deinitGL() {
        egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
        egl.eglDestroySurface(eglDisplay, eglSurface);
        egl.eglDestroyContext(eglDisplay, eglContext);
        egl.eglTerminate(eglDisplay);
        Log.d(LOG_TAG, "OpenGL deinit OK.");
    }

    boolean shouldReInit = false;

    public void reinitGL(SurfaceTexture surfaceTexture) {
        if (surfaceTexture != null) {
            outputTexture = surfaceTexture;
        }
        shouldReInit = true;
        running = false;
    }

    private EGLContext createContext(EGL10 egl, EGLDisplay eglDisplay, EGLConfig eglConfig) {
        int[] attribList = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE};
        return egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attribList);
    }

    protected EGLConfig chooseEglConfig() {
        int[] configsCount = new int[1];
        EGLConfig[] configs = new EGLConfig[1];
        int[] configSpec = getConfig();

        if (!egl.eglChooseConfig(eglDisplay, configSpec, configs, 1, configsCount)) {
            throw new IllegalArgumentException("Failed to choose config: " + GLUtils.getEGLErrorString(egl.eglGetError()));
        } else if (configsCount[0] > 0) {
            return configs[0];
        }

        return null;
    }

    protected int[] getConfig() {
        return new int[]{
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 8,
                EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_NONE
        };
    }


    protected Runnable onInputTextureAvailableCallback;

    // Texture to be shown in backgrund
    private int[] textures = new int[1];

    private SurfaceTexture inputTexture;
    private float[] videoTextureTransform = new float[16];
    ;
    private boolean frameAvailable = false;

    private int[] frameBuffers = new int[1];
    private int[] renderBuffers = new int[1];
    private int[] offScreenTextures = new int[1];
    private boolean frameBufferPrepared;

    private void setupTexture() {
        GLES20.glGenTextures(1, textures, 0);
        checkGlError("Texture generate");

        inputTexture = new SurfaceTexture(textures[0]);
        inputTexture.setOnFrameAvailableListener(this);
        if (mImageWidth != 0 && mImageHeight != 0) {
            inputTexture.setDefaultBufferSize(mImageWidth, mImageHeight);
        }
        if (onInputTextureAvailableCallback != null) {
            onInputTextureAvailableCallback.run();
        }
    }


    protected boolean draw() {

        synchronized (this) {
            if (frameAvailable) {
                inputTexture.updateTexImage();
                inputTexture.getTransformMatrix(videoTextureTransform);
                frameAvailable = false;
            }
        }
        if (!frameBufferPrepared) {
            prepareFramebuffer(mImageWidth, mImageHeight);
        }
        if (!frameBufferPrepared) {
            return false;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffers[0]);
        GLES20.glViewport(0, 0, mImageWidth, mImageHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        mNoFilter.onDraw(textures[0], mGLCubeBuffer, mGLTextureBuffer, videoTextureTransform);
        onDrawAfterNoFilter();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, mOutputWidth, mOutputHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        mFilter.onDraw(offScreenTextures[0], screenCubeBuffer, screenTextureBuffer);
        onDrawAfterFilter();

        return true;

    }

    protected void onDrawAfterNoFilter() {

    }

    protected void onDrawAfterFilter() {

    }


    protected void initGLComponents() {
        setupTexture();
    }

    protected void deinitGLComponents() {
        GLES20.glDeleteTextures(1, textures, 0);
        releaseFramebuffer();
        if (Build.VERSION.SDK_INT >= 14) {
            inputTexture.release();
        }
        inputTexture.setOnFrameAvailableListener(null);
    }

    public void setOutputSize(int width, int height) {
        mOutputWidth = width;
        mOutputHeight = height;
        mFilter.onOutputSizeChanged(width, height);
        if (outputTexture != null) {
            outputTexture.setDefaultBufferSize(width, height);
        }
    }

    public void setVideoSize(final int width, final int height) {
        if (width != mImageWidth || height != mImageHeight) {
            mImageWidth = width;
            mImageHeight = height;
            frameBufferPrepared = false;
            if (inputTexture != null) {
                inputTexture.setDefaultBufferSize(mImageWidth, mImageHeight);
            }
        }
        mNoFilter.runOnDraw(new Runnable() {
            @Override
            public void run() {
                //NoFilter's output is a framebuffer, therefore it's size is the video size, not the display size.
                adjustImageScaling();
                mNoFilter.onOutputSizeChanged(width, height);
            }
        });
    }


    public void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e("SurfaceTest", op + ": glError " + GLUtils.getEGLErrorString(error));
        }
    }

    public SurfaceTexture getInputTexture() {
        return inputTexture;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (this) {
            frameAvailable = true;
        }
    }

    public void releaseFramebuffer() {
        if (offScreenTextures[0] > 0) {
            GLES20.glDeleteTextures(1, offScreenTextures, 0);
            offScreenTextures[0] = -1;
        }
        if (renderBuffers[0] > 0) {
            GLES20.glDeleteRenderbuffers(1, renderBuffers, 0);
            renderBuffers[0] = -1;

        }
        if (frameBuffers[0] > 0) {
            GLES20.glDeleteFramebuffers(1, frameBuffers, 0);
            frameBuffers[0] = -1;
        }
        frameBufferPrepared = false;
    }

    private void prepareFramebuffer(int width, int height) {
        if (width == 0 || height == 0) return;
        releaseFramebuffer();
        // Create a outputTexture object and bind it.  This will be the color buffer.
        GLES20.glGenTextures(1, offScreenTextures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, offScreenTextures[0]);
        checkGlError("genTexture");
        // Create outputTexture storage.
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        checkGlError("texImage");

        // Set parameters.  We're probably using non-power-of-two dimensions, so
        // some values may not be available for use.
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        // Create framebuffer object and bind it.
        GLES20.glGenFramebuffers(1, frameBuffers, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffers[0]);
        checkGlError("genFramebuffer");

        // Create a depth buffer and bind it.
        GLES20.glGenRenderbuffers(1, renderBuffers, 0);
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, renderBuffers[0]);
        checkGlError("genRenderbuffer");

        // Allocate storage for the depth buffer.
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16,
                width, height);
        checkGlError("renderbufferStorage");

        // Attach the depth buffer and the outputTexture (color buffer) to the framebuffer object.
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER, renderBuffers[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, offScreenTextures[0], 0);

        // See if GLES is happy with all this.
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Framebuffer not complete, status=" + status);
        }

        // Switch back to the default framebuffer.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        frameBufferPrepared = true;
    }

    @Override
    protected void adjustImageScaling() {
        float[] texture = TextureRotationUtil.getRotation(mRotation, false, true);
        mGLCubeBuffer.clear();
        mGLCubeBuffer.put(CUBE).position(0);
        mGLTextureBuffer.clear();
        mGLTextureBuffer.put(texture).position(0);
    }


    public GPUImageFilter getFilter() {
        return mFilter;
    }

    @Override
    public int getFrameWidth() {
        return super.getFrameWidth();
    }

    @Override
    public int getFrameHeight() {
        return super.getFrameHeight();
    }


}
