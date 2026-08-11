package net.wastu.cleancopy.clipboard

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import net.wastu.cleancopy.CleanClipboardActivity
import net.wastu.cleancopy.ClipboardLinkTileAction

class CleanCopyDefaultTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            icon = Icon.createWithResource(this@CleanCopyDefaultTileService, net.wastu.cleancopy.R.drawable.ic_clean_copy_mark)
            label = getString(net.wastu.cleancopy.R.string.tile_cleancopy)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (ClipboardLinkTileAction.start(this)) return
        val intent = Intent(this, CleanClipboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
