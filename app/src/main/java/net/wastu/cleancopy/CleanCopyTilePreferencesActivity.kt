package net.wastu.cleancopy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Long-pressing the tile opens media selection for cleaning and copying. */
class CleanCopyTilePreferencesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, CleanMediaActivity::class.java)
                .putExtra(CleanMediaActivity.EXTRA_SAVE_TO_LIBRARY, false)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}
