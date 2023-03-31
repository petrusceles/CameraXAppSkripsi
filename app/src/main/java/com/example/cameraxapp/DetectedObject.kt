package com.example.cameraxapp

import android.graphics.RectF

data class DetectedObject(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
)
