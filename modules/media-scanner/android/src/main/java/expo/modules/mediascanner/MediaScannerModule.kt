package expo.modules.mediascanner

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.io.FileInputStream

class MediaScannerModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("MediaScanner")

    AsyncFunction("scanFile") { path: String ->
      val context = appContext.reactContext ?: throw Exception("reactContext is null")
      val file = File(path)
      if (!file.exists()) throw Exception("File not found: $path")
      val displayName = file.name

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val mimeType = when {
          displayName.endsWith(".flac") -> "audio/flac"
          displayName.endsWith(".mp3") -> "audio/mpeg"
          displayName.endsWith(".m4a") -> "audio/mp4"
          else -> "audio/*"
        }

        val values = ContentValues().apply {
          put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
          put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
          put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Annieplayer")
          put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val newUri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        if (newUri != null) {
          FileInputStream(file).use { input ->
            context.contentResolver.openOutputStream(newUri)?.use { output ->
              input.copyTo(output)
            }
          }
          values.clear()
          values.put(MediaStore.Audio.Media.IS_PENDING, 0)
          context.contentResolver.update(newUri, values, null, null)
        }
        newUri?.toString()
      } else {
        android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        null
      }
    }
  }
}
