package com.example.cameraxapp

import android.graphics.RectF

data class DetectionResult(
    val label: RectF,
    val confidence: Float,
    val rect: Float
)
