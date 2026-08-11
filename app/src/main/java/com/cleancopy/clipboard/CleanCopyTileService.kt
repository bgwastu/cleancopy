package com.cleancopy.clipboard

import android.content.Intent
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.cleancopy.CleanMediaActivity

class CleanCopyTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            icon = Icon.createWithResource(this@CleanCopyTileService, com.cleancopy.R.drawable.ic_choose_media_copy)
            label = getString(com.cleancopy.R.string.tile_clean_copy)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
        val intent = Intent(this, CleanMediaActivity::class.java)
            .putExtra(CleanMediaActivity.EXTRA_SAVE_TO_LIBRARY, false)
            .putExtra(CleanMediaActivity.EXTRA_CURRENT_CLIPBOARD, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
