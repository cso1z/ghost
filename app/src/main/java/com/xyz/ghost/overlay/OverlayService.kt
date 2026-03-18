package com.xyz.ghost.overlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import com.xyz.ghost.adskip.AdSkipService
import com.xyz.ghost.overlay.ball.BallController
import com.xyz.ghost.overlay.ball.BallPositionCalc
import com.xyz.ghost.overlay.protection.ProtectionController
import com.xyz.ghost.util.LOG_TAG
import com.xyz.ghost.util.NotificationHelper
import com.xyz.ghost.util.dpToPx
import com.xyz.ghost.util.screenHeight
import com.xyz.ghost.util.screenWidth

/**
 * 职责：Android Service 生命周期管理 + 组件装配（DI 根）。
 * 不含任何 UI 或业务逻辑，全部委托给 BallController / ProtectionController。
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOPPED = "com.xyz.ghost.ACTION_STOPPED"
        const val ACTION_STOP = "com.xyz.ghost.ACTION_STOP"
        const val ACTION_SHOW_BALL = "com.xyz.ghost.ACTION_SHOW_BALL"
        const val ACTION_HIDE_BALL = "com.xyz.ghost.ACTION_HIDE_BALL"

        private const val PREFS_NAME = "ghost_prefs"
        private const val KEY_BALL_ENABLED = "ball_enabled"

        @Volatile
        var isRunning = false
            private set

        fun isBallEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BALL_ENABLED, false)

        fun setBallEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BALL_ENABLED, enabled).apply()
        }
    }

    private val overlayType
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private lateinit var ballController: BallController
    private lateinit var protectionController: ProtectionController
    private lateinit var notificationHelper: NotificationHelper

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(LOG_TAG, "OverlayService.onCreate")

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val handler = Handler(Looper.getMainLooper())

        val pos = BallPositionCalc(
            ballSizePx = dpToPx(56),
            overflowPx = dpToPx(20),
            marginPx   = dpToPx(12),
            screenWidth  = { wm.screenWidth() },
            screenHeight = { wm.screenHeight() }
        )

        notificationHelper = NotificationHelper(this)
        notificationHelper.createChannel()
        startForeground(NotificationHelper.NOTIFICATION_ID, notificationHelper.build())

        protectionController = ProtectionController(wm, overlayType)

        ballController = BallController(
            context     = this,
            windowManager = wm,
            overlayType = overlayType,
            handler     = handler,
            pos         = pos,
            protection  = protectionController,
            isAdSkipAuthorized = { isAdSkipAuthorized() },
            isAdSkipEnabled    = { AdSkipService.isAdSkipEnabled(this) },
            onAdSkipToggle     = {
                if (isAdSkipAuthorized()) {
                    AdSkipService.setAdSkipEnabled(this, !AdSkipService.isAdSkipEnabled(this))
                }
            }
        )

        if (isBallEnabled(this)) ballController.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                setBallEnabled(this, false)
                AdSkipService.setAdSkipEnabled(this, false)
                stopSelf()
            }
            ACTION_SHOW_BALL -> if (ballController.ballView == null) ballController.show()
            ACTION_HIDE_BALL -> if (ballController.ballView != null) ballController.destroy()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.i(LOG_TAG, "OverlayService.onDestroy")
        ballController.destroy()
        protectionController.deactivate()
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isAdSkipAuthorized(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == AdSkipService::class.java.name
            }
    }
}