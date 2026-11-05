package kr.across.autotether

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 한 PC에 대한 테더링 사용 기록. key = 대문자 호스트명. */
data class PcRecord(
    val key: String,
    val displayName: String,
    val mac: String?,
    val lastTetherOn: Boolean,
    val lastSeen: Long,
    val sessions: Int,
    val tetherSessions: Int,
)

/** 마지막 USB 세션 결과. PC를 식별하지 못한 세션이면 pcKey = null. */
data class LastSession(val pcKey: String?, val tetherOn: Boolean, val endedAt: Long)

/** SharedPreferences(JSON) 기반 저장소. 데이터가 적어 DB는 쓰지 않는다. */
class HistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("history", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<PcRecord> {
        val arr = JSONArray(prefs.getString(KEY_PCS, "[]"))
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            .sortedByDescending { it.lastSeen }
    }

    @Synchronized
    fun get(key: String): PcRecord? = all().firstOrNull { it.key == key }

    @Synchronized
    fun put(record: PcRecord) {
        val list = all().filterNot { it.key == record.key } + record
        save(list)
    }

    @Synchronized
    fun delete(key: String) = save(all().filterNot { it.key == key })

    @Synchronized
    fun clear() = prefs.edit().remove(KEY_PCS).remove(KEY_LAST).apply()

    var lastSession: LastSession?
        @Synchronized get() {
            val s = prefs.getString(KEY_LAST, null) ?: return null
            val o = JSONObject(s)
            return LastSession(
                pcKey = o.optString("pcKey").ifEmpty { null },
                tetherOn = o.getBoolean("tetherOn"),
                endedAt = o.getLong("endedAt"),
            )
        }
        @Synchronized set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_LAST).apply(); return
            }
            val o = JSONObject()
                .put("pcKey", value.pcKey ?: "")
                .put("tetherOn", value.tetherOn)
                .put("endedAt", value.endedAt)
            prefs.edit().putString(KEY_LAST, o.toString()).apply()
        }

    private fun save(list: List<PcRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_PCS, arr.toString()).apply()
    }

    private fun toJson(r: PcRecord) = JSONObject()
        .put("key", r.key)
        .put("displayName", r.displayName)
        .put("mac", r.mac ?: "")
        .put("lastTetherOn", r.lastTetherOn)
        .put("lastSeen", r.lastSeen)
        .put("sessions", r.sessions)
        .put("tetherSessions", r.tetherSessions)

    private fun fromJson(o: JSONObject) = PcRecord(
        key = o.getString("key"),
        displayName = o.optString("displayName", o.getString("key")),
        mac = o.optString("mac").ifEmpty { null },
        lastTetherOn = o.getBoolean("lastTetherOn"),
        lastSeen = o.getLong("lastSeen"),
        sessions = o.optInt("sessions", 0),
        tetherSessions = o.optInt("tetherSessions", 0),
    )

    companion object {
        private const val KEY_PCS = "pcs"
        private const val KEY_LAST = "last_session"
    }
}
