package com.example.cameraxapp

data class DetectionResult(
    val label: String,
    val confidence: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)