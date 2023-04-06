package com.example.cameraxapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class GraphOverlay(context: Context, attrs: AttributeSet) : View(context, attrs) {

//    private val paint = Paint().apply {
//        color = Color.GREEN
//        style = Paint.Style.STROKE
//        strokeWidth = 4f
//    }

    private val paint = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(250f, BlurMaskFilter.Blur.NORMAL)
    }

    private var rect: RectF? = null

    fun setRect(rect: RectF) {
        this.rect = rect
        invalidate()
    }

    fun clearRect() {
        rect = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)

        rect?.let {
            canvas?.drawRect(it, paint)
        }
    }
}