package net.wastu.cleancopy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Long-pressing the tile opens the same explicit source chooser as a regular tile tap. */
class CleanCopyTilePreferencesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, TileSourceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}
