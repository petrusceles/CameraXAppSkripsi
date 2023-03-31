import android.graphics.*
import android.media.Image
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.cameraxapp.DetectedObject
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.lang.Float.max
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class YOLOv5Detector(private val interpreter: Interpreter) : ImageAnalysis.Analyzer {

    // Constants for YOLOv5 model
    private val inputSize = 320 // Input size of the model
    private val outputSize = 6300 // Output size of the model
    private val numClasses = 1 // Number of classes in the model
    private val blockSize = 52 // Block size of the model
    private val numAnchors = 3 // Number of anchors in the model
    private val numCoords = 4 // Number of coordinates in the model
    private val numElementsPerDetection = numClasses + numCoords + 1 // Number of elements per detection
    private val imageWidth = 320
    private val imageHeight = 320
    private val labelList = listOf("person")
//    private lateinit var boundingBox :RectF

    // ByteBuffer to hold preprocessed image
    private val inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    override fun analyze(imageProxy: ImageProxy) {
//        Log.d("ANALYSIS","RUNNING")
        // Get YUV_420_888 format image from ImageProxy

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

        // Preprocess image and fill the input ByteBuffer
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        scaledBitmap.getPixels(
            IntArray(inputSize * inputSize),
            0,
            inputSize,
            0,
            0,
            inputSize,
            inputSize
        )
        inputBuffer.rewind()
        for (i in 0 until inputSize * inputSize) {
            val pixelValue = scaledBitmap.getPixel(i % inputSize, i / inputSize)
            inputBuffer.putFloat((pixelValue shr 16 and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixelValue shr 8 and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixelValue and 0xFF) / 255.0f)
        }

        // Run inference on the input ByteBuffer
        val outputBuffer = Array(1) {
            Array(outputSize) {
                FloatArray(numElementsPerDetection)
            }
        }
        interpreter.run(inputBuffer, outputBuffer)



        val outputList = mutableListOf<DetectedObject>()
        for (i in 0 until outputSize) {
            val classProb = outputBuffer[0][i][4]
            if (classProb >= 0.5) {
                val xPos = outputBuffer[0][i][0] * imageWidth
                val yPos = outputBuffer[0][i][1] * imageHeight
                val width = outputBuffer[0][i][2] * imageWidth
                val height = outputBuffer[0][i][3] * imageHeight

                // Create a Detection object for the prediction
                val detection = DetectedObject(
                    label = labelList[0],
                    confidence = classProb,
                    boundingBox = RectF(
                        max(0f, xPos - width / 2),
                        max(0f, yPos - height / 2),
                        min(imageWidth.toFloat(), xPos + width / 2),
                        min(imageHeight.toFloat(), yPos + height / 2)
                    )
                )
                outputList.add(detection)
            }
        }

        Log.i("OUTPUT", outputList.toString())
        val endTime = SystemClock.uptimeMillis()
        val inferenceTime = endTime - startTime
        Log.d("Analyze", "Inference time: $inferenceTime ms")

        imageProxy.close()
//        return outputList
    }
}