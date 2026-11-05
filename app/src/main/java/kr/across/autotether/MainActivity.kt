package kr.across.autotether

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var store: HistoryStore
    private lateinit var status: TextView
    private lateinit var lastSession: TextView
    private lateinit var checklist: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var enabled: CompoundButton
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 알림에서 바로 열린 경우: 테더링 설정만 띄우고 종료
        if (intent?.action == ACTION_OPEN_TETHER) {
            TetherProbe.openTetherSettings(this)
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        // Android 15+ edge-to-edge: 상태바/내비게이션바/컷아웃 영역만큼 안쪽으로 배치
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        store = HistoryStore(this)
        status = findViewById(R.id.status)
        lastSession = findViewById(R.id.last_session)
        checklist = findViewById(R.id.checklist)
        list = findViewById(R.id.pc_list)
        enabled = findViewById(R.id.enabled)

        enabled.isChecked = Prefs.isEnabled(this)
        enabled.setOnCheckedChangeListener { _, on ->
            Prefs.setEnabled(this, on)
            if (on) TetherMonitorService.start(this) else TetherMonitorService.stop(this)
        }
        findViewById<Button>(R.id.open_tether).setOnClickListener { TetherProbe.openTetherSettings(this) }
        findViewById<Button>(R.id.clear).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("모든 PC 기록을 삭제할까요?")
                .setPositiveButton("삭제") { _, _ -> store.clear(); refresh() }
                .setNegativeButton("취소", null)
                .show()
        }

        handleSetupExtras(intent)
        firstRunSetup()
        if (Prefs.isEnabled(this)) TetherMonitorService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSetupExtras(intent)
        if (intent.action == ACTION_OPEN_TETHER) TetherProbe.openTetherSettings(this)
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    /** PC 설치 스크립트가 넘겨 준 설정 결과(앱은 기본 USB 구성 값을 직접 읽을 권한이 없다). */
    private fun handleSetupExtras(i: Intent?) {
        val at = i?.getLongExtra(EXTRA_USB_DEFAULT_SET, 0L) ?: 0L
        if (at > 0) Prefs.setUsbDefaultSet(this, at)
    }

    /** 첫 실행: 모니터링을 켜고, 빠진 권한을 차례로 요청한다. */
    private fun firstRunSetup() {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        if (p.getBoolean("initialized", false)) return
        p.edit().putBoolean("initialized", true).apply()
        Prefs.setEnabled(this, true)
        enabled.isChecked = true
        if (!notificationsOk()) requestNotifications() else if (!batteryOk()) requestBattery()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            TetherMonitorService.start(this) // 상태 알림 다시 게시
            if (!batteryOk()) requestBattery()
        }
    }

    // ---------------- 체크 항목 ----------------

    private fun notificationsOk() = NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun devOptionsOn() =
        Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("notif_asked", false)
        ) {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("notif_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        } else {
            // 이미 한 번 거부했으면 시스템 대화상자가 다시 뜨지 않으므로 앱 알림 설정으로 보낸다
            runCatching {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBattery() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun renderChecklist() {
        checklist.removeAllViews()
        val autoAt = Prefs.autoOnSeenAt(this)
        val ident = Prefs.lastIdentify(this)

        item(
            if (notificationsOk()) Mark.OK else Mark.NG, "1. 알림 허용",
            "테더링 켜기/끄기 안내와 상태 표시에 필요합니다.",
            "허용" to ::requestNotifications,
        )
        item(
            if (batteryOk()) Mark.OK else Mark.NG, "2. 배터리 최적화 제외",
            "백그라운드에서 USB 연결을 계속 감시하려면 필요합니다.",
            "설정" to ::requestBattery,
        )
        item(
            if (Prefs.isEnabled(this) && TetherMonitorService.running) Mark.OK else Mark.NG, "3. 모니터링 실행",
            "위 스위치. 재부팅 후에도 자동으로 다시 시작됩니다.",
            "켜기" to { enabled.isChecked = true },
        )
        val setAt = Prefs.usbDefaultSetAt(this)
        item(
            when {
                autoAt > 0 || setAt > 0 -> Mark.OK
                !devOptionsOn() -> Mark.NG
                else -> Mark.UNKNOWN
            },
            "4. 연결 시 테더링 자동 켜기",
            when {
                autoAt > 0 -> "동작 확인됨 — ${FMT.format(Date(autoAt))} 연결 때 자동으로 켜졌습니다."
                setAt > 0 -> "설정됨 (PC 설치 스크립트, ${FMT.format(Date(setAt))}). " +
                    "삼성 ‘기본 USB 구성’ 화면에는 ‘폰 충전만’으로 보일 수 있지만 정상이니 그 화면에서 다른 항목을 누르지 마세요."
                !devOptionsOn() -> "개발자 옵션이 꺼져 있습니다. 설정 → 휴대전화 정보 → 소프트웨어 정보 → 빌드번호를 7번 누른 뒤, " +
                    "개발자 옵션 → 기본 USB 구성 → USB 테더링. (PC 설치 스크립트를 쓰면 자동 설정)"
                else -> "개발자 옵션 → 기본 USB 구성 → USB 테더링을 선택하세요. (PC 설치 스크립트를 쓰면 자동 설정)"
            },
            (if (devOptionsOn()) "기본 USB 구성" else "휴대전화 정보") to ::openDevOrAbout,
        )
        item(
            when {
                ident == null -> Mark.UNKNOWN
                ident.ok -> Mark.OK
                else -> Mark.NG
            },
            "5. PC 식별 (PC마다 1회)",
            when {
                ident == null -> "아직 시도하지 않았습니다. 테더링이 켜지면 자동으로 PC 이름을 확인합니다."
                ident.ok -> "최근 식별: ${ident.name} (${FMT.format(Date(ident.at))})"
                else -> "식별 실패 (${FMT.format(Date(ident.at))}). 그 PC에서 설치 스크립트(설치.bat)를 한 번 실행한 뒤 ‘다시 식별’을 누르세요."
            },
            "다시 식별" to { TetherMonitorService.reidentify(this) },
        )
    }

    private fun openDevOrAbout() {
        if (devOptionsOn() && TetherProbe.openDefaultUsbConfig(this)) return
        runCatching { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
    }

    private enum class Mark(val symbol: String, val color: Int) {
        OK("✓", 0xFF2E7D32.toInt()), NG("✗", 0xFFC62828.toInt()), UNKNOWN("?", 0xFFF9A825.toInt())
    }

    private fun item(mark: Mark, title: String, desc: String, action: Pair<String, () -> Unit>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = mark.symbol
            textSize = 20f
            setTextColor(mark.color)
            minWidth = dp(28)
        })
        row.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "$title\n$desc"
            textSize = 13f
        })
        if (mark != Mark.OK || action.first == "다시 식별") {
            row.addView(Button(this).apply {
                text = action.first
                setOnClickListener { action.second() }
            })
        }
        checklist.addView(row)
    }

    // ---------------- 상태 / 기록 ----------------

    private fun refresh() {
        status.text = TetherMonitorService.status
        if (enabled.isChecked != Prefs.isEnabled(this)) enabled.isChecked = Prefs.isEnabled(this)
        renderChecklist()

        lastSession.text = store.lastSession?.let { s ->
            val name = s.pcKey?.let { store.get(it)?.displayName ?: it } ?: "식별 안 된 PC"
            "직전 세션: $name · ${if (s.tetherOn) "테더링 ON" else "테더링 OFF"} · ${FMT.format(Date(s.endedAt))}"
        } ?: "직전 세션: 없음"

        val records = store.all()
        list.removeAllViews()
        if (records.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "아직 기록된 PC가 없습니다.\nUSB를 연결하고 테더링이 켜지면 PC가 자동으로 등록됩니다."
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        records.forEach { r -> list.addView(row(r)) }
    }

    private fun row(r: PcRecord): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(TextView(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = buildString {
                append(r.displayName)
                append(if (r.lastTetherOn) "   ● 마지막: ON" else "   ○ 마지막: OFF")
                append("\n연결 ${r.sessions}회 · 테더링 ${r.tetherSessions}회 · ${FMT.format(Date(r.lastSeen))}")
                r.mac?.let { append("\nMAC $it") }
            }
        })
        addView(Button(this@MainActivity).apply {
            text = "삭제"
            setOnClickListener { store.delete(r.key); refresh() }
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_OPEN_TETHER = "kr.across.autotether.OPEN_TETHER"
        const val EXTRA_USB_DEFAULT_SET = "kr.across.autotether.USB_DEFAULT_SET"
        private const val REQ_NOTIF = 1
        private val FMT = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)
    }
}
