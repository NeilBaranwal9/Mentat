package com.polymath.os.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.polymath.os.domain.DispatcherProvider
import com.polymath.os.domain.newId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proof photos and SAF file access. Everything runs on Dispatchers.IO (Rule 1).
 * Photos stay on the device and are never sent to any model.
 */
@Singleton
class FileStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dispatchers: DispatcherProvider,
) {
    val proofDir: File get() = File(ctx.filesDir, "proofs").apply { mkdirs() }

    /** Copies a Photo Picker image into app storage, downscaled to max 1600 px, JPEG 80. Returns the file name. */
    suspend fun importProof(uri: Uri): String = withContext(dispatchers.io) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Could not read image")
        val name = "${newId()}.jpg"
        File(proofDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bmp.recycle()
        name
    }

    fun proofFile(name: String): File = File(proofDir, name)

    fun shareUri(name: String): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", proofFile(name))

    suspend fun deleteProof(name: String) = withContext(dispatchers.io) { proofFile(name).delete(); Unit }

    suspend fun readText(uri: Uri, maxBytes: Int = 5_000_000): String = withContext(dispatchers.io) {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= maxBytes) { "File is too large" }
            String(bytes, Charsets.UTF_8)
        } ?: error("Could not open file")
    }

    suspend fun <T> withOutput(uri: Uri, block: suspend (OutputStream) -> T): T = withContext(dispatchers.io) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { block(it) } ?: error("Could not open file for writing")
    }

    suspend fun <T> withInput(uri: Uri, block: suspend (InputStream) -> T): T = withContext(dispatchers.io) {
        ctx.contentResolver.openInputStream(uri)?.use { block(it) } ?: error("Could not open file")
    }
}
