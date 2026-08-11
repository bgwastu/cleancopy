package com.cleancopy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Quick Settings opens this activity when the CleanCopy tile is long-pressed. */
class CleanCopyTilePreferencesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, CleanMediaActivity::class.java)
                .putExtra(CleanMediaActivity.EXTRA_SAVE_TO_LIBRARY, false)
        )
        finish()
    }
}
