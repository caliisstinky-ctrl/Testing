package com.example.minilauncher

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.WindowManager

/**
 * Notification listener that also owns the island overlay.
 * Android keeps it alive while notification access is granted.
 */
class IslandService : NotificationListenerService() {

    companion object {
        @Volatile var instance: IslandService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var island: IslandView? = null
    private var sessions: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()

    private var alert: Live? = null
    private val sticky = LinkedHashMap<String, Live>()
    private var quickUntil = 0L

    private var lastController: MediaController? = null
    private var holdUntil = 0L

    private var cam: CameraManager? = null
    private var torchId: String? = null
    private var torchOn = false

    private var batteryPct = 0
    private var batteryInit = false
    private var lowShown = false
    private var fullShown = false
    private var receiverRegistered = false

    private val clearAlert = Runnable { alert = null; refresh() }
    private val delayedRefresh = Runnable { refresh() }
    private val quickExpire = Runnable {
        quickUntil = 0L
        island?.setExpanded(false)
        refresh()
    }

    private val mediaCb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        setControllers(list ?: emptyList())
    }

    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) {
                torchOn = enabled
                refresh()
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    fullShown = false
                    if (feature(IslandPrefs.F_CHARGE)) showCharge("Charging")
                }
                Intent.ACTION_BATTERY_CHANGED -> onBattery(i)
                else -> {}
            }
        }
    }

    private fun feature(k: String) = IslandPrefs.bool(this, k, true)

    // ------------------------------------------------------------ lifecycle

    override fun onListenerConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        sessions = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        setupTorch()
        registerPower()
        val me = ComponentName(this, IslandService::class.java)
        try {
            sessions?.addOnActiveSessionsChangedListener(sessionsListener, me, main)
            setControllers(sessions?.getActiveSessions(me) ?: emptyList())
        } catch (_: SecurityException) {
        }
        try {
            activeNotifications?.forEach { handle(it, true) }
        } catch (_: Exception) {
        }
        refresh()
    }

    override fun onListenerDisconnected() = teardown()

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        main.removeCallbacksAndMessages(null)
        controllers.forEach { try { it.unregisterCallback(mediaCb) } catch (_: Exception) {} }
        controllers = emptyList()
        try { sessions?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        try { cam?.unregisterTorchCallback(torchCb) } catch (_: Exception) {}
        if (receiverRegistered) {
            try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        hide()
        if (instance === this) instance = null
    }

    /** Called by settings after a preference changes. */
    fun reload() {
        hide()
        refresh()
    }

    // ------------------------------------------------------------ torch / power

    private fun setupTorch() {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        cam = cm
        torchId = try {
            cm.cameraIdList.firstOrNull { id ->
                val ch = cm.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (_: Exception) {
            null
        }
        try { cm.registerTorchCallback(torchCb, main) } catch (_: Exception) {}
    }

    private fun toggleTorch() {
        val id = torchId ?: return
        try { cam?.setTorchMode(id, !torchOn) } catch (_: Exception) {}
    }

    private fun registerPower() {
        if (receiverRegistered) return
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerReceiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(powerReceiver, f)
        }
        receiverRegistered = true
    }

    private fun onBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) batteryPct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        if (!batteryInit) {
            batteryInit = true
            lowShown = batteryPct <= 20
            fullShown = batteryPct >= 100
            return
        }
        if (charging && batteryPct >= 100 && !fullShown) {
            fullShown = true
            if (feature(IslandPrefs.F_CHARGE)) showCharge("Fully charged")
        }
        if (!charging && batteryPct <= 20 && !lowShown) {
            lowShown = true
            if (feature(IslandPrefs.F_CHARGE)) showCharge("Low battery")
        }
        if (batteryPct > 25) lowShown = false
        if (batteryPct < 100) fullShown = false
    }

    private fun chargeIcon(): Drawable? = try {
        getDrawable(android.R.drawable.ic_lock_idle_charging)?.mutate()?.also {
            it.setTint(IslandPrefs.accent(this))
        }
    } catch (_: Exception) {
        null
    }

    private fun showCharge(title: String) {
        val sub = when (title) {
            "Low battery" -> "Plug in soon"
            "Fully charged" -> "You can unplug now"
            else -> "Plugged in"
        }
        postAlert(
            Live(
                "alert", LiveKind.CHARGE, title, sub, chargeIcon(),
                progress = batteryPct, big = "$batteryPct%"
            ),
            4000L
        )
    }

    // ------------------------------------------------------------ media

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { try { it.unregisterCallback(mediaCb) } catch (_: Exception) {} }
        controllers = list
        controllers.forEach { it.registerCallback(mediaCb, main) }
        refresh()
    }

    private fun mediaLive(): Live? {
        if (!feature(IslandPrefs.F_MEDIA)) return null
        val now = System.currentTimeMillis()
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (playing != null) {
            lastController = playing
            holdUntil = now + 8000
        }
        main.removeCallbacks(delayedRefresh)
        val c = playing ?: lastController?.takeIf { it in controllers && now < holdUntil } ?: return null
        if (playing == null) main.postDelayed(delayedRefresh, holdUntil - now + 50)

        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val icon: Drawable? = art?.let { BitmapDrawable(resources, it) } ?: appIcon(c.packageName)
        return Live(
            key = "media", kind = LiveKind.MEDIA, title = title, subtitle = artist, icon = icon,
            controller = c, playing = playing != null,
            onTap = { try { c.sessionActivity?.send() } catch (_: Exception) {} }
        )
    }

    // ------------------------------------------------------------ notifications

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn, false)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sticky.remove(sbn.key) != null) refresh()
    }

    private fun handle(sbn: StatusBarNotification, replay: Boolean) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val ex = n.extras
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return

        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() } ?: return
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val large: Drawable? = try { n.getLargeIcon()?.loadDrawable(this) } catch (_: Exception) { null }
        val icon = large ?: appIcon(sbn.packageName)
        val pi = n.contentIntent
        val tap: (() -> Unit)? =
            if (pi == null) null else ({ try { pi.send() } catch (_: Exception) {} })
        val key = sbn.key
        val isNew = !replay && !sticky.containsKey(key)
        val showChrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)

        when {
            n.category == Notification.CATEGORY_CALL && (ongoing || n.fullScreenIntent != null) -> {
                if (!feature(IslandPrefs.F_CALLS)) return
                val chrono = if (showChrono) n.`when` else 0L
                sticky[key] = Live(
                    key, LiveKind.CALL, title, text, icon,
                    chronoBase = chrono, actions = actionsOf(n, 2, true), onTap = tap
                )
                val dur = if (!isNew) 0L else if (chrono == 0L) 30000L else 4000L
                refresh(if (isNew) key else null, dur)
            }
            ongoing && showChrono -> {
                if (!feature(IslandPrefs.F_TIMERS)) return
                sticky[key] = Live(
                    key, LiveKind.TIMER, title, text, icon,
                    chronoBase = n.`when`,
                    countDown = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
                    onTap = tap
                )
                refresh(if (isNew) key else null, if (isNew) 3500L else 0L)
            }
            ongoing && ex.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 &&
                !ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) -> {
                if (!feature(IslandPrefs.F_TIMERS)) return
                val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX)
                val p = (ex.getInt(Notification.EXTRA_PROGRESS) * 100 / max).coerceIn(0, 100)
                sticky[key] = Live(key, LiveKind.PROGRESS, title, text, icon, progress = p, onTap = tap)
                refresh()
            }
            ongoing -> {}
            else -> {
                if (replay || !feature(IslandPrefs.F_NOTIF)) return
                if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
                postAlert(
                    Live("alert", LiveKind.NOTIF, title, text, icon,
                        actions = actionsOf(n, 2, false), onTap = tap),
                    6000L
                )
            }
        }
    }

    private fun actionsOf(n: Notification, limit: Int, call: Boolean): List<LiveAction> {
        val out = ArrayList<LiveAction>()
        n.actions?.forEach { a ->
            val pi = a.actionIntent ?: return@forEach
            if (!a.remoteInputs.isNullOrEmpty()) return@forEach
            if (out.size >= limit) return@forEach
            val label = a.title?.toString() ?: return@forEach
            val low = label.lowercase()
            val danger = call && listOf("decline", "reject", "hang", "end", "dismiss", "cancel")
                .any { low.contains(it) }
            val primary = if (call) !danger else out.isEmpty()
            out.add(LiveAction(label, primary, danger) {
                try { pi.send() } catch (_: Exception) {}
            })
        }
        return out
    }

    private fun postAlert(l: Live, ms: Long) {
        alert = l
        main.removeCallbacks(clearAlert)
        main.postDelayed(clearAlert, ms)
        refresh("alert", if (l.kind == LiveKind.CALL) ms else 4500L)
    }

    // ------------------------------------------------------------ quick panel

    private fun quickLive(): Live {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val q = QuickData(
            torchAvailable = torchId != null,
            torchOn = torchOn,
            volume = am.getStreamVolume(AudioManager.STREAM_MUSIC),
            volumeMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            batteryPct = batteryPct,
            onTorch = { toggleTorch() },
            onCamera = { launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) },
            onSettings = { launch(Intent(Settings.ACTION_SETTINGS)) },
            onVolume = { v -> am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
        )
        return Live("quick", LiveKind.QUICK, quick = q)
    }

    private fun launch(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    fun openQuick() {
        quickUntil = System.currentTimeMillis() + 9000
        main.removeCallbacks(quickExpire)
        main.postDelayed(quickExpire, 9000)
        refresh("quick", 9000L)
    }

    // ------------------------------------------------------------ previews

    fun preview(kind: String) {
        val icon = appIcon(packageName)
        when (kind) {
            "notif" -> postAlert(
                Live("alert", LiveKind.NOTIF, "Mia", "Are you coming tonight? Save me a seat", icon,
                    actions = listOf(LiveAction("Reply", true) {}, LiveAction("Mark read") {})),
                6000L
            )
            "call" -> postAlert(
                Live("alert", LiveKind.CALL, "Alex Rivera", "Incoming call", icon,
                    actions = listOf(
                        LiveAction("Decline", danger = true) {},
                        LiveAction("Answer", primary = true) {}
                    )),
                15000L
            )
            "music" -> postAlert(
                Live("alert", LiveKind.MEDIA, "Pink Noise", "Mini Island", icon,
                    playing = true, durationMs = 214000L, demoStart = System.currentTimeMillis()),
                12000L
            )
            "charge" -> showCharge("Charging")
            "quick" -> openQuick()
            else -> {}
        }
    }

    // ------------------------------------------------------------ overlay

    private fun buildItems(): List<Live> {
        if (System.currentTimeMillis() < quickUntil) return listOf(quickLive())
        val out = ArrayList<Live>()
        sticky.values.filter { it.kind == LiveKind.CALL }.forEach { out.add(it) }
        alert?.let { out.add(it) }
        sticky.values.filter { it.kind != LiveKind.CALL }.forEach { out.add(it) }
        mediaLive()?.let { out.add(it) }
        if (out.isEmpty() && IslandPrefs.bool(this, IslandPrefs.ALWAYS_ON, true)) out.add(quickLive())
        return out
    }

    private fun refresh(focusKey: String? = null, expandFor: Long = 0L) {
        val list = buildItems()
        if (list.isEmpty() || !Settings.canDrawOverlays(this)) {
            hide()
            return
        }
        val v = ensureView() ?: return
        v.bind(list, focusKey)
        if (expandFor > 0L) {
            v.setExpanded(true, expandFor)
            v.flash()
        }
    }

    private fun ensureView(): IslandView? {
        island?.let { return it }
        val w = wm ?: return null
        val v = IslandView(this, w)
        v.onCollapsed = {
            quickUntil = 0L
            main.removeCallbacks(quickExpire)
            refresh()
        }
        v.onLongPressed = { openQuick() }
        v.alpha = 0f
        return try {
            w.addView(v, v.lp)
            v.animate().alpha(1f).setDuration(220).start()
            island = v
            v
        } catch (_: Exception) {
            null
        }
    }

    private fun hide() {
        val v = island ?: return
        island = null
        try { wm?.removeView(v) } catch (_: Exception) {}
    }

    private fun appIcon(pkg: String): Drawable? =
        try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
}
