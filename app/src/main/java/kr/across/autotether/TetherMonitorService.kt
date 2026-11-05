package kr.across.autotether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * USB 연결 세션과 USB 테더링 상태를 추적해 PC별 마지막 테더링 상태를 기록하고,
 * 다음 연결 때 그 상태로 맞추도록 알림을 띄운다(권한이 없어 직접 전환은 하지 않는다).
 *
 * 세션 흐름
 *  - USB 연결 → 세션 시작. 몇 초 안에 테더링이 켜지지 않았고 직전 세션이 ON이었으면 "켜기" 알림.
 *  - 테더링 ON 감지 → PC 식별(NetBIOS/mDNS/LLMNR). 이 PC의 마지막 상태가 OFF였고 테더링이
 *    연결 직후 저절로 켜진 경우(기본 USB 구성 = 테더링)라면 "끄기" 알림.
 *  - USB가 연결된 채 테더링 OFF → 이 PC의 마지막 상태 = OFF.
 *  - 케이블 분리 시 테더링이 함께 꺼지는 것은 사용자의 선택이 아니므로 기록하지 않는다.
 *  - USB 기능 전환(MTP↔RNDIS) 중 잠깐 끊기는 USB_STATE는 디바운스로 무시한다.
 */
class TetherMonitorService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: HistoryStore
    private lateinit var nm: NotificationManager

    // ---- 세션 상태 (메인 스레드에서만 접근) ----
    private var sessionActive = false
    private var sessionStart = 0L
    private var serviceStartedAt = 0L
    /** 서비스 시작 시 이미 연결돼 있던 세션(테더링 상태가 사용자가 켠 것일 수 있어 자동 켜짐 판정에서 제외). */
    private var sessionPreexisting = false
    private var promptedOn = false
    private var tetherOn = false
    private var tetherCameOnAuto = false
    private var committedTetherOn = false
    private var currentPc: PcIdentity? = null
    private var pcCounted = false
    private var identifyGeneration = 0
    private var pendingDisconnect: Runnable? = null
    private var pendingOffCommit: Runnable? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val connected = i.getBooleanExtra("connected", false)
            Log.d(TAG, "USB_STATE connected=$connected extras=${i.extras?.keySet()}")
            onUsbState(connected)
            checkTether()
        }
    }

    private val tetherReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = checkTether()
    }

    private val poll = object : Runnable {
        override fun run() {
            checkTether()
            if (sessionActive) main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = HistoryStore(this)
        serviceStartedAt = System.currentTimeMillis()
        nm = getSystemService(NotificationManager::class.java)
        createChannels()
        ServiceCompat.startForeground(
            this, NOTIF_STATUS, statusNotification("USB 연결 대기 중"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        // USB_STATE는 sticky이므로 등록 즉시 현재 상태가 전달된다.
        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter(ACTION_USB_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, tetherReceiver, IntentFilter(ACTION_TETHER_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REIDENTIFY) reidentify()
        return START_STICKY
    }

    /** 사용자가 PC 방화벽을 설정한 뒤 케이블을 다시 꽂지 않고 식별을 재시도한다. */
    private fun reidentify() {
        if (!sessionActive) { updateStatus("USB 연결 대기 중 — PC에 연결 후 다시 시도"); return }
        val iface = TetherProbe.activeUsbTether()
        if (iface == null) { updateStatus("테더링이 꺼져 있어 식별할 수 없음"); return }
        currentPc = null
        updateStatus("테더링 켜짐 — PC 다시 식별 중")
        identify(iface)
    }

    override fun onDestroy() {
        running = false
        unregisterReceiver(usbReceiver)
        unregisterReceiver(tetherReceiver)
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        status = "모니터링 중지됨"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- USB 세션 ----------------

    private fun onUsbState(connected: Boolean) {
        if (connected) {
            pendingDisconnect?.let { main.removeCallbacks(it) }
            pendingDisconnect = null
            if (!sessionActive) startSession()
        } else if (sessionActive && pendingDisconnect == null) {
            pendingDisconnect = Runnable { pendingDisconnect = null; endSession() }
                .also { main.postDelayed(it, DISCONNECT_DEBOUNCE_MS) }
        }
    }

    private fun startSession() {
        sessionActive = true
        sessionStart = System.currentTimeMillis()
        sessionPreexisting = sessionStart - serviceStartedAt < 2_000
        promptedOn = false
        tetherOn = false
        tetherCameOnAuto = false
        committedTetherOn = false
        currentPc = null
        pcCounted = false
        updateStatus("USB 연결됨 — 테더링 꺼짐")
        main.post(poll)
        main.postDelayed({ maybePromptOn() }, PROMPT_DELAY_MS)
    }

    private fun endSession() {
        sessionActive = false
        identifyGeneration++
        pendingOffCommit?.let { main.removeCallbacks(it) }
        pendingOffCommit = null
        main.removeCallbacks(poll)
        nm.cancel(NOTIF_ACTION)
        store.lastSession = LastSession(currentPc?.key, committedTetherOn, System.currentTimeMillis())
        Log.i(TAG, "session end pc=${currentPc?.key} tether=$committedTetherOn")
        updateStatus("USB 연결 대기 중")
    }

    /** 연결 후 테더링이 켜지지 않았을 때, 직전 세션 기준으로 켜기를 제안한다. */
    private fun maybePromptOn() {
        if (!sessionActive || tetherOn) return
        val last = store.lastSession ?: return
        if (!last.tetherOn) return
        val name = last.pcKey?.let { store.get(it)?.displayName ?: it } ?: "알 수 없는 PC"
        promptedOn = true
        showAction(
            "USB 테더링 켜기",
            "마지막 연결($name)에서 테더링을 사용했습니다. 탭하면 테더링 설정을 엽니다.",
        )
    }

    // ---------------- 테더링 상태 ----------------

    private fun checkTether() {
        if (!sessionActive) return
        val iface = TetherProbe.activeUsbTether()
        val on = iface != null
        if (on == tetherOn) return
        tetherOn = on
        if (on) onTetherOn(iface!!) else onTetherOff()
    }

    private fun onTetherOn(iface: TetherIface) {
        pendingOffCommit?.let { main.removeCallbacks(it) }
        pendingOffCommit = null
        committedTetherOn = true
        nm.cancel(NOTIF_ACTION)
        val pc = currentPc
        if (pc != null) {
            record(pc, true)
            updateStatus("테더링 켜짐 — ${pc.displayName}")
            return
        }
        tetherCameOnAuto = !promptedOn && !sessionPreexisting &&
            System.currentTimeMillis() - sessionStart < AUTO_WINDOW_MS
        if (tetherCameOnAuto) Prefs.setAutoOnSeen(this, System.currentTimeMillis())
        updateStatus("테더링 켜짐 — PC 식별 중 (${iface.name} ${iface.address.hostAddress})")
        identify(iface)
    }

    private fun onTetherOff() {
        identifyGeneration++
        updateStatus(currentPc?.let { "USB 연결됨 — 테더링 꺼짐 (${it.displayName})" } ?: "USB 연결됨 — 테더링 꺼짐")
        val commit = object : Runnable {
            override fun run() {
                if (pendingDisconnect != null) { main.postDelayed(this, 1000); return }
                pendingOffCommit = null
                // 세션이 끝났다면 케이블 분리로 꺼진 것 → 기록하지 않음
                if (!sessionActive || tetherOn) return
                committedTetherOn = false
                currentPc?.let { record(it, false) }
            }
        }
        pendingOffCommit = commit
        main.postDelayed(commit, OFF_COMMIT_DELAY_MS)
    }

    private fun identify(iface: TetherIface) {
        val gen = ++identifyGeneration
        val auto = tetherCameOnAuto
        worker.execute {
            var result: PcIdentity? = null
            for (delay in IDENTIFY_RETRY_MS) {
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    return@execute
                }
                if (gen != identifyGeneration) return@execute
                val current = TetherProbe.activeUsbTether() ?: return@execute
                result = PcIdentifier.identify(current)
                if (result != null) break
            }
            val id = result
            main.post { onIdentified(gen, id, auto) }
        }
    }

    private fun onIdentified(gen: Int, id: PcIdentity?, auto: Boolean) {
        if (gen != identifyGeneration || !sessionActive || !tetherOn) return
        Prefs.setLastIdentify(this, id != null, id?.displayName)
        if (id == null) {
            updateStatus("테더링 켜짐 — PC 식별 실패 (PC 방화벽 설정 필요)")
            return
        }
        currentPc = id
        val prev = store.get(id.key)
        Log.i(TAG, "identified $id prev=$prev auto=$auto")
        if (prev != null && !prev.lastTetherOn && auto) {
            showAction(
                "USB 테더링 끄기",
                "${id.displayName}에서는 마지막에 테더링을 끈 상태였습니다. 탭하면 테더링 설정을 엽니다.",
            )
        }
        record(id, true)
        updateStatus("테더링 켜짐 — ${id.displayName} (${id.via})")
    }

    private fun record(pc: PcIdentity, on: Boolean) {
        val prev = store.get(pc.key)
        // 서비스 재시작(앱 업데이트·권한 변경·재부팅) 때 이미 연결돼 있던 세션은 새 연결로 세지 않는다
        val firstInSession = !pcCounted && (!sessionPreexisting || prev == null)
        pcCounted = true
        store.put(
            PcRecord(
                key = pc.key,
                displayName = pc.displayName,
                mac = pc.mac ?: prev?.mac,
                lastTetherOn = on,
                lastSeen = System.currentTimeMillis(),
                sessions = (prev?.sessions ?: 0) + if (firstInSession) 1 else 0,
                tetherSessions = (prev?.tetherSessions ?: 0) + if (firstInSession && on) 1 else 0,
            ),
        )
        store.lastSession = LastSession(pc.key, on, System.currentTimeMillis())
    }

    // ---------------- 알림 ----------------

    private fun createChannels() {
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "모니터링 상태", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_ACTION, "테더링 전환 안내", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun updateStatus(text: String) {
        status = "$text  ·  ${TIME.format(Date())}"
        nm.notify(NOTIF_STATUS, statusNotification(text))
    }

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_tether)
            .setContentTitle("AutoTether")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(activityIntent(null))
            .build()

    private fun showAction(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CH_ACTION)
            .setSmallIcon(R.drawable.ic_tether)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(activityIntent(MainActivity.ACTION_OPEN_TETHER))
            .build()
        nm.notify(NOTIF_ACTION, n)
    }

    private fun activityIntent(action: String?): PendingIntent {
        val i = Intent(this, MainActivity::class.java).setAction(action ?: Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, if (action == null) 0 else 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "AutoTether"
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
        private const val CH_STATUS = "status"
        private const val CH_ACTION = "action"
        private const val NOTIF_STATUS = 1
        private const val NOTIF_ACTION = 2

        private const val POLL_MS = 2_000L
        private const val DISCONNECT_DEBOUNCE_MS = 3_000L
        private const val OFF_COMMIT_DELAY_MS = 4_000L
        private const val PROMPT_DELAY_MS = 6_000L
        private const val AUTO_WINDOW_MS = 12_000L
        private val IDENTIFY_RETRY_MS = longArrayOf(2_000, 4_000, 6_000, 10_000, 20_000)

        private val TIME = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

        @Volatile var status: String = "모니터링 중지됨"
            private set
        @Volatile var running: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TetherMonitorService::class.java))
        }

        const val ACTION_REIDENTIFY = "kr.across.autotether.REIDENTIFY"

        fun reidentify(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TetherMonitorService::class.java).setAction(ACTION_REIDENTIFY),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TetherMonitorService::class.java))
        }
    }
}
