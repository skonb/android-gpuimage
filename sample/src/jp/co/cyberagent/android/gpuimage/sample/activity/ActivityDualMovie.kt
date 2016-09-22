package jp.co.cyberagent.android.gpuimage.sample.activity

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import com.malmstein.fenster.view.FensterDualVideoView
import jp.co.cyberagent.android.gpuimage.GPUImageDualTextureRenderer
import jp.co.cyberagent.android.gpuimage.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.GPUImageRenderer
import jp.co.cyberagent.android.gpuimage.Rotation
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools
import jp.co.cyberagent.android.gpuimage.sample.R
import kotlinx.android.synthetic.main.activity_dual_movie.*
import wseemann.media.FFmpegMediaMetadataRetriever

class ActivityDualMovie : Activity(), View.OnClickListener {


    internal val REQUEST_PICK_MOVIE = 1001
    internal var filter: GPUImageFilter? = null

    internal inner class Renderer : GPUImageDualTextureRenderer, FensterDualVideoView.Renderer {
        constructor() : super() {
        }

        constructor(filter: GPUImageFilter) : super(filter) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dual_movie)
        video_view.renderer = Renderer()
        filter = (video_view.renderer as GPUImageDualTextureRenderer).filter
        video_view.setVideo(0, "http://clips.vorwaerts-gmbh.de/big_buck_bunny.mp4", 0)
        video_view.setVideo(1, "http://clips.vorwaerts-gmbh.de/big_buck_bunny.mp4", 0)
        video_view.setOnPreparedListener { mediaPlayer ->

        }
        video_view.start(0)
        video_view.start(1)
        findViewById(R.id.button_choose_filter).setOnClickListener(this)
        findViewById(R.id.button_choose_movie).setOnClickListener(this)
    }


    var pickingMovieIndex = 0

    override fun onClick(v: View) {
        when (v.id) {
            R.id.button_choose_filter -> GPUImageFilterTools.showDialog(this) { filter -> switchFilterTo(filter) }
            R.id.button_choose_movie -> {
                video_view.pause(0)
                video_view.pause(1)
                AlertDialog.Builder(this)
                        .setMessage("どちらの動画を選択しますか?")
                        .setItems(arrayOf("一つ目", "二つ目"), { dialog, which ->
                            pickingMovieIndex = which
                            val moviePickerIntent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                            val chooserIntent = Intent.createChooser(moviePickerIntent, "Choose application to pick movie.")
                            startActivityForResult(chooserIntent, REQUEST_PICK_MOVIE)
                        })

            }
        }
    }

    override fun onResume() {
        super.onResume()
        video_view.renderer = Renderer()
        (video_view.renderer as GPUImageRenderer).rotation = rotation
    }

    internal fun switchFilterTo(filter: GPUImageFilter) {
        this.filter = filter
        (video_view.renderer as GPUImageRenderer).setFilter(filter)
    }

    internal var rotation = Rotation.NORMAL


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PICK_MOVIE -> {
                if (resultCode == Activity.RESULT_OK) {
                    var selectedVideo: Uri? = null
                    selectedVideo = data.data
                    val filePathColumn = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DURATION)
                    val cursor = contentResolver.query(
                            selectedVideo!!, filePathColumn, null, null, null)
                    cursor!!.moveToFirst()

                    var columnIndex = cursor.getColumnIndex(filePathColumn[0])

                    val filePath = cursor.getString(columnIndex)
                    cursor.moveToFirst()

                    columnIndex = cursor.getColumnIndex(filePathColumn[1])
                    val duration = cursor.getLong(columnIndex)
                    cursor.close()
                    val retriever = FFmpegMediaMetadataRetriever()
                    if (selectedVideo != null) {
                        retriever.setDataSource(this, selectedVideo)
                        video_view.setVideo(pickingMovieIndex, selectedVideo, 0)
                    } else {
                        retriever.setDataSource(filePath)
                        video_view.setVideo(pickingMovieIndex, filePath, 0)
                    }
                    val rotation = retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    when (rotation) {
                        "90" -> this.rotation = Rotation.ROTATION_90
                        "180" -> this.rotation = Rotation.ROTATION_180
                        "270" -> this.rotation = Rotation.ROTATION_270
                        "0" -> this.rotation = Rotation.NORMAL
                    }

                    video_view.resume(0)
                    video_view.resume(1)
                }
            }
        }
    }
}
