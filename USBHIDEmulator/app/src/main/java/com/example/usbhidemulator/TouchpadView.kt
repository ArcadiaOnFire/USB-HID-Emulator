package com.example.usbhidemulator

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View

class TouchpadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var lastX = 0f
    private var lastY = 0f
    private var isTouching = false

    companion object {
        init {
            System.loadLibrary("hid_writer")
        }
    }

    external fun sendMouseMove(dx: Int, dy: Int)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                isTouching = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTouching) return false
                val dx = (event.x - lastX).toInt()
                val dy = (event.y - lastY).toInt()
                if (dx != 0 || dy != 0) {
                    Log.d("TouchpadView", "Move dx=$dx dy=$dy")
                    sendMouseMove(dx, dy)
                    lastX = event.x
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Optional: Add visual feedback for touchpad here
    }
}
