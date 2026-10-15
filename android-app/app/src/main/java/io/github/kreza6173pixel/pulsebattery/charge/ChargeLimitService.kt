package io.github.kreza6173pixel.pulsebattery.charge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import io.github.kreza6173pixel.pulsebattery.MainActivity
import io.github.kreza6173pixel.pulsebattery.R
import io.github.kreza6173pixel.pulsebattery.diag.DiagResult
import io.github.kreza6173pixel.pulsebattery.exec.ConnectionState
import io.github.kreza6173pixel.pulsebattery.exec.ExecBridge
import io.github.kreza6173pixel.pulsebattery.shizuku.UID_ROOT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Keeps the battery at the chosen level by flipping the same kernel gate the manual buttons
 * use. The decision itself lives in [ChargeLimitPolicy]; this class only gathers facts,
 * applies the verdict and shows what it is doing.
 *
 * It is a foreground service with a permanent notification on purpose. The app advertises
 * that it runs nothing in the background, so the one component that breaks that has to be
 * opt-in and impossible to miss.
 *
 * Two modes. Normally it watches the level and enforces the thresholds. In release-only mode
 * it does one thing and quits: open the charge gate if a reboot left it shut.
 */
class ChargeLimitService : Service() {

    private val bridge by lazy { ExecBridge(applicationContext) }
    private val store by lazy { ChargeLimitStore(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    /** One-shot mode: undo a stranded gate, then stop. Never enforces thresholds. */
    private var releaseOnly = false

    /**
     * Consecutive polls that reported no charger. The gate is only opened once this reaches
     * [UNPLUGGED_CONFIRMATIONS], so one odd sample cannot make the gate flap.
     */
    private var unpluggedPolls = 0

    private var lastLine: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        bridge.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us. Keep the mode we were started with
        // rather than silently falling back to the watching mode.
        if (intent != null) {
            releaseOnly = intent.getBooleanExtra(EXTRA_RELEASE_ONLY, false)
        }
        val opening = if (releaseOnly) {
            getString(R.string.limit_notif_releasing)
        } else {
            getString(R.string.limit_notif_starting)
        }
        startForeground(NOTIFICATION_ID, notification(opening))
        if (loop == null) {
            loop = scope.launch { run() }
        }
        // Release-only work is finished in one pass; being restarted with a null intent would
        // turn it into a background watcher the user never asked for.
        return if (releaseOnly) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        loop = null
        scope.cancel()
        bridge.stop()
        super.onDestroy()
    }

    private suspend fun run() {
        var connectAttempts = 0
        while (scope.isActive) {
            if (bridge.connectionState != ConnectionState.CONNECTED) {
                connectAttempts++
                // In release-only mode there is nobody waiting at the screen, so give up
                // instead of holding a notification open forever.
                if (releaseOnly && connectAttempts > RELEASE_CONNECT_ATTEMPTS) {
                    show(getString(R.string.limit_notif_release_no_service))
                    delay(LINGER_MS)
                    stopSelf()
                    return
                }
                bridge.connect()
                show(getString(R.string.limit_notif_connecting))
                delay(CONNECT_WAIT_MS)
                continue
            }
            if (releaseOnly) {
                releaseTick()
                return
            }
            tick()
            delay(POLL_MS)
        }
    }

    /** Opens the gate if a reboot left it shut, says what it found, and stops. */
    private fun releaseTick() {
        val repository = GateRepository(bridge, writable = runningAsRoot())
        val snapshot = when (val result = repository.read()) {
            is DiagResult.Error -> {
                show(getString(R.string.limit_notif_unreadable))
                stopSelf()
                return
            }
            is DiagResult.Ok -> result.value
        }
        if (snapshot.state != GateState.PAUSED) {
            show(getString(R.string.limit_notif_release_not_needed))
            stopSelf()
            return
        }
        val after = repository.resume().stateAfter
        show(
            getString(
                if (after == GateState.OPEN) {
                    R.string.limit_notif_release_done
                } else {
                    R.string.limit_notif_release_failed
                }
            )
        )
        stopSelf()
    }

    private fun tick() {
        val settings = store.read()
        val repository = GateRepository(bridge, writable = runningAsRoot())

        val snapshot = when (val result = repository.read()) {
            is DiagResult.Error -> {
                show(getString(R.string.limit_notif_unreadable))
                return
            }
            is DiagResult.Ok -> result.value
        }

        val pluggedNow = readPlugged()
        if (pluggedNow) unpluggedPolls = 0 else unpluggedPolls++
        // Treated as still plugged until the readings agree, so a single odd sample never
        // produces a write.
        val plugged = pluggedNow || unpluggedPolls < UNPLUGGED_CONFIRMATIONS

        val decision = ChargeLimitPolicy.decide(
            settings,
            LimitInputs(snapshot.capacityPercent, snapshot.state, plugged),
        )

        val stateAfter = when (decision) {
            LimitDecision.PAUSE -> repository.pause().stateAfter
            LimitDecision.RESUME -> repository.resume().stateAfter
            LimitDecision.LEAVE -> snapshot.state
        }

        show(statusLine(settings, snapshot, stateAfter, plugged))

        if (!ChargeLimitPolicy.shouldKeepRunning(settings, stateAfter)) {
            stopSelf()
        }
    }

    /**
     * True when the privileged service currently runs as root. Read every tick rather than
     * cached: a Shizuku or Sui restart can change it under us, and a stale yes would only
     * produce writes that are refused.
     */
    private fun runningAsRoot(): Boolean =
        runCatching { Shizuku.getUid() }.getOrDefault(-1) == UID_ROOT

    /**
     * Charger presence from the framework rather than from a kernel node, because the node
     * that holds charging off also changes what the charging status line says.
     *
     * Defaults to true when the sticky intent is missing, so an unknown reading is never
     * treated as unplugged.
     */
    private fun readPlugged(): Boolean {
        val intent = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return true
        return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, PLUGGED_UNKNOWN) != 0
    }

