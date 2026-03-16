package com.xyz.ghost.util

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

internal const val LOG_TAG = "ghost"

fun Context.dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

fun WindowManager.screenWidth(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        currentWindowMetrics.bounds.width()
    else {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") defaultDisplay.getMetrics(dm)
        dm.widthPixels
    }

fun WindowManager.screenHeight(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        currentWindowMetrics.bounds.height()
    else {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") defaultDisplay.getMetrics(dm)
        dm.heightPixels
    }