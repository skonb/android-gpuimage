package jp.co.cyberagent.android.gpuimage.sample.activity;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;

import com.malmstein.fenster.view.FensterVideoView;

import jp.co.cyberagent.android.gpuimage.GPUImageBrightnessFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageGrayscaleFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageMonochromeFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageRenderer;
import jp.co.cyberagent.android.gpuimage.GPUImageSepiaFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageSharpenFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageTextureRenderer;
import jp.co.cyberagent.android.gpuimage.GPUImageWhiteBalanceFilter;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools;
import jp.co.cyberagent.android.gpuimage.sample.R;

public class ActivityMovie extends Activity implements View.OnClickListener {

    FensterVideoView videoView;
    GPUImageFilter filter = new GPUImageFilter();

    class Renderer extends GPUImageTextureRenderer implements FensterVideoView.Renderer {
        public Renderer(final GPUImageFilter filter) {
            super(filter);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_movie);
        videoView = (FensterVideoView) findViewById(R.id.video_view);
        videoView.setRenderer(new Renderer(filter));
        videoView.setVideo("http://clips.vorwaerts-gmbh.de/big_buck_bunny.mp4", 0);
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                videoView.start();
            }
        });
        videoView.start();
        findViewById(R.id.button_choose_filter).setOnClickListener(this);

    }

    @Override
    public void onClick(final View v) {
        switch (v.getId()) {
            case R.id.button_choose_filter:
                GPUImageFilterTools.showDialog(this, new GPUImageFilterTools.OnGpuImageFilterChosenListener() {

                    @Override
                    public void onGpuImageFilterChosenListener(final GPUImageFilter filter) {
                        switchFilterTo(filter);
                    }
                });
        }
    }

    void switchFilterTo(GPUImageFilter filter) {
        this.filter = filter;
        ((GPUImageRenderer) videoView.getRenderer()).setFilter(filter);
    }

}
