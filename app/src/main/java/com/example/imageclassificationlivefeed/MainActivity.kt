package com.example.imageclassificationlivefeed

import android.app.Fragment
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.Image.Plane
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.imageclassificationkotlin.Classifier
import com.example.imageclassificationlivefeed.ImageUtils.convertYUV420ToARGB8888
import java.io.IOException

class MainActivity : AppCompatActivity(),ImageReader.OnImageAvailableListener {

    var classifier: Classifier? = null
    var resultTV: TextView? = null
    var handler: Handler? = null
    var sensorOrientation = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        handler = Handler()

        //TODO show live camera footage
        setFragment()
        resultTV = findViewById(R.id.textView)

        //TODO inialize classifier class
        try {
            classifier = Classifier(assets,"mobilenet_v1_1.0_224.tflite", "mobilenet_v1_1.0_224.txt",224)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    var previewHeight = 0;
    var previewWidth = 0
    //TODO fragment which show llive footage from camera
    protected fun setFragment() {
        val manager =
            getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var cameraId: String? = null
        try {
            cameraId = manager.cameraIdList[0]
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        val fragment: Fragment
        val camera2Fragment = CameraConnectionFragment.newInstance(
            object :
                CameraConnectionFragment.ConnectionCallback {
                override fun onPreviewSizeChosen(size: Size?, rotation: Int) {
                    previewHeight = size!!.height
                    previewWidth = size.width
                    sensorOrientation = rotation - getScreenOrientation()
                    //                                    CameraActivity.this.onPreviewSizeChosen(size, rotation);
                }
            },
            this,
            R.layout.camera_fragment,
            Size(640, 480)
        )
        camera2Fragment.setCamera(cameraId)
        fragment = camera2Fragment
        fragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }

    //TODO getting frames of live camera footage and passing them to model
    private var isProcessingFrame = false
    private val yuvBytes = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    private var yRowStride = 0
    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null
    private var rgbFrameBitmap: Bitmap? = null
    override fun onImageAvailable(reader: ImageReader) {
        Log.d("tryImage", "Closed")


        // We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }
        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }
        try {
            val image = reader.acquireLatestImage() ?: return
            if (isProcessingFrame) {
                image.close()
                return
            }
            isProcessingFrame = true
            val planes = image.planes
            fillBytes(planes, yuvBytes)
            yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride
            imageConverter = Runnable {
                convertYUV420ToARGB8888(
                    yuvBytes[0]!!,
                    yuvBytes[1]!!,
                    yuvBytes[2]!!,
                    previewWidth,
                    previewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    rgbBytes!!
                )
            }
            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }
            processImage()
        } catch (e: Exception) {
            Log.d("tryError", e.message + "abc ")
            return
        }
    }


    var result = ""
    private fun processImage() {
        imageConverter!!.run()
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        rgbFrameBitmap?.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight)
        val recognitionList =
            classifier!!.recognizeImage(rotateBitmap(rgbFrameBitmap!!)!!)
        result = ""
        for (i in recognitionList.indices) {
            result += "${recognitionList[i].title}  ${recognitionList[i].confidence}"
        }
        handler!!.post { resultTV!!.text = result }
        postInferenceCallback!!.run()
    }

    protected fun fillBytes(
        planes: Array<Plane>,
        yuvBytes: Array<ByteArray?>
    ) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer[yuvBytes[i]]
        }
    }

    protected fun getScreenOrientation(): Int {
        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 90
            else -> 0
        }
    }

    //TODO rotate image if image captured on sumsong devices
    //Most phone cameras are landscape, meaning if you take the photo in portrait, the resulting photos will be rotated 90 degrees.
    fun rotateBitmap(input: Bitmap): Bitmap? {
        Log.d("trySensor", sensorOrientation.toString() + "     " + getScreenOrientation())
        val rotationMatrix = Matrix()
        rotationMatrix.setRotate(sensorOrientation.toFloat())
        return Bitmap.createBitmap(input, 0, 0, input.width, input.height, rotationMatrix, true)
    }


}