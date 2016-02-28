package jp.co.cyberagent.android.gpuimage;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil;

/**
 * Created by skonb on 16/02/27.
 */
public class GPUImageCanvasOverlayFilter extends GPUImageFilter implements SurfaceTexture.OnFrameAvailableListener {
    public interface OnSurfaceAvailableListener {
        void onSurfaceAvailable(GPUImageCanvasOverlayFilter filter, Surface surface);
    }

    static final float CUBE[] = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f,
    };

    public GPUImageCanvasOverlayFilter(OnSurfaceAvailableListener onSurfaceAvailableListener) {
        super();
        this.onSurfaceAvailableListener = onSurfaceAvailableListener;
    }

    OnSurfaceAvailableListener onSurfaceAvailableListener;
    SurfaceTexture canvasTexture;
    Surface canvasSurface;
    FloatBuffer canvasCubeBuffer;
    FloatBuffer canvasTextureBuffer;
    int[] textures = new int[1];
    boolean frameAvailable;
    int canvasProgram;
    protected int canvasAttribPosition;
    protected int canvasUniformTexture;
    protected int canvasAttribTextureCoordinate;

    @Override
    public void onInitialized() {
        super.onInitialized();
        String fragmentShader = mExternalOES ? mFragmentShader : getExternalOESFragmentShader(mFragmentShader, true);
        canvasProgram = OpenGlUtils.loadProgram(mVertexShader, fragmentShader);
        canvasAttribPosition = GLES20.glGetAttribLocation(canvasProgram, "position");
        canvasUniformTexture = GLES20.glGetUniformLocation(canvasProgram, "inputImageTexture");
        canvasAttribTextureCoordinate = GLES20.glGetAttribLocation(canvasProgram,
                "inputTextureCoordinate");
        setupTexture();
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDepthMask(true);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
    }

    void setupTexture() {

        canvasTextureBuffer = (FloatBuffer) ByteBuffer.allocateDirect(TextureRotationUtil.TEXTURE_NO_ROTATION.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(TextureRotationUtil
                .TEXTURE_NO_ROTATION).position(0);

        canvasCubeBuffer = (FloatBuffer) ByteBuffer.allocateDirect(CUBE.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(CUBE).position(0);

        // Generate the actual texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glGenTextures(1, textures, 0);

        canvasTexture = new SurfaceTexture(textures[0]);
        canvasTexture.setOnFrameAvailableListener(this);
        canvasTexture.setDefaultBufferSize(mOutputWidth, mOutputHeight);
        canvasSurface = new Surface(canvasTexture);

        if (onSurfaceAvailableListener != null) {
            onSurfaceAvailableListener.onSurfaceAvailable(this, canvasSurface);
        }
    }

    @Override
    public void onOutputSizeChanged(int width, int height) {
        super.onOutputSizeChanged(width, height);
        if (canvasTexture != null) {
            canvasTexture.setDefaultBufferSize(mOutputWidth, mOutputHeight);
        }
    }

    public Surface getCanvasSurface() {
        return canvasSurface;
    }

    @Override
    public void onDraw(int textureId, FloatBuffer cubeBuffer, FloatBuffer textureBuffer) {
        super.onDraw(textureId, cubeBuffer, textureBuffer);
        if (canvasSurface != null) {
            if (frameAvailable) {
                canvasTexture.updateTexImage();
                frameAvailable = false;
            }
            GLES20.glUseProgram(canvasProgram);
            canvasCubeBuffer.position(0);
            GLES20.glVertexAttribPointer(canvasAttribPosition, 2, GLES20.GL_FLOAT, false, 0, canvasCubeBuffer);
            GLES20.glEnableVertexAttribArray(canvasAttribPosition);
            canvasTextureBuffer.position(0);
            GLES20.glVertexAttribPointer(canvasAttribTextureCoordinate, 2, GLES20.GL_FLOAT, false, 0,
                    canvasTextureBuffer);
            GLES20.glEnableVertexAttribArray(canvasAttribTextureCoordinate);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
            GLES20.glUniform1i(canvasUniformTexture, 2);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(canvasAttribPosition);
            GLES20.glDisableVertexAttribArray(canvasAttribTextureCoordinate);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (this) {
            frameAvailable = true;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glDeleteTextures(1, textures, 0);
        if (Build.VERSION.SDK_INT >= 14) {
            canvasTexture.release();
        }
        canvasTexture.setOnFrameAvailableListener(null);
        GLES20.glDeleteProgram(canvasProgram);
    }
}
