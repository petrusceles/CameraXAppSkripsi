package com.example.cameraxapp

import android.graphics.RectF

data class DetectedObject(
    val classId: Int,
    val score: Float,
    val detection: RectF
)
