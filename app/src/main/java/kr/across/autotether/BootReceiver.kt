package kr.across.autotether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 부팅/앱 업데이트 후 모니터링이 켜져 있었다면 서비스를 다시 시작한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.isEnabled(context)) TetherMonitorService.start(context)
    }
}

object Prefs {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()

    /** USB 연결 직후 테더링이 저절로 켜진 것을 마지막으로 본 시각(= 기본 USB 구성이 테더링). 0 = 미확인. */
    fun autoOnSeenAt(c: Context) = p(c).getLong("auto_on_seen_at", 0L)
    fun setAutoOnSeen(c: Context, at: Long) = p(c).edit().putLong("auto_on_seen_at", at).apply()

    /** PC 설치 스크립트가 "기본 USB 구성 = USB 테더링"을 설정한 시각. 0 = 없음. */
    fun usbDefaultSetAt(c: Context) = p(c).getLong("usb_default_set_at", 0L)
    fun setUsbDefaultSet(c: Context, at: Long) = p(c).edit().putLong("usb_default_set_at", at).apply()

    /** 마지막 PC 식별 결과. ok=false면 name은 null. */
    data class IdentifyResult(val ok: Boolean, val name: String?, val at: Long)

    fun lastIdentify(c: Context): IdentifyResult? {
        val at = p(c).getLong("identify_at", 0L)
        if (at == 0L) return null
        return IdentifyResult(p(c).getBoolean("identify_ok", false), p(c).getString("identify_name", null), at)
    }

    fun setLastIdentify(c: Context, ok: Boolean, name: String?) = p(c).edit()
        .putBoolean("identify_ok", ok)
        .putString("identify_name", name)
        .putLong("identify_at", System.currentTimeMillis())
        .apply()
}
