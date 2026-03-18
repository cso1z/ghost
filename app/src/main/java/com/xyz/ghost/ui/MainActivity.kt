package com.xyz.ghost.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xyz.ghost.R
import com.xyz.ghost.adskip.AdSkipService
import com.xyz.ghost.databinding.ActivityMainBinding
import com.xyz.ghost.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startOrStopService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestOverlayPermissionIfNeeded()
    }

    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_STOPPED) {
                updateBallUI()
                updateAdSkipUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.switchBall.setOnCheckedChangeListener { _, isChecked ->
            OverlayService.setBallEnabled(this, isChecked)
            if (OverlayService.isRunning) {
                val action = if (isChecked) OverlayService.ACTION_SHOW_BALL else OverlayService.ACTION_HIDE_BALL
                startService(Intent(this, OverlayService::class.java).setAction(action))
            }
            startOrStopService()
            updateBallUI()
        }

        binding.btnAdSkip.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.switchAdSkip.setOnCheckedChangeListener { _, isChecked ->
            AdSkipService.setAdSkipEnabled(this, isChecked)
            startOrStopService()
            updateAdSkipUI()
        }

        updateBallUI()
        updateAdSkipUI()
        startOrStopService()
    }

    override fun onResume() {
        super.onResume()
        updateBallUI()
        updateAdSkipUI()
        val filter = IntentFilter(OverlayService.ACTION_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStoppedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceStoppedReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(serviceStoppedReceiver)
    }

    // ── 服务启停 ──────────────────────────────────────────────────────

    private fun startOrStopService() {
        val ballOn = OverlayService.isBallEnabled(this)
        val adSkipOn = isAdSkipAuthorized() && AdSkipService.isAdSkipEnabled(this)
        if (ballOn || adSkipOn) {
            if (!OverlayService.isRunning) checkNotificationPermissionThenOverlay()
        } else {
            if (OverlayService.isRunning) stopService(Intent(this, OverlayService::class.java))
        }
    }

    private fun checkNotificationPermissionThenOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestOverlayPermissionIfNeeded()
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } else {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    // ── UI 更新 ───────────────────────────────────────────────────────

    private fun isAdSkipAuthorized(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == AdSkipService::class.java.name }
    }

    private fun updateBallUI() {
        val enabled = OverlayService.isBallEnabled(this)
        binding.switchBall.setOnCheckedChangeListener(null)
        binding.switchBall.isChecked = enabled
        binding.switchBall.setOnCheckedChangeListener { _, isChecked ->
            OverlayService.setBallEnabled(this, isChecked)
            if (OverlayService.isRunning) {
                val action = if (isChecked) OverlayService.ACTION_SHOW_BALL else OverlayService.ACTION_HIDE_BALL
                startService(Intent(this, OverlayService::class.java).setAction(action))
            }
            startOrStopService()
            updateBallUI()
        }
        if (enabled) {
            binding.statusDot.setBackgroundResource(R.drawable.bg_status_active)
            binding.statusText.text = getString(R.string.ball_status_on)
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            binding.statusDot.setBackgroundResource(R.drawable.bg_status_inactive)
            binding.statusText.text = getString(R.string.ball_status_off)
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
        }
    }

    private fun updateAdSkipUI() {
        val authorized = isAdSkipAuthorized()
        val prefEnabled = AdSkipService.isAdSkipEnabled(this)

        if (!authorized) {
            binding.btnAdSkip.visibility = View.VISIBLE
            binding.switchAdSkip.visibility = View.GONE
            binding.adSkipDot.setBackgroundResource(R.drawable.bg_status_inactive)
            binding.adSkipStatusText.text = getString(R.string.ad_skip_status_off)
            binding.adSkipStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
        } else {
            binding.btnAdSkip.visibility = View.GONE
            binding.switchAdSkip.visibility = View.VISIBLE
            binding.switchAdSkip.setOnCheckedChangeListener(null)
            binding.switchAdSkip.isChecked = prefEnabled
            binding.switchAdSkip.setOnCheckedChangeListener { _, isChecked ->
                AdSkipService.setAdSkipEnabled(this, isChecked)
                startOrStopService()
                updateAdSkipUI()
            }
            if (prefEnabled) {
                binding.adSkipDot.setBackgroundResource(R.drawable.bg_status_active)
                binding.adSkipStatusText.text = getString(R.string.ad_skip_status_on)
                binding.adSkipStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            } else {
                binding.adSkipDot.setBackgroundResource(R.drawable.bg_status_inactive)
                binding.adSkipStatusText.text = getString(R.string.ad_skip_status_paused)
                binding.adSkipStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
            }
        }
    }
}