package jp.co.cyberagent.android.gpuimage.sample.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.view.View;

import com.malmstein.fenster.view.FensterVideoView;

import java.util.Timer;
import java.util.TimerTask;

import jp.co.cyberagent.android.gpuimage.GPUImageCanvasOverlayFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageRenderer;
import jp.co.cyberagent.android.gpuimage.GPUImageTextureRenderer;
import jp.co.cyberagent.android.gpuimage.Rotation;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageRecordableTextureRenderer;
import jp.co.cyberagent.android.gpuimage.sample.R;

public class ActivityCanvas extends Activity implements GPUImageCanvasOverlayFilter.OnSurfaceAvailableListener {
    FensterVideoView videoView;
    GPUImageFilter filter = null;

    class Renderer extends GPUImageTextureRenderer implements FensterVideoView.Renderer {
        public Renderer() {
            super();
        }

        public Renderer(final GPUImageFilter filter) {
            super(filter);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_activity_canvas);
        videoView = (FensterVideoView) findViewById(R.id.video_view);
        videoView.setRenderer(new Renderer(new GPUImageCanvasOverlayFilter(this)));
        filter = ((GPUImageTextureRenderer) videoView.getRenderer()).getFilter();
        videoView.setVideo("http://clips.vorwaerts-gmbh.de/big_buck_bunny.mp4", 0);
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (!videoView.isPlaying()) {
                    videoView.start();
                } else {
                    videoView.resume();
                }
            }
        });
        videoView.start();
    }


    @Override
    protected void onResume() {
        super.onResume();
        videoView.setRenderer(new Renderer(new GPUImageCanvasOverlayFilter(this)));
    }


    Surface canvasSurface;

    void draw() {
        GPUImageTextureRenderer renderer = (GPUImageTextureRenderer) videoView.getRenderer();
        Rect dirtyRect = new Rect(0, 0, renderer.getFrameWidth(), renderer.getFrameHeight());
        Canvas canvas = canvasSurface.lockCanvas(dirtyRect);
        Log.i("TEST", String.format("canvas rect:(%d, %d, %d, %d)", dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom));
        canvas.drawColor(Color.TRANSPARENT);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        canvas.drawLine(0, 0, 200, 200, paint);
        canvasSurface.unlockCanvasAndPost(canvas);
    }

    @Override
    public void onSurfaceAvailable(GPUImageCanvasOverlayFilter filter, Surface surface) {
        canvasSurface = surface;
        draw();
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                draw();
            }
        }, 1000);
    }
}
