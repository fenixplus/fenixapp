package com.arflix.tv.player.telegram

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.arflix.tv.data.telegram.TelegramClient
import org.drinkless.tdlib.TdApi
import java.io.File

/**
 * Hands one Telegram video to an external player app (VLC, MX Player, Just Player, etc.)
 * directly via [FileProvider] (`content://...`) without needing a local HTTP proxy server.
 */
object ExternalPlayer {

    private const val TAG = "ExternalPlayer"

    enum class Readiness {
        Complete,
        Partial,
        Nothing,
    }

    sealed interface Handoff {
        data class Started(val caution: String?) : Handoff
        data object NothingOnDisk : Handoff
        data class Refused(val reason: String) : Handoff
    }

    fun chooserFor(context: Context, path: String, title: String, mimeType: String): Intent? {
        val file = File(path)
        if (!file.isFile || file.length() <= 0) return null
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure { Log.w(TAG, "Cannot hand out $path via FileProvider", it) }.getOrNull() ?: return null

        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType.ifBlank { "video/*" })
            .putExtra(Intent.EXTRA_TITLE, title)
            .putExtra("title", title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return Intent.createChooser(view, "Abrir com...").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun handOver(
        context: Context,
        client: TelegramClient,
        fileId: Int,
        title: String,
        mimeType: String = "video/*",
    ): Handoff {
        if (fileId <= 0) return Handoff.NothingOnDisk
        val file = client.getFile(fileId) ?: return Handoff.NothingOnDisk
        val local = file.local

        val path = local?.path?.takeIf { it.isNotBlank() }
        val diskFile = path?.let { File(it) }
        val downloaded = local?.downloadedSize ?: 0L

        if (diskFile == null || !diskFile.exists() || diskFile.length() <= 0) {
            // Trigger download so external player will soon have bytes
            client.downloadFile(fileId = fileId, priority = 32, offset = 0, limit = 0, synchronous = false)
            val msg = "Iniciando download no Telegram. Tente novamente em alguns segundos para abrir no player externo."
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            return Handoff.NothingOnDisk
        }

        val chooser = chooserFor(context, path, title, mimeType)
            ?: return Handoff.Refused("Não foi possível gerar URI do arquivo")

        return runCatching {
            context.startActivity(chooser)
            val caution = if (!local.isDownloadingCompleted) {
                "Reproduzindo arquivo em download (${downloaded / (1024 * 1024)} MB baixados)"
            } else null
            Handoff.Started(caution)
        }.getOrElse {
            Log.w(TAG, "Nenhum aplicativo externo aceitou o vídeo", it)
            Handoff.Refused("Nenhum player externo compatível encontrado")
        }
    }
}
