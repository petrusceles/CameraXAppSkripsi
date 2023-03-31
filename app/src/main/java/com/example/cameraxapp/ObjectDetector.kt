package com.example.cameraxapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*


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
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

//    fun detectObjects(bitmap: Bitmap): List<DetectedObject> {
//        val inputTensorShape = interpreter.getInputTensor(0).shape()
//        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputTensorShape[2], inputTensorShape[1], true)
//        val inputArray = arrayOf(resizedBitmap.toNormalizedFloatArray(inputTensorShape[1], inputTensorShape[2]))
//        val outputMap = mutableMapOf<Int, Any>()
//        val outputLocations = Array(1) { Array(MAX_DETECTION_RESULTS) { FloatArray(4) } }
//        val outputClasses = Array(1) { FloatArray(MAX_DETECTION_RESULTS) }
//        val outputScores = Array(1) { FloatArray(MAX_DETECTION_RESULTS) }
//        val numDetections = FloatArray(1)
//        outputMap[0] = outputLocations
//        outputMap[1] = outputClasses
//        outputMap[2] = outputScores
//        outputMap[3] = numDetections
////        Log.d("GET INPUT TENSOR", interpreter.getInputTensor(0).shape().toString())
//
//        interpreter.runForMultipleInputsOutputs(inputArray, outputMap)
//        val detectionObjects = mutableListOf<DetectedObject>()
//        val numDetection = numDetections[0].toInt()
//        for (i in 0 until numDetection) {
//            val classId = outputClasses[0][i].toInt()
//            val score = outputScores[0][i]
//            val detection = RectF(
//                outputLocations[0][i][1] * bitmap.width,
//                outputLocations[0][i][0] * bitmap.height,
//                outputLocations[0][i][3] * bitmap.width,
//                outputLocations[0][i][2] * bitmap.height
//            )
//            detectionObjects.add(DetectedObject(classId, score, detection))
//        }
//        return detectionObjects
//    }

    fun Bitmap.toNormalizedFloatArray(inputHeight: Int, inputWidth: Int): FloatArray {
        // Create a new float array to hold the normalized pixel values
        val floatValues = FloatArray(inputHeight * inputWidth * 3)

        // Get the dimensions of the bitmap
        val height = this.height
        val width = this.width

        // Get the scaling factors for the width and height
        val widthScale = inputWidth.toFloat() / width
        val heightScale = inputHeight.toFloat() / height

        // Convert the bitmap to a 32-bit ARGB format
        val pixels = IntArray(width * height)
        this.getPixels(pixels, 0, width, 0, 0, width, height)

        // Normalize the pixel values and add them to the float array
        var index = 0
        for (y in 0 until inputHeight) {
            val scaledY = (y / heightScale).toInt()
            for (x in 0 until inputWidth) {
                val scaledX = (x / widthScale).toInt()
                val pixel = pixels[scaledY * width + scaledX]

                floatValues[index++] = ((pixel shr 16 and 0xFF) - IMAGE_MEAN[0]) / IMAGE_STD[0]
                floatValues[index++] = ((pixel shr 8 and 0xFF) - IMAGE_MEAN[1]) / IMAGE_STD[1]
                floatValues[index++] = ((pixel and 0xFF) - IMAGE_MEAN[2]) / IMAGE_STD[2]
            }
        }

        return floatValues
    }
}