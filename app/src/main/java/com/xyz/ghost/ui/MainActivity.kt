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
            startOverlayService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // proceed regardless of result
        requestOverlayPermissionIfNeeded()
    }

    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_STOPPED) {
                updateUI(active = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggle.setOnClickListener {
            if (OverlayService.isRunning) {
                stopOverlayService()
            } else {
                checkNotificationPermissionThenOverlay()
            }
        }

        binding.btnAdSkip.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        updateUI(active = OverlayService.isRunning)
        updateAdSkipUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI(active = OverlayService.isRunning)
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
            startOverlayService()
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUI(active = true)
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        updateUI(active = false)
    }

    private fun isAdSkipAuthorized(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == AdSkipService::class.java.name }
    }

    private fun updateUI(active: Boolean) {
        if (active) {
            binding.btnToggle.text = getString(R.string.stop_protection)
            binding.statusDot.setBackgroundResource(R.drawable.bg_status_active)
            binding.statusText.text = getString(R.string.status_active)
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            binding.hintText.text = getString(R.string.hint_active)
        } else {
            binding.btnToggle.text = getString(R.string.start_protection)
            binding.statusDot.setBackgroundResource(R.drawable.bg_status_inactive)
            binding.statusText.text = getString(R.string.status_inactive)
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
            binding.hintText.text = getString(R.string.hint_inactive)
        }
    }

    private fun updateAdSkipUI() {
        val authorized = isAdSkipAuthorized()
        val prefEnabled = AdSkipService.isAdSkipEnabled(this)
        when {
            !authorized -> {
                binding.adSkipDot.setBackgroundResource(R.drawable.bg_status_inactive)
                binding.adSkipStatusText.text = getString(R.string.ad_skip_status_off)
                binding.adSkipStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
                binding.btnAdSkip.text = getString(R.string.ad_skip_btn_enable)
            }
            prefEnabled -> {
                binding.adSkipDot.setBackgroundResource(R.drawable.bg_status_active)
                binding.adSkipStatusText.text = getString(R.string.ad_skip_status_on)
                binding.adSkipStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_active))
                binding.btnAdSkip.text = getString(R.string.ad_skip_btn_enabled)
            }
            else -> {
                binding.adSkipDot.setBackgroundResource(R.drawable.bg_status_inactive)
                binding.adSkipStatusText.text = getString(R.string.ad_skip_status_paused)
                binding.adSkipStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
                binding.btnAdSkip.text = getString(R.string.ad_skip_btn_enabled)
            }
        }
    }
}