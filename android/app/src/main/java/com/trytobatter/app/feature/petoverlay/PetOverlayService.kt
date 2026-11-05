package com.trytobatter.app.feature.petoverlay

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import kotlin.math.min
import kotlin.math.roundToInt

/** User-started foreground service, independent of alarms, tasks, Room and LAN sync. */
class PetOverlayService : Service() {
    private lateinit var windows: WindowManager
    private lateinit var config: PetOverlayConfig
    private val handler = Handler(Looper.getMainLooper())
    private var pet: PetOverlayView? = null
    private var speech: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private var limits = OverlayBounds(0, 0, 0, 0)
    private var petPixels = 0
    private var receiverRegistered = false
    private var foregroundStarted = false
    private var lastError: String? = null
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { hideSpeech(); updateAppearance() }
    }
    private val permissionCheck = object : Runnable {
        override fun run() {
            if (!Settings.canDrawOverlays(this@PetOverlayService)) fail("悬浮窗权限已关闭，请重新授权")
            else { updateVisibility(); handler.postDelayed(this, 5000) }
        }
    }
    override fun onCreate() { super.onCreate(); windows = getSystemService(WindowManager::class.java); config = PetOverlayConfig(this) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { fail("请先允许显示在其他应用上层"); return START_NOT_STICKY }
        if (intent?.action == UPDATE && pet == null) { stopSelf(); return START_NOT_STICKY }
        try {
            if (!foregroundStarted) {
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "大肥鱼桌宠", NotificationManager.IMPORTANCE_LOW))
                val settings = PendingIntent.getActivity(this, 0, Intent(this, PetOverlayActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val close = PendingIntent.getService(this, 1, Intent(this, PetOverlayService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("大肥鱼正在陪伴").setContentText("点击设置，或随时关闭桌宠")
                    .setContentIntent(settings).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .addAction(0, "桌宠设置", settings).addAction(0, "关闭桌宠", close).build()
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(NOTIFICATION, notification)
                foregroundStarted = true
            }
            if (pet == null) attachPet() else updateAppearance()
        } catch (e: RuntimeException) { android.util.Log.e("PetOverlay", "Cannot show overlay", e); fail("桌宠开启失败，请检查悬浮窗权限后重试") }
        return START_NOT_STICKY
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun unlocked() = getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    @Suppress("DEPRECATION")
    private fun bounds(): OverlayBounds {
        val width: Int; val height: Int; val left: Int; val top: Int; val right: Int; val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = metrics.windowInsets.getInsets(WindowInsets.Type.ime())
            width = metrics.bounds.width(); height = metrics.bounds.height()
            left = bars.left; top = bars.top; right = bars.right; bottom = maxOf(bars.bottom, ime.bottom)
        } else {
            val metrics = android.util.DisplayMetrics(); windows.defaultDisplay.getRealMetrics(metrics)
            val insets = pet?.rootWindowInsets
            width = metrics.widthPixels; height = metrics.heightPixels
            left = insets?.systemWindowInsetLeft ?: 0; top = insets?.systemWindowInsetTop ?: dp(24)
            right = insets?.systemWindowInsetRight ?: 0; bottom = insets?.systemWindowInsetBottom ?: dp(48)
        }
        val gap = dp(12)
        petPixels = min(dp(config.size), min(width - left - right - gap * 2, height - top - bottom - gap * 2).coerceAtLeast(1))
        return overlayBounds(width, height, left, top, right, bottom, petPixels, gap)
    }
    // Absolute screen coordinates deliberately retain physical left/right in all locales.
    @SuppressLint("RtlHardcoded")
    private fun windowParams(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; title = "Try to Better 大肥鱼" }
    private fun attachPet() {
        limits = bounds()
        params = windowParams(petPixels, petPixels)
        params.x = if (config.left) limits.left else limits.right
        params.y = limits.top + ((limits.bottom - limits.top) * config.vertical).roundToInt()
        val view = PetOverlayView(this, tap = ::toggleSpeech, drag = { dx, dy ->
            hideSpeech(); params.x = limits.x(params.x + dx.roundToInt()); params.y = limits.y(params.y + dy.roundToInt())
            movePet()
        }, drop = {
            config.left = params.x < (limits.left + limits.right) / 2
            params.x = if (config.left) limits.left else limits.right
            config.vertical = if (limits.bottom > limits.top) (params.y - limits.top).toFloat() / (limits.bottom - limits.top) else 0f
            movePet()
        }, failed = { fail("角色素材读取失败，请重新开启") })
        pet = view
        windows.addView(view, params)
        view.setOnApplyWindowInsetsListener { _, insets ->
            handler.post { if (pet != null && (bounds() != limits || params.width != petPixels)) updateAppearance() }
            insets
        }
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        updateAppearance()
        handler.post(permissionCheck)
        PetOverlayRuntime.set(true, "已悬浮，回到手机桌面即可看到")
    }
    private fun movePet() {
        val view = pet ?: return
        try { windows.updateViewLayout(view, params) } catch (_: IllegalArgumentException) { fail("桌宠窗口已结束，请重新开启") }
        catch (_: SecurityException) { fail("悬浮窗权限已关闭，请重新授权") }
    }
    private fun updatePosition() {
        if (pet == null) return
        limits = bounds(); params.width = petPixels; params.height = petPixels
        params.x = if (config.left) limits.left else limits.right
        params.y = limits.top + ((limits.bottom - limits.top) * config.vertical).roundToInt()
        movePet()
    }
    private fun updateAppearance() { hideSpeech(); updatePosition(); pet?.configure(config.animations, config.quiet, unlocked()) }
    private fun updateVisibility() {
        val view = pet ?: return
        if (view.isVisible != unlocked()) { hideSpeech(); view.configure(config.animations, config.quiet, unlocked()) }
    }
    private fun toggleSpeech() {
        if (speech != null) { hideSpeech(); return }
        if (!unlocked()) return
        val panel = object : LinearLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { hideSpeech(); return true }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
            elevation = dp(6).toFloat()
        }
        panel.addView(TextView(this).apply { text = if (config.quiet) "我安静陪着你。" else "我在呢，今天也一起加油。"; textSize = 14f; setTextColor(Color.rgb(29,43,68)); setPadding(dp(4), dp(6), dp(4), dp(8)) })
        fun action(label: String, click: () -> Unit) { panel.addView(Button(this).apply {
            text = label; textSize = 13f; isAllCaps = false; minHeight = dp(48); setTextColor(Color.rgb(35,84,211))
            background = GradientDrawable().apply { setColor(Color.rgb(235,240,254)); cornerRadius = dp(12).toFloat() }
            stateListAnimator = null
            setOnClickListener { hideSpeech(); click() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) }) }
        action("打开聊天") { openSettings(true) }
        action("桌宠设置") { openSettings(false) }
        action("关闭桌宠") { stopSelf() }
        val width = min(dp(252), limits.right + petPixels - limits.left)
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val popup = windowParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            x = (params.x + petPixels / 2 - width / 2).coerceIn(limits.left, maxOf(limits.left, limits.right + petPixels - width))
            val above = params.y - panel.measuredHeight - dp(8)
            val below = params.y + petPixels + dp(8)
            when {
                above >= limits.top -> y = above
                below + panel.measuredHeight <= limits.bottom + petPixels -> y = below
                params.x - width - dp(8) >= limits.left -> {
                    x = params.x - width - dp(8)
                    y = params.y.coerceIn(limits.top, maxOf(limits.top, limits.bottom + petPixels - panel.measuredHeight))
                }
                params.x + petPixels + dp(8) + width <= limits.right + petPixels -> {
                    x = params.x + petPixels + dp(8)
                    y = params.y.coerceIn(limits.top, maxOf(limits.top, limits.bottom + petPixels - panel.measuredHeight))
                }
                else -> y = above.coerceAtLeast(limits.top)
            }
        }
        try { speech = panel; windows.addView(panel, popup); handler.postDelayed({ if (speech === panel) hideSpeech() }, 12000) }
        catch (_: RuntimeException) { speech = null; fail("无法显示气泡，请重新检查悬浮窗权限") }
    }
    private fun openSettings(chat: Boolean) {
        try { startActivity(Intent(this, PetOverlayActivity::class.java).putExtra("chat", chat).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        catch (_: ActivityNotFoundException) { PetOverlayRuntime.set(true, "无法打开桌宠设置，请从App我的页进入") }
    }
    private fun hideSpeech() {
        speech?.let { if (it.isAttachedToWindow) runCatching { windows.removeViewImmediate(it) } }
        speech = null
    }
    private fun fail(message: String) { lastError = message; PetOverlayRuntime.set(false, message); stopSelf() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); updateAppearance() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); hideSpeech()
        pet?.let { if (it.isAttachedToWindow) windows.removeViewImmediate(it) }; pet = null
        if (receiverRegistered) unregisterReceiver(screen)
        stopForeground(STOP_FOREGROUND_REMOVE)
        PetOverlayRuntime.set(false, lastError ?: "已关闭")
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "pet_overlay_v1"
        private const val NOTIFICATION = 8701
        private const val STOP = "com.trytobatter.pet.STOP"
        private const val UPDATE = "com.trytobatter.pet.UPDATE"
        fun update(context: Context) { if (PetOverlayRuntime.status.value.running) context.startService(Intent(context, PetOverlayService::class.java).setAction(UPDATE)) }
    }
}
