package com.example.cameraxapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


private const val INPUT_IMAGE_SIZE = 640
private const val MAX_DETECTION_RESULTS = 10
private val IMAGE_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val IMAGE_STD = floatArrayOf(0.229f, 0.224f, 0.225f)



class ObjectDetector(context: Context, private val modelPath: String) {

    private var interpreter: Interpreter


    init {
        interpreter = Interpreter(loadModelFile(context))
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        Log.i("LOAD MODEL FILE", modelPath)
        val fileDescriptor = context.assets.openFd(modelPath)
        Log.e("File Descriptor", fileDescriptor.toString() )
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun detectObjects(bitmap: Bitmap): List<DetectedObject> {
        val inputArray = arrayOf(bitmap.toNormalizedFloatArray(INPUT_IMAGE_SIZE))
        val outputMap = mutableMapOf<Int, Any>()
        val outputLocations = Array(1) { Array(MAX_DETECTION_RESULTS) { FloatArray(4) } }
        val outputClasses = Array(1) { FloatArray(MAX_DETECTION_RESULTS) }
        val outputScores = Array(1) { FloatArray(MAX_DETECTION_RESULTS) }
        val numDetections = FloatArray(1)
        outputMap[0] = outputLocations
        outputMap[1] = outputClasses
        outputMap[2] = outputScores
        outputMap[3] = numDetections
        interpreter.runForMultipleInputsOutputs(inputArray, outputMap)
        val detectionObjects = mutableListOf<DetectedObject>()
        val numDetection = numDetections[0].toInt()
        for (i in 0 until numDetection) {
            val classId = outputClasses[0][i].toInt()
            val score = outputScores[0][i]
            val detection = RectF(
                outputLocations[0][i][1] * bitmap.width,
                outputLocations[0][i][0] * bitmap.height,
                outputLocations[0][i][3] * bitmap.width,
                outputLocations[0][i][2] * bitmap.height
            )
            detectionObjects.add(DetectedObject(classId, score, detection))
        }
        return detectionObjects
    }

    private fun Bitmap.toNormalizedFloatArray(inputSize: Int): FloatArray {
        // Create a new bitmap with the specified size and normalize the pixel values
        val inputBitmap = Bitmap.createScaledBitmap(this, inputSize, inputSize, false)
        val inputArray = FloatArray(inputSize * inputSize * 3)
        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val pixelValue = inputBitmap.getPixel(j, i)
                // Normalize the pixel values to the range of [0, 1]
                inputArray[pixel++] = ((pixelValue shr 16 and 0xFF) - IMAGE_MEAN[0]) / IMAGE_STD[0]
                inputArray[pixel++] = ((pixelValue shr 8 and 0xFF) - IMAGE_MEAN[1]) / IMAGE_STD[1]
                inputArray[pixel++] = ((pixelValue and 0xFF) - IMAGE_MEAN[2]) / IMAGE_STD[2]
            }
        }
        return inputArray
    }
}