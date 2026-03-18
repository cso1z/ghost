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
        if (!isAdSkipEnabled(this)) return
        if (event.packageName != "com.google.android.youtube") return
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs < CLICK_COOLDOWN_MS) return

        // 快速预检：仅当触发节点文本命中跳过关键词，或界面发生整体切换时，才获取完整节点树
        val needFullScan = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
            else -> {
                val text = event.source?.text?.toString()
                    ?: event.contentDescription?.toString()
                    ?: ""
                val hit = SKIP_TEXTS.any { text.contains(it, ignoreCase = true) }
                if (hit) Log.d(TAG, "pre-check hit: \"$text\"")
                hit
            }
        }
        if (!needFullScan) return

        val root = rootInActiveWindow ?: return
        try {
            if (trySkip(root)) {
                lastClickMs = now
                Log.i(TAG, "skip ad clicked")
            }
        } finally {
            root.recycle()
        }
    }

    private fun trySkip(root: AccessibilityNodeInfo): Boolean {
        // 优先按 viewId 查找（更精准）
        for (id in SKIP_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                Log.d(TAG, "found skip node by id: $id visible=${node.isVisibleToUser} clickable=${node.isClickable}")
                if (clickNode(node)) return true
            }
        }
        // 按文本查找（兜底）
        for (text in SKIP_TEXTS) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                Log.d(TAG, "found skip node by text: \"$text\" visible=${node.isVisibleToUser} clickable=${node.isClickable}")
                if (clickNode(node)) return true
            }
        }
        return false
    }

    /**
     * 点击节点：优先点击自身，若不可点击则向上找可点击的父节点（最多 3 层）
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        var target: AccessibilityNodeInfo? = node
        repeat(3) {
            target?.let {
                if (it.isClickable) {
                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                target = it.parent
            }
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.i(TAG, "AdSkipService destroyed")
    }
}