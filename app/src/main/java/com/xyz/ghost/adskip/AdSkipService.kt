package com.xyz.ghost.adskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AdSkipService : AccessibilityService() {

    companion object {
        private const val TAG = "ghost"
        private const val PREFS_NAME = "ghost_prefs"
        private const val KEY_AD_SKIP_ENABLED = "ad_skip_enabled"

        @Volatile
        var isRunning = false
            private set

        fun isAdSkipEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AD_SKIP_ENABLED, false)

        fun setAdSkipEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AD_SKIP_ENABLED, enabled).apply()
            Log.i(TAG, "adSkipEnabled = $enabled")
        }

        // YouTube 跳过广告按钮的已知文本（多语言 + 多版本）
        private val SKIP_TEXTS = setOf(
            "Skip Ad", "Skip ad", "SKIP AD",
            "Skip Ads", "Skip ads", "SKIP ADS",
            "跳过广告", "跳过", "略过广告",
            "Passer l'annonce", "Überspringen",
            "Saltar anuncio", "Pular anúncio"
        )

        // YouTube 跳过按钮的已知 viewId（随 YouTube 版本可能变化）
        private val SKIP_IDS = setOf(
            "com.google.android.youtube:id/skip_ad_button",
            "com.google.android.youtube:id/skip_button"
        )
    }

    // 节流：避免同一广告重复点击
    private var lastClickMs = 0L
    private val CLICK_COOLDOWN_MS = 2000L

    override fun onServiceConnected() {
        isRunning = true
        serviceInfo = serviceInfo.apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        Log.i(TAG, "AdSkipService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        val type = AccessibilityEvent.eventTypeToString(event.eventType)

        // ① 记录所有收到的事件（排除非 YouTube 前先打印，便于确认事件在流动）
        if (pkg != "com.google.android.youtube") {
            Log.v(TAG, "event from other pkg=$pkg type=$type [skip]")
            return
        }

        if (!isAdSkipEnabled(this)) {
            Log.d(TAG, "YouTube event received but ad-skip disabled, type=$type")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs < CLICK_COOLDOWN_MS) {
            Log.v(TAG, "cooldown active, skip event type=$type")
            return
        }

        // ② 预检：记录事件文本内容
        val needFullScan = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "WINDOW_STATE_CHANGED → full scan")
                true
            }
            else -> {
                val srcText = event.source?.text?.toString() ?: ""
                val descText = event.contentDescription?.toString() ?: ""
                val text = srcText.ifEmpty { descText }
                val hit = SKIP_TEXTS.any { text.contains(it, ignoreCase = true) }
                Log.d(TAG, "event type=$type text=\"$text\" hit=$hit")
                hit
            }
        }
        if (!needFullScan) return

        // ③ 获取根节点
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "rootInActiveWindow is null")
            return
        }
        try {
            val found = trySkip(root)
            if (found) {
                lastClickMs = now
                Log.i(TAG, "✅ skip ad clicked")
            } else {
                Log.w(TAG, "❌ no skip button found in window")
            }
        } finally {
            root.recycle()
        }
    }

    private fun trySkip(root: AccessibilityNodeInfo): Boolean {
        // 优先按 viewId 查找（更精准）
        for (id in SKIP_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isEmpty()) {
                Log.d(TAG, "id search: no nodes for id=$id")
            }
            for (node in nodes) {
                Log.d(TAG, "id match: id=$id text=\"${node.text}\" visible=${node.isVisibleToUser} clickable=${node.isClickable} class=${node.className}")
                if (clickNode(node)) return true
            }
        }
        // 按文本查找（兜底）
        for (text in SKIP_TEXTS) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                Log.d(TAG, "text match: \"$text\" visible=${node.isVisibleToUser} clickable=${node.isClickable} id=${node.viewIdResourceName} class=${node.className}")
                if (clickNode(node)) return true
            }
        }
        // ④ 都没找到时，dump 根节点第一层子节点帮助定位
        Log.d(TAG, "root childCount=${root.childCount} pkg=${root.packageName} class=${root.className}")
        for (i in 0 until minOf(root.childCount, 5)) {
            val child = root.getChild(i) ?: continue
            Log.d(TAG, "  root.child[$i] class=${child.className} id=${child.viewIdResourceName} text=\"${child.text}\" desc=\"${child.contentDescription}\"")
            child.recycle()
        }
        return false
    }

    /**
     * 点击节点：优先点击自身，若不可点击则向上找可点击的父节点（最多 3 层）
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) {
            Log.d(TAG, "clickNode: not visible, skip")
            return false
        }
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        repeat(3) {
            target?.let {
                Log.d(TAG, "clickNode depth=$depth class=${it.className} clickable=${it.isClickable}")
                if (it.isClickable) {
                    val ok = it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "performAction(ACTION_CLICK) result=$ok depth=$depth")
                    return ok
                }
                target = it.parent
                depth++
            }
        }
        Log.w(TAG, "clickNode: no clickable ancestor found within 3 levels")
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.i(TAG, "AdSkipService destroyed")
    }
}