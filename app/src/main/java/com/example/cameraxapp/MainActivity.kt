package com.example.cameraxapp

//import org.tensorflow.lite.Interpreter
//import org.tensorflow.lite.
import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Half
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.CameraSelector.LENS_FACING_BACK
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.example.cameraxapp.databinding.ActivityMainBinding
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.Tensor.QuantizationParams
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min


class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var interpreter: Interpreter
    private lateinit var inputTensor: Tensor
    private lateinit var inputShape: IntArray
    private lateinit var inputType: DataType
    private var inputSize: Int = 0
    private lateinit var outputTensor: Tensor
    private lateinit var outputShape: IntArray
    private lateinit var outputType: DataType
    private var outputSize: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        val tfliteModel = loadModelFile(this)
        val options = Interpreter.Options()
        options.useNNAPI = true
        options.numThreads = 4
        interpreter = Interpreter(tfliteModel, options)

        inputTensor = interpreter.getInputTensor(0)
        inputShape = inputTensor.shape()  // [batch_size, height, width, channels]
        inputType = inputTensor.dataType()

        inputSize = inputShape[1] * inputShape[2] * inputShape[3]  // height * width * channels


        outputTensor = interpreter.getOutputTensor(0)
        outputShape = outputTensor.shape()  // [batch_size, height, width, channels]
        outputType = outputTensor.dataType()

        outputSize = outputShape[0] * outputShape[1] * outputShape[2]  // height * width * channels
        interpreter = Interpreter(tfliteModel, options)


        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()


    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun
                        onImageSaved(output: ImageCapture.OutputFileResults){
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    // Implements VideoCapture use case, including start and stop capturing.
    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        viewBinding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity,
                        Manifest.permission.RECORD_AUDIO) ==
                    PermissionChecker.PERMISSION_GRANTED)
                {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " +
                                    "${recordEvent.error}")
                        }
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()



            // Preview
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }



            imageCapture = ImageCapture.Builder().build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
//                .setTargetRotation(ROTATION_90)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(this)
            ) { image: ImageProxy -> analyzer(image) }

            val selector = QualitySelector
                .from(
                    Quality.UHD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                )

            val recorder = Recorder.Builder()
                .setQualitySelector(selector)
                .build()



            videoCapture = VideoCapture.withOutput(recorder)

            // Select back camera as a default
            @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
            val cameraSelector = CameraSelector.Builder()
                .addCameraFilter {
                    it.filter { camInfo ->
                        val level = Camera2CameraInfo.from(camInfo)
                            .getCameraCharacteristic(
                                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
                            )
                        level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
                    }
                }.requireLensFacing(LENS_FACING_BACK).build()


            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector,preview, imageAnalyzer)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }


    private fun nonMaxSuppression(boundingBoxes: MutableList<FloatArray>, overlapThresh: Float): MutableList<FloatArray> {

        val selectedBoxes = mutableListOf<FloatArray>()

        // Loop over all bounding boxes
        while (boundingBoxes.isNotEmpty()) {
            // Select the bounding box with the highest confidence score
            val maxBox = boundingBoxes.maxByOrNull { it[4] } ?: break
            selectedBoxes.add(maxBox)

            // Remove the selected box from the list
            boundingBoxes.remove(maxBox)

            // Calculate overlap with other bounding boxes
            val overlaps = mutableListOf<FloatArray>()
            for (box in boundingBoxes) {
                val overlap = calculateOverlap(maxBox, box)
                if (overlap > overlapThresh) {
                    overlaps.add(box)
                }
            }

            // Remove all bounding boxes that overlap with the selected box
            boundingBoxes.removeAll(overlaps)
        }

        return selectedBoxes
    }


    private fun calculateOverlap(boxA: FloatArray, boxB: FloatArray): Float {
        val xA = maxOf(boxA[0], boxB[0])
        val yA = maxOf(boxA[1], boxB[1])
        val xB = minOf(boxA[2], boxB[2])
        val yB = minOf(boxA[3], boxB[3])

        val interArea = maxOf(0f, xB - xA) * maxOf(0f, yB - yA)

        val boxAArea = (boxA[2] - boxA[0]) * (boxA[3] - boxA[1])
        val boxBArea = (boxB[2] - boxB[0]) * (boxB[3] - boxB[1])

        return interArea / (boxAArea + boxBArea - interArea)
    }



//    private fun analyzer(imageProxy: ImageProxy) {
//        Log.d("ANALYSIS","RUNNING")
        // Get YUV_420_888 format image from ImageProxy

//        val startTime = SystemClock.uptimeMillis()
//        val yBuffer = imageProxy.planes[0].buffer // Y
//        val uBuffer = imageProxy.planes[1].buffer // U
//        val vBuffer = imageProxy.planes[2].buffer // V
//        val ySize = yBuffer.remaining()
//        val uSize = uBuffer.remaining()
//        val vSize = vBuffer.remaining()
//        val nv21 = ByteArray(ySize + uSize + vSize)
//        yBuffer.get(nv21, 0, ySize)
//        vBuffer.get(nv21, ySize, vSize)
//        uBuffer.get(nv21, ySize + vSize, uSize)
//        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
//
//        // Convert YUV_420_888 format image to Bitmap
//        val out = ByteArrayOutputStream()
//        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
//        val bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
//        val matrix = Matrix()
//        matrix.postRotate(90f)
//        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
//        val scaledBitmap = Bitmap.createScaledBitmap(rotatedBitmap, inputSize, inputSize, false)
//        scaledBitmap.getPixels(
//            IntArray(inputSize * inputSize),
//            0,
//            inputSize,
//            0,
//            0,
//            inputSize,
//            inputSize
//        )
//        inputBuffer.rewind()
//        for (i in 0 until inputSize * inputSize) {
//            val pixelValue = scaledBitmap.getPixel(i % inputSize, i / inputSize)
//            inputBuffer.putFloat((pixelValue shr 16 and 0xFF) / 255.0f)
//            inputBuffer.putFloat((pixelValue shr 8 and 0xFF) / 255.0f)
//            inputBuffer.putFloat((pixelValue and 0xFF) / 255.0f)
//        }
//
//        val outputBuffer = Array(1) {
//            Array(outputSize) {
//                FloatArray(numElementsPerDetection)
//            }
//        }
//        this.interpreter.run(inputBuffer, outputBuffer)
////        if(null != nnApiDelegate) nnApiDelegate.close()
//
////        interpreter.close()
//
//        val boundingBoxes = mutableListOf<FloatArray>()
//        for (i in 0 until outputSize) {
//            val classProb = outputBuffer[0][i][4]
//            if (classProb >= 0.5) {
//                val xPos = outputBuffer[0][i][0] * viewBinding.viewFinder.width
//                val yPos = outputBuffer[0][i][1] * viewBinding.viewFinder.height
//                val width = outputBuffer[0][i][2] * viewBinding.viewFinder.width
//                val height = outputBuffer[0][i][3] * viewBinding.viewFinder.height
//                boundingBoxes.add(floatArrayOf(
//                    maxOf(0f, xPos - width / 2),
//                    maxOf(0f, yPos - height / 2),
//                    min(viewBinding.viewFinder.width.toFloat(), xPos + width / 2),
//                    min(viewBinding.viewFinder.height.toFloat(), yPos + height / 2),
//                    outputBuffer[0][i][4]
//                ))
//            }
//        }
//
//
//
//        finalBoundingBox = nonMaxSuppression(boundingBoxes,0.1f)
//        Log.d("Bounding box size", boundingBoxes.size.toString())
//        viewBinding.overlay.setRect(finalBoundingBox as MutableList<FloatArray>)
//        viewBinding.overlay.clearRect()
//        for (floatArray in boundingBoxes) {
//            Log.d("TAG", floatArray.joinToString())
//            viewBinding.overlay.setRect(RectF(floatArray[0], floatArray[1], floatArray[2],floatArray[3]))
//        }

//        val endTime = SystemClock.uptimeMillis()
//        val inferenceTime = endTime - startTime
//        Log.d("Analyze", "Inference time: $inferenceTime ms")
//        imageProxy.close()
        // Get YUV_420_888 format image from ImageProxy

//        val startTime = SystemClock.uptimeMillis()
//        val yBuffer = imageProxy.planes[0].buffer // Y
//        val uBuffer = imageProxy.planes[1].buffer // U
//        val vBuffer = imageProxy.planes[2].buffer // V
//        val ySize = yBuffer.remaining()
//        val uSize = uBuffer.remaining()
//        val vSize = vBuffer.remaining()
//        val nv21 = ByteArray(ySize + uSize + vSize)
//        yBuffer.get(nv21, 0, ySize)
//        vBuffer.get(nv21, ySize, vSize)
//        uBuffer.get(nv21, ySize + vSize, uSize)
//        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
//
//        // Convert YUV_420_888 format image to Bitmap
//        val out = ByteArrayOutputStream()
//        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
//        val bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
//
//        val matrix = Matrix()
//        matrix.postRotate(90f)
//        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
//        val rotatedAndScaledBitmap = Bitmap.createScaledBitmap(rotatedBitmap, 640, 640, false)
//
//        // Adjust the input image
////        val matrix = Matrix()
////        matrix.setRotate(90f)
////        val scaleX = 640f / bitmap.height.toFloat()
////        val scaleY = 640f / bitmap.width.toFloat()
////        matrix.postScale(scaleX,scaleY)
////        val rotatedAndScaledBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
//        val intValues = IntArray(inputShape[1] * inputShape[2])
//        rotatedAndScaledBitmap.getPixels(intValues, 0, rotatedAndScaledBitmap.width, 0, 0, rotatedAndScaledBitmap.width, rotatedAndScaledBitmap.height)
//
//        // Convert the input image to int8 format and write to the input buffer
//        val mean = 127.5f
//        val std = 127.5f
//        var index = 0
//        for (i in 0 until inputShape[1]) {
//            for (j in 0 until inputShape[2]) {
//                val pixel = intValues[index++]
//
//                // Normalize the pixel values to [-1, 1]
//                val blue = (pixel and 0xFF).toFloat()
//                val green = (pixel shr 8 and 0xFF).toFloat()
//                val red = (pixel shr 16 and 0xFF).toFloat()
//                inputBuffer.put(((blue - mean) / std).toInt().toByte())
//                inputBuffer.put(((green - mean) / std).toInt().toByte())
//                inputBuffer.put(((red - mean) / std).toInt().toByte())
//            }
//        }
//
//        // Set the position of the input buffer to 0
//        inputBuffer.rewind()
//
//        val outputTensor = interpreter.getOutputTensor(0)
//
//        val outputShape = outputTensor.shape()
//        val outputBuffer = ByteBuffer.allocateDirect(outputShape[0] * outputShape[1] * outputShape[2] * 4).apply { order(ByteOrder.nativeOrder()) }
//
//        outputBuffer.order(ByteOrder.nativeOrder())
//        interpreter.run(inputBuffer, outputBuffer)
//        val endTime = SystemClock.uptimeMillis()
//        val inferenceTime = endTime - startTime
//
//        Log.d("Analyze", "Inference time: $inferenceTime ms")
//        imageProxy.close()
//    }


    private fun analyzer (imageProxy: ImageProxy) {

        val startTime = SystemClock.uptimeMillis()
        val yBuffer = imageProxy.planes[0].buffer // Y
        val uBuffer = imageProxy.planes[1].buffer // U
        val vBuffer = imageProxy.planes[2].buffer // V
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)

        // Convert YUV_420_888 format image to Bitmap
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())

        val matrix = Matrix()
        matrix.postRotate(90f)
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val resizedBitmap = Bitmap.createScaledBitmap(rotatedBitmap, 416, 416, false)

//        START DETECTION
        val inputBuffer = ByteBuffer.allocateDirect(inputSize * inputType.byteSize()).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }

        // Normalize pixel values to [0, 255] range and convert to int8
        for (y in 0 until inputShape[1]) {
            for (x in 0 until inputShape[2]) {
                val pixel = resizedBitmap.getPixel(x, y)
                inputBuffer.put((pixel shr 16 and 0xFF).toByte())
                inputBuffer.put((pixel shr 8 and 0xFF).toByte())
                inputBuffer.put((pixel and 0xFF).toByte())
            }
        }
        inputBuffer.rewind()
        val outputBuffer = Array(1) {
            Array(outputShape[1]) {
                ByteArray(6)
            }
        }
        interpreter.run(inputBuffer,outputBuffer)
        val boundingBoxes = mutableListOf<FloatArray>()
//        Log.d("WIDTH", viewBinding.viewFinder.width.toString())
//        Log.d("HEIGHT", viewBinding.viewFinder.height.toString())
        for (i in 0 until outputShape[1]) {
            val classProb = (0.011072992347180843 * (outputBuffer[0][i][4] - 2))
            if (classProb >= 0.6) {
                val xPos = (0.011072992347180843 * (outputBuffer[0][i][0]  - 2)) * viewBinding.viewFinder.width

                val yPos = (0.011072992347180843 * (outputBuffer[0][i][1]  - 2)) * viewBinding.viewFinder.height
                val width = (0.011072992347180843 * (outputBuffer[0][i][2]  - 2)) * viewBinding.viewFinder.width
                val height = (0.011072992347180843 * (outputBuffer[0][i][3]  - 2)) * viewBinding.viewFinder.height
                boundingBoxes.add(floatArrayOf(
                    max(0f, (xPos - width / 2).toFloat()),
                    max(0f, (yPos - height / 2).toFloat()),
                    min(viewBinding.viewFinder.width.toFloat(), (xPos + width / 2).toFloat()),
                    min(viewBinding.viewFinder.height.toFloat(), (yPos + height / 2).toFloat()),
                    classProb.toFloat()
                ))
            }
        }


        val postProcessedBoxes = nonMaxSuppression(boundingBoxes, 0.1f)

        viewBinding.overlay.setRect(postProcessedBoxes)
//        viewBinding.overlay.clearRect()
//
//        for (box in postProcessedBoxes) {
//            Log.d("BOUNDING BOXES",Arrays.toString(box))
//        }

        val endTime = SystemClock.uptimeMillis()
        val inferenceTime = endTime - startTime
        Log.d("Analyze", "Inference time: $inferenceTime ms")

//        START DETECTION
        imageProxy.close()
    }


    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("person-416-int8.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }


    @SuppressLint("MissingSuperCall")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }



}
