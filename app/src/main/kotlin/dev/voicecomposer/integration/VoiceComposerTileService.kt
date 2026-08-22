package dev.voicecomposer.integration

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import dev.voicecomposer.ui.ComposerActivity

/**
 * Quick Settings activation.
 *
 * The highest-ranked activation surface in the brief's own security ordering
 * (section 32): it needs no overlay permission, no accessibility service and no
 * background process, and the user places the tile themselves, so it cannot
 * appear without their action.
 *
 * NOTE: implemented but not device-tested. See docs/TEST_RESULTS.md.
 */
@RequiresApi(Build.VERSION_CODES.N)
class VoiceComposerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(dev.voicecomposer.R.string.tile_label)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()

        val intent = Intent(this, ComposerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // startActivityAndCollapse is the supported way to open a UI from a
        // tile; from API 34 it requires a PendingIntent.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = android.app.PendingIntent.getActivity(
                this,
                0,
                intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
