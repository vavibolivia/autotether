package kr.across.autotether

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/** USB 테더링 인터페이스(rndis0 / ncm0 / usb0 …) 정보. */
data class TetherIface(val name: String, val address: Inet4Address, val prefixLength: Int)

object TetherProbe {
    private val USB_IFACE = Regex("^(rndis|ncm|usb)\\d*$")

    /**
     * 현재 IPv4가 할당되어 올라와 있는 USB 테더링 인터페이스를 찾는다.
     * 권한 없이 확인할 수 있는 "USB 테더링 ON" 판정 기준으로 쓴다.
     */
    fun activeUsbTether(): TetherIface? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { USB_IFACE.matches(it.name) && it.isUp }
            .firstNotNullOfOrNull { nif ->
                nif.interfaceAddresses
                    .firstOrNull { it.address is Inet4Address }
                    ?.let { TetherIface(nif.name, it.address as Inet4Address, it.networkPrefixLength.toInt()) }
            }
    } catch (e: Exception) {
        null
    }

    /** Settings 화면에서 해당 항목으로 스크롤 + 강조 표시하게 하는 extra (AOSP/삼성 공통). */
    const val EXTRA_HIGHLIGHT = ":settings:fragment_args_key"

    /**
     * 테더링 설정 화면을 "USB 테더링" 스위치가 강조된 상태로 연다.
     * 삼성 → AOSP 순으로 시도하고, 모두 실패하면 무선/네트워크 설정으로 대체한다.
     */
    fun openTetherSettings(context: Context): Boolean {
        val candidates = listOf(
            ComponentName("com.android.settings", "com.android.settings.Settings\$SecTetherSettingsActivity"),
            ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
            ComponentName("com.android.settings", "com.android.settings.TetherSettings"),
        )
        for (c in candidates) {
            try {
                context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(c).putExtra(EXTRA_HIGHLIGHT, "usb_tether_settings"))
                return true
            } catch (_: Exception) {
                // 다음 후보
            }
        }
        return try {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)); true
        } catch (_: Exception) {
            false
        }
    }

    /** 개발자 옵션을 "기본 USB 구성" 항목이 강조된 상태로 연다. */
    fun openDefaultUsbConfig(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(EXTRA_HIGHLIGHT, "default_usb_configuration"),
        )
        true
    } catch (_: Exception) {
        false
    }
}
