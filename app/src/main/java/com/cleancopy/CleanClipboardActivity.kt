package com.cleancopy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Routes a foreground clipboard request without introducing a background clipboard monitor. */
class CleanClipboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, CleanMediaActivity::class.java)
                .putExtra(CleanMediaActivity.EXTRA_SAVE_TO_LIBRARY, false)
                .putExtra(CleanMediaActivity.EXTRA_CURRENT_CLIPBOARD, true)
        )
        finish()
    }
}
