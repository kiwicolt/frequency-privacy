package app.relish.ui.common

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Lets the user pick a text file containing a Reddit client id (e.g. ReVanced's
 * `reddit_client_id_revanced.txt` in the storage root) via the system file picker, so the id
 * never has to be retyped. Needs no storage permission. Returns a function that opens the picker.
 */
@Composable
fun rememberClientIdImporter(onResult: (Result<String>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onResult(runCatching { readClientId(context, uri) })
    }
    return { launcher.launch(arrayOf("text/plain", "*/*")) }
}

private val CLIENT_ID = Regex("""[A-Za-z0-9_-]{14,30}""")

private fun readClientId(context: Context, uri: Uri): String {
    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Couldn't read that file")
    return CLIENT_ID.find(text.trim())?.value ?: error("No client id found in that file")
}
