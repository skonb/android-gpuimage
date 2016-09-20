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
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

import static jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.TEXTURE_NO_ROTATION;
import static jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.TEXTURE_ROTATED_180;
import static jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.TEXTURE_ROTATED_270;
import static jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.TEXTURE_ROTATED_90;

@TargetApi(11)
public class GPUImageDualTextureRenderer extends GPUImageRenderer implements SurfaceTexture.OnFrameAvailableListener {
    static final int N = 2;
    static final int WIDTH = 0;
    static final int HEIGHT = 1;
    static final float LEFT_CUBE[] = {
            -1.0f, -1.0f,
            0.0f, -1.0f,
            -1.0f, 1.0f,
            0.0f, 1.0f,
    };
    static final float RIGHT_CUBE[] = {
            0.0f, -1.0f,
            1.0f, -1.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
    };
    static final float TOP_CUBE[] = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 0.0f,
            1.0f, 0.0f,
    };
    static final float BOTTOM_CUBE[] = {
            -1.0f, 0.0f,
            1.0f, 0.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f,
    };


    static final float[] SCREEN_TEXTURE = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
    };

    public GPUImageDualTextureRenderer() {
        this(new GPUImageFilter());
    }

    public GPUImageDualTextureRenderer(final GPUImageFilter filter) {
        super(filter);
        for (int i = 0; i < N; ++i) {
            screenCubeBuffers[i] = ByteBuffer.allocateDirect(CUBE.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            screenTextureBuffers[i] = ByteBuffer.allocateDirect(SCREEN_TEXTURE.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();

            screenTextureBuffers[i].put(SCREEN_TEXTURE).position(0);
        }
        screenCubeBuffers[0].put(LEFT_CUBE).position(0);
        screenCubeBuffers[1].put(RIGHT_CUBE).position(0);
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
    protected FloatBuffer[] screenCubeBuffers = new FloatBuffer[N];
    protected FloatBuffer[] screenTextureBuffers = new FloatBuffer[N];


    public void startRenderingToOutput(SurfaceTexture outputTexture, Runnable onInputTextureAvailableCallback) {
        this.outputTexture = outputTexture;
        this.onInputTextureAvailableCallback = onInputTextureAvailableCallback;
//        if (mImageHeight != 0 && mImageWidth != 0) {
//            outputTexture.setDefaultBufferSize(mImageWidth, mImageHeight);
//        }
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
                GPUImageDualTextureRenderer.this.run();
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
    private int[] textures = new int[N];

    private SurfaceTexture[] inputTextures = new SurfaceTexture[N];
    private int[][] imageSizes = new int[N][2];
    private float[][] videoTextureTransforms = new float[N][16];

    private boolean[] frameAvailable = {
            false, false
    };

    private int[] frameBuffers = new int[N];
    private int[] renderBuffers = new int[N];
    private int[] offScreenTextures = new int[N];
    private boolean[] frameBufferPrepared = new boolean[N];

    private void setupTexture() {
        GLES20.glGenTextures(N, textures, 0);
        checkGlError("Texture generate");
        for (int i = 0; i < N; ++i) {
            inputTextures[i] = new SurfaceTexture(textures[i]);
            inputTextures[i].setOnFrameAvailableListener(this);
            if (imageSizes[i][WIDTH] != 0 && imageSizes[i][HEIGHT] != 0) {
                inputTextures[i].setDefaultBufferSize(imageSizes[i][WIDTH], imageSizes[i][HEIGHT]);
            }
        }
        if (onInputTextureAvailableCallback != null) {
            onInputTextureAvailableCallback.run();
        }
    }


    protected boolean draw() {

        synchronized (this) {
            for (int i = 0; i < N; ++i) {
                if (frameAvailable[i]) {
                    inputTextures[i].updateTexImage();
                    inputTextures[i].getTransformMatrix(videoTextureTransforms[i]);
                    frameAvailable[i] = false;
                }
            }
        }
        for (int i = 0; i < N; ++i) {
            if (!frameBufferPrepared[i]) {
                prepareFramebuffer(i, imageSizes[i][WIDTH], imageSizes[i][HEIGHT]);
            }
        }

        for (int i = 0; i < N; ++i) {
            if (!frameBufferPrepared[i]) {
                return false;
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffers[i]);
            GLES20.glViewport(0, 0, imageSizes[i][WIDTH], imageSizes[i][HEIGHT]);
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            mNoFilter.onDraw(textures[i], mGLCubeBuffer, mGLTextureBuffer);
        }
        onDrawAfterNoFilter();


        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, mOutputWidth, mOutputHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        for (int i = 0; i < N; ++i) {
            mFilter.onDraw(offScreenTextures[i], screenCubeBuffers[i], screenTextureBuffers[i]);
        }
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
        GLES20.glDeleteTextures(N, textures, 0);
        for (int i = 0; i < N; ++i) {
            releaseFramebuffer(i);
            if (Build.VERSION.SDK_INT >= 14) {
                inputTextures[i].release();
            }
            inputTextures[i].setOnFrameAvailableListener(null);
        }

    }

    public void setOutputSize(int width, int height) {
        mOutputWidth = width;
        mOutputHeight = height;
        mFilter.onOutputSizeChanged(width, height);
        if (outputTexture != null) {
            outputTexture.setDefaultBufferSize(width, height);
        }
    }

    public void setVideoSize(final int index, final int width, final int height) {
        if (width != imageSizes[index][0] || height != imageSizes[index][1]) {
            imageSizes[index][0] = width;
            imageSizes[index][1] = height;
            frameBufferPrepared[index] = false;
            if (inputTextures[index] != null) {
                inputTextures[index].setDefaultBufferSize(width, height);
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
        return inputTextures[0];
    }

    public SurfaceTexture[] getInputTextures() {
        return inputTextures;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (this) {
            for (int i = 0; i < N; ++i) {
                if (surfaceTexture == inputTextures[i]) {
                    frameAvailable[i] = true;
                    break;
                }
            }
        }
    }

    public void releaseFramebuffer(int index) {
        if (offScreenTextures[index] > 0) {
            GLES20.glDeleteTextures(1, offScreenTextures, index);
            offScreenTextures[index] = -1;
        }
        if (renderBuffers[index] > 0) {
            GLES20.glDeleteRenderbuffers(1, renderBuffers, index);
            renderBuffers[index] = -1;

        }
        if (frameBuffers[index] > 0) {
            GLES20.glDeleteFramebuffers(1, frameBuffers, index);
            frameBuffers[index] = -1;
        }
        frameBufferPrepared[index] = false;
    }

    private void prepareFramebuffer(int index, int width, int height) {
        if (width == 0 || height == 0) return;
        releaseFramebuffer(index);
        // Create a outputTexture object and bind it.  This will be the color buffer.
        GLES20.glGenTextures(1, offScreenTextures, index);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, offScreenTextures[index]);
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
        GLES20.glGenFramebuffers(1, frameBuffers, index);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffers[index]);
        checkGlError("genFramebuffer");

        // Create a depth buffer and bind it.
        GLES20.glGenRenderbuffers(1, renderBuffers, index);
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, renderBuffers[index]);
        checkGlError("genRenderbuffer");

        // Allocate storage for the depth buffer.
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16,
                width, height);
        checkGlError("renderbufferStorage");

        // Attach the depth buffer and the outputTexture (color buffer) to the framebuffer object.
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER, renderBuffers[index]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, offScreenTextures[index], 0);

        // See if GLES is happy with all this.
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Framebuffer not complete, status=" + status);
        }

        // Switch back to the default framebuffer.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        frameBufferPrepared[index] = true;
    }

    @Override
    protected void adjustImageScaling() {
        float[] texture = TEXTURE_NO_ROTATION;
        switch (mRotation) {
            case NORMAL:
                break;
            case ROTATION_90:
                texture = TEXTURE_ROTATED_90;
                break;
            case ROTATION_180:
                texture = TEXTURE_ROTATED_180;
                break;
            case ROTATION_270:
                texture = TEXTURE_ROTATED_270;
                break;
        }
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
