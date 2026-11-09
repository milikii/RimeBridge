package com.rimebridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject

/** 一个文件的同步基线：两端 size/mtime，加上内容 CRC32。 */
data class Baseline(
    val srcSize: Long,
    val srcMtime: Long,
    val dstSize: Long,
    val dstMtime: Long,
    val crc: Long
)

/**
 * 轻量偏好存储。刻意不用 DataStore：这里只有几个标量和一个几十条的小清单，
 * SharedPreferences 不会拖进任何额外依赖。
 */
object Prefs {

    private const val FILE = "rimebridge"
    private const val K_PRIV = "priv_tree"
    private const val K_PUB = "pub_tree"
    private const val K_INTERVAL = "interval_min"
    private const val K_ID = "install_id"
    private const val K_ID_MTIME = "install_id_mtime"
    private const val K_MANIFEST = "manifest"
    private const val K_LAST = "last_result"
    private const val K_LAST_AT = "last_at"
    private const val K_FOLLOWUP_AT = "followup_at"
    private const val K_SYNC_ID = "sync_id"

    const val DEFAULT_INTERVAL = 30

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun privTree(c: Context): String? = sp(c).getString(K_PRIV, null)
    fun pubTree(c: Context): String? = sp(c).getString(K_PUB, null)

    fun setPrivTree(c: Context, v: String?) {
        sp(c).edit().putString(K_PRIV, v).apply()
    }

    fun setPubTree(c: Context, v: String?) {
        sp(c).edit().putString(K_PUB, v).apply()
    }

    fun interval(c: Context): Int = sp(c).getInt(K_INTERVAL, DEFAULT_INTERVAL)

    fun setInterval(c: Context, v: Int) {
        sp(c).edit().putInt(K_INTERVAL, v).apply()
    }

    fun installId(c: Context): String? = sp(c).getString(K_ID, null)
    fun installIdMtime(c: Context): Long = sp(c).getLong(K_ID_MTIME, -1L)

    fun setInstallId(c: Context, id: String, mtime: Long) {
        sp(c).edit().putString(K_ID, id).putLong(K_ID_MTIME, mtime).apply()
    }

    /**
     * 用户手填的同步 ID（公共目录下的子目录名，也是私有 sync/<名>）。
     * 空串表示「用 installation.yaml 里自动读到的 installation_id」。
     */
    fun syncId(c: Context): String = sp(c).getString(K_SYNC_ID, "") ?: ""

    fun setSyncId(c: Context, v: String) {
        sp(c).edit().putString(K_SYNC_ID, v.trim()).apply()
    }

    fun lastResult(c: Context): String? = sp(c).getString(K_LAST, null)
    fun lastAt(c: Context): Long = sp(c).getLong(K_LAST_AT, 0L)

    fun setLast(c: Context, text: String) {
        sp(c).edit().putString(K_LAST, text).putLong(K_LAST_AT, System.currentTimeMillis()).apply()
    }

    /** 上次排「15 s 补跑」的时间，用来防住连续补跑的死循环。 */
    fun followupAt(c: Context): Long = sp(c).getLong(K_FOLLOWUP_AT, 0L)

    fun setFollowupAt(c: Context, t: Long) {
        sp(c).edit().putLong(K_FOLLOWUP_AT, t).apply()
    }

    // ---- 清单：key = "push|<设备id>|<文件名>" 或 "pull|<设备id>|<文件名>" ----

    private fun loadManifest(c: Context): JSONObject {
        val raw = sp(c).getString(K_MANIFEST, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: JSONException) {
            JSONObject()
        }
    }

    @Synchronized
    fun baseline(c: Context, key: String): Baseline? {
        val o = loadManifest(c).optJSONObject(key) ?: return null
        return Baseline(
            srcSize = o.optLong("ss", -1L),
            srcMtime = o.optLong("sm", -1L),
            dstSize = o.optLong("ds", -1L),
            dstMtime = o.optLong("dm", -1L),
            crc = o.optLong("crc", 0L)
        )
    }

    @Synchronized
    fun putBaseline(c: Context, key: String, b: Baseline) {
        val m = loadManifest(c)
        val o = JSONObject()
            .put("ss", b.srcSize)
            .put("sm", b.srcMtime)
            .put("ds", b.dstSize)
            .put("dm", b.dstMtime)
            .put("crc", b.crc)
        m.put(key, o)
        sp(c).edit().putString(K_MANIFEST, m.toString()).apply()
    }

    @Synchronized
    fun dropBaseline(c: Context, key: String) {
        val m = loadManifest(c)
        if (m.has(key)) {
            m.remove(key)
            sp(c).edit().putString(K_MANIFEST, m.toString()).apply()
        }
    }

    fun reset(c: Context) {
        sp(c).edit().clear().apply()
    }
}
