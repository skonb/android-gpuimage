package jp.co.cyberagent.android.gpuimage.sample;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Environment;

import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_imgproc;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameRecorder;
import org.bytedeco.javacv.OpenCVFrameConverter;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;

import jp.co.cyberagent.android.gpuimage.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageTextureRenderer;

/**
 * Created by skonb on 16/02/25.
 */
public class GPUImageRecordableTextureRenderer extends GPUImageTextureRenderer {

    static class CONSTANTS {

        public final static String METADATA_REQUEST_BUNDLE_TAG = "requestMetaData";
        public final static String FILE_START_NAME = "VMS_";
        public final static String VIDEO_EXTENSION = ".mp4";
        public final static String DCIM_FOLDER = "/DCIM";
        public final static String CAMERA_FOLDER = "/Camera/GPUImage";
        public final static String TEMP_FOLDER = "/Temp";
        public final static String CAMERA_FOLDER_PATH = Environment.getExternalStorageDirectory().toString() + CONSTANTS.DCIM_FOLDER + CONSTANTS.CAMERA_FOLDER;
        public final static String TEMP_FOLDER_PATH = Environment.getExternalStorageDirectory().toString() + CONSTANTS.DCIM_FOLDER + CONSTANTS.CAMERA_FOLDER + CONSTANTS.TEMP_FOLDER;
        public final static String VIDEO_CONTENT_URI = "content://media/external/video/media";

        public final static String KEY_DELETE_FOLDER_FROM_SDCARD = "deleteFolderFromSDCard";

        public final static String RECEIVER_ACTION_SAVE_FRAME = "com.javacv.recorder.intent.action.SAVE_FRAME";
        public final static String RECEIVER_CATEGORY_SAVE_FRAME = "com.javacv.recorder";
        public final static String TAG_SAVE_FRAME = "saveFrame";

        public final static int RESOLUTION_HIGH = 1300;
        public final static int RESOLUTION_MEDIUM = 500;
        public final static int RESOLUTION_LOW = 180;

        public final static int RESOLUTION_HIGH_VALUE = 2;
        public final static int RESOLUTION_MEDIUM_VALUE = 1;
        public final static int RESOLUTION_LOW_VALUE = 0;
    }

    private static String genrateFilePath(String uniqueId, boolean isFinalPath, File tempFolderPath) {
        String fileName = CONSTANTS.FILE_START_NAME + uniqueId + CONSTANTS.VIDEO_EXTENSION;
        String dirPath = "";
        if (isFinalPath) {
            new File(CONSTANTS.CAMERA_FOLDER_PATH).mkdirs();
            dirPath = CONSTANTS.CAMERA_FOLDER_PATH;
        } else
            dirPath = tempFolderPath.getAbsolutePath();
        String filePath = dirPath + "/" + fileName;
        return filePath;
    }

    public static String createTempPath(File tempFolderPath) {
        long dateTaken = System.currentTimeMillis();
        String filePath = genrateFilePath(String.valueOf(dateTaken), false, tempFolderPath);
        return filePath;
    }


    public static File getTempFolderPath() {
        File tempFolder = new File(CONSTANTS.TEMP_FOLDER_PATH + "_" + System.currentTimeMillis());
        return tempFolder;
    }

    FFmpegFrameRecorder frameRecorder;

    public GPUImageRecordableTextureRenderer() {
        createFile();
    }

    public GPUImageRecordableTextureRenderer(GPUImageFilter filter) {
        super(filter);
        createFile();
    }

    String outputFilePath;

    public void createFile() {
        if (outputFilePath == null) {
            File tempFolder = getTempFolderPath();
            tempFolder.mkdirs();
            outputFilePath = createTempPath(tempFolder);
        }
    }

    static final int GL_BGR = 0x80E0;

    opencv_core.IplImage rgbaImage;
    opencv_core.IplImage bgraImage;
    opencv_core.IplImage bgraResizedImage;
    long startedTime = 0;
    public boolean recording = false;

    Queue<RecordingPacket> recordingQueue = new LinkedBlockingQueue<>(1000);

    static class RecordingPacket {
        Frame frame;
        long timestamp;

        public RecordingPacket(Frame frame, long timestamp) {
            this.frame = frame;
            this.timestamp = timestamp;
        }
    }


    long stopRecordingTime;


    long totalPausedTime = 0;

    int recordingWidth = 720, recordingHeight = 1280;

    @Override
    protected void onDrawAfterFilter() {
        int width = mOutputWidth, height = mOutputHeight;
        if (running) {
            if (frameRecorder == null && width > 0 && height > 0) {
                frameRecorder = new FFmpegFrameRecorder(outputFilePath, recordingWidth, recordingHeight, 1);
                frameRecorder.setFormat("mp4");
                frameRecorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
                rgbaImage = opencv_core.IplImage.create(width, height, opencv_core.IPL_DEPTH_8U, 4);
                bgraImage = opencv_core.IplImage.create(width, height, opencv_core.IPL_DEPTH_8U, 3);
                bgraResizedImage = opencv_core.IplImage.create(recordingWidth, recordingHeight, opencv_core.IPL_DEPTH_8U, 3);
                try {
                    frameRecorder.start();
                    startedTime = System.currentTimeMillis();
                } catch (FrameRecorder.Exception e) {
                    e.printStackTrace();
                }
            }
            long currentTime = System.currentTimeMillis();
            if (frameRecorder != null && recording) {
                if (stopRecordingTime != 0) {
                    long pausedTime = System.currentTimeMillis() - stopRecordingTime;
                    totalPausedTime += pausedTime;
                    stopRecordingTime = 0;
                }
                GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1);
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
                ByteBuffer buffer = (ByteBuffer) rgbaImage.createBuffer().position(0);
                bgraImage.createBuffer(0);
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
                opencv_imgproc.cvCvtColor(rgbaImage, bgraImage, opencv_imgproc.CV_RGBA2BGR);
                opencv_core.cvFlip(bgraImage);
                opencv_imgproc.cvResize(bgraImage, bgraResizedImage);
                Frame frame = new OpenCVFrameConverter.ToIplImage().convert(bgraResizedImage);
                long timestamp = 1000 * (currentTime - startedTime - totalPausedTime);
                synchronized (this) {
                    try {

                        frameRecorder.setTimestamp(timestamp);
                        frameRecorder.record(frame);
                    } catch (FrameRecorder.Exception e) {
                        e.printStackTrace();
                    }
                }
            } else {
                if (stopRecordingTime == 0) {
                    stopRecordingTime = System.currentTimeMillis();
                }
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (frameRecorder != null) {
            try {
                synchronized (this) {
                    frameRecorder.stop();
                }

            } catch (FrameRecorder.Exception e) {
                e.printStackTrace();
            }
        }
    }
}
