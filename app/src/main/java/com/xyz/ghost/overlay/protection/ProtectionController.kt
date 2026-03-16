package com.xyz.ghost.overlay.protection

import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.xyz.ghost.util.LOG_TAG

/**
 * 职责：管理全屏透明拦截层（overlay）的 WindowManager 生命周期。
 * 通过"移除球 → 加遮罩 → 重加球"确保球始终在最顶层 Z-order。
 */
class ProtectionController(
    private val windowManager: WindowManager,
    private val overlayType: Int
) {
    var isActive = false
        private set

    private var overlayView: View? = null

    fun activate(ballView: View, ballParams: WindowManager.LayoutParams) {
        if (isActive) return
        isActive = true
        Log.i(LOG_TAG, "ProtectionController.activate")

        try { windowManager.removeView(ballView) } catch (_: Exception) {}

        val overlay = View(ballView.context).apply { isClickable = true }
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowManager.addView(overlay, overlayParams)
        overlayView = overlay
        try { windowManager.addView(ballView, ballParams) } catch (_: Exception) {}
    }

    fun deactivate() {
        if (!isActive) return
        isActive = false
        Log.i(LOG_TAG, "ProtectionController.deactivate")
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }
}