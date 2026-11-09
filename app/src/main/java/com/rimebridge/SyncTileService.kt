package com.rimebridge

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快捷设置磁贴：点一下排一个 expedited 一次性作业；展开面板时补排周期作业，
 * 顺便把上次结果写在副标题上（不进通知栏）。
 */
class SyncTileService : TileService() {

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    override fun onStartListening() {
        super.onStartListening()
        runCatching { Sched.ensureScheduled(this) }
        render(lastRunSubtitle())
    }

    override fun onClick() {
        super.onClick()
        val ok = runCatching { Sched.runOnce(this) }.getOrDefault(false)
        render(if (ok) "已排队…" else "排队失败")
    }

    private fun lastRunSubtitle(): String {
        val at = Prefs.lastAt(this)
        val last = Prefs.lastResult(this)
        return when {
            at == 0L -> "尚未同步"
            last == null -> fmt.format(Date(at))
            else -> fmt.format(Date(at)) + " " + last
        }
    }

    private fun render(subtitle: String) {
        val t = qsTile ?: return
        t.label = getString(R.string.tile_label)
        t.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        t.state = Tile.STATE_INACTIVE
        t.subtitle = subtitle
        t.updateTile()
    }
}
