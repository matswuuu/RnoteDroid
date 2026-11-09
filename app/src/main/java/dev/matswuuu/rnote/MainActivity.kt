package dev.matswuuu.rnote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dev.matswuuu.rnote.ui.RnoteApp
import dev.matswuuu.rnote.ui.theme.RnoteTheme

class MainActivity : ComponentActivity() {
    private val externalUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) externalUri.value = intent.documentUri()
        setContent {
            RnoteTheme {
                RnoteApp(
                    externalUri = externalUri.value,
                    onExternalUriConsumed = { externalUri.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.documentUri()?.let { externalUri.value = it }
    }

    private fun Intent.documentUri(): Uri? = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") (getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        else -> null
    }
}