    private fun statusLine(
        settings: ChargeLimitSettings,
        snapshot: GateSnapshot,
        state: GateState,
        plugged: Boolean,
    ): String {
        val level = snapshot.capacityPercent
        val levelText = if (level == null) {
            getString(R.string.limit_notif_level_unknown)
        } else {
            getString(R.string.limit_notif_level, level)
        }
        val gateText = getString(
            when (state) {
                GateState.PAUSED -> R.string.limit_notif_gate_paused
                GateState.OPEN -> R.string.limit_notif_gate_open
                GateState.UNKNOWN -> R.string.limit_notif_gate_unknown
            }
        )
        val plugText = getString(
            if (plugged) R.string.limit_notif_plugged else R.string.limit_notif_unplugged
        )
        val thresholds = getString(
            R.string.limit_notif_thresholds,
            settings.limitPercent,
            settings.resumePercent,
        )
        return "$levelText  $gateText  $plugText  $thresholds"
    }

    private fun show(line: String) {
        if (line == lastLine) return
        lastLine = line
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification(line))
    }

    private fun notification(line: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(
                getString(
                    if (releaseOnly) {
                        R.string.limit_notif_release_title
                    } else {
                        R.string.limit_notif_title
                    }
                )
            )
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.limit_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.limit_channel_description)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** Set to true to run the one-shot gate release instead of the watcher. */
        const val EXTRA_RELEASE_ONLY = "release_only"

        private const val CHANNEL_ID = "charge_limit"
        private const val NOTIFICATION_ID = 4201
        private const val POLL_MS = 30_000L
        private const val CONNECT_WAIT_MS = 5_000L
        private const val PLUGGED_UNKNOWN = -1

        /** Polls that must agree before an unplugged charger opens the gate. */
        private const val UNPLUGGED_CONFIRMATIONS = 2

        /** How long the release pass waits for the privileged service before giving up. */
        private const val RELEASE_CONNECT_ATTEMPTS = 12

        /** Leaves the final line on screen briefly before the notification goes away. */
        private const val LINGER_MS = 4_000L

        /** Starts the service, or does nothing when the policy says it has no work. */
        fun sync(context: Context) {
            val settings = ChargeLimitStore(context).read()
            if (!settings.enabled) return
            start(context)
        }

        fun start(context: Context, releaseOnly: Boolean = false) {
            val intent = Intent(context, ChargeLimitService::class.java)
                .putExtra(EXTRA_RELEASE_ONLY, releaseOnly)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ChargeLimitService::class.java)) }
        }
    }
}
