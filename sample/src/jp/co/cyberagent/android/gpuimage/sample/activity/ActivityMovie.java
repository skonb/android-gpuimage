package jp.co.cyberagent.android.gpuimage.sample.activity;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;

import com.malmstein.fenster.view.FensterVideoView;

import java.util.HashMap;

import jp.co.cyberagent.android.gpuimage.GPUImageBrightnessFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageGrayscaleFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageMonochromeFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageRenderer;
import jp.co.cyberagent.android.gpuimage.GPUImageSepiaFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageSharpenFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageTextureRenderer;
import jp.co.cyberagent.android.gpuimage.GPUImageWhiteBalanceFilter;
import jp.co.cyberagent.android.gpuimage.Rotation;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageRecordableTextureRenderer;
import jp.co.cyberagent.android.gpuimage.sample.R;
import wseemann.media.FFmpegMediaMetadataRetriever;

public class ActivityMovie extends Activity implements View.OnClickListener {

    static final int REQUEST_PICK_MOVIE = 1001;
    FensterVideoView videoView;
    GPUImageFilter filter = null;

    class Renderer extends GPUImageRecordableTextureRenderer implements FensterVideoView.Renderer {
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
        setContentView(R.layout.activity_movie);
        videoView = (FensterVideoView) findViewById(R.id.video_view);
        videoView.setRenderer(new Renderer());
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
        findViewById(R.id.button_choose_filter).setOnClickListener(this);
        findViewById(R.id.button_choose_movie).setOnClickListener(this);
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
                break;
            case R.id.button_choose_movie:
                videoView.pause();
                Intent moviePickerIntent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
                Intent chooserIntent = Intent.createChooser(moviePickerIntent, "Choose application to pick movie.");
                startActivityForResult(chooserIntent, REQUEST_PICK_MOVIE);
                break;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        videoView.setRenderer(new Renderer());
        ((GPUImageRenderer) videoView.getRenderer()).setRotation(rotation);
    }

    void switchFilterTo(GPUImageFilter filter) {
        this.filter = filter;
        ((GPUImageRenderer) videoView.getRenderer()).setFilter(filter);
    }

    Rotation rotation = Rotation.NORMAL;


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_PICK_MOVIE: {
                if (resultCode == Activity.RESULT_OK) {
                    Uri selectedVideo = null;
                    selectedVideo = data.getData();
                    String[] filePathColumn = new String[]{MediaStore.Video.Media.DATA, MediaStore.Video.Media.DURATION};
                    Cursor cursor = getContentResolver().query(
                            selectedVideo, filePathColumn, null, null, null);
                    cursor.moveToFirst();

                    int columnIndex = cursor.getColumnIndex(filePathColumn[0]);

                    String filePath = cursor.getString(columnIndex);
                    cursor.moveToFirst();

                    columnIndex = cursor.getColumnIndex(filePathColumn[1]);
                    long duration = cursor.getLong(columnIndex);
                    cursor.close();
                    FFmpegMediaMetadataRetriever retriever = new FFmpegMediaMetadataRetriever();
                    if (selectedVideo != null) {
                        retriever.setDataSource(this, selectedVideo);
                        videoView.setVideo(selectedVideo, 0);
                    } else {
                        retriever.setDataSource(filePath);
                        videoView.setVideo(filePath, 0);
                    }
                    String rotation = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                    switch (rotation) {
                        case "90":
                            this.rotation = Rotation.ROTATION_90;
                            break;
                        case "180":
                            this.rotation = Rotation.ROTATION_180;
                            break;
                        case "270":
                            this.rotation = Rotation.ROTATION_270;
                            break;
                        case "0":
                            this.rotation = Rotation.NORMAL;
                            break;
                    }

                    videoView.resume();
                }
            }
        }
    }
}
