package com.arflix.tv.player.telegram

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Utilitário para abrir vídeos em players externos instalados (VLC, MX Player, Just Player, MPV, etc.).
 *
 * Suporta:
 * 1. Arquivos locais 100% baixados via [FileProvider] (`content://...`).
 * 2. Streaming online sob demanda via URL HTTP (local proxy Ktor com suporte a Range requests),
 *    garantindo que o usuário consiga avançar e retroceder sem que o player feche por EOF.
 * 3. Abertura na mesma pilha de tarefas (Same Task / sem janela fantasma nos apps recentes).
 */
object ExternalPlayer {

    private const val TAG = "ExternalPlayer"

    /**
     * Cria a Intent para reprodução direta em player externo (VLC, MX Player, Just Player, etc.).
     *
     * Não utiliza `Intent.createChooser`, permitindo que o Android exiba seu diálogo nativo
     * do sistema ("Abrir com...", "Só uma vez" / "Sempre"), empilhando o player externo
     * diretamente sobre a tarefa do Fênix (mesmo comportamento do Telegram / Nagram).
     */
    fun createPlayIntent(
        context: Context,
        uri: Uri,
        title: String,
        mimeType: String = "video/*",
        positionMs: Long = 0L,
    ): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "video/*" })
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra("title", title)
            putExtra("sticky_style", false)
            if (positionMs > 0L) {
                putExtra("position", positionMs.toInt()) // MX Player e Just Player usam ms (int)
                putExtra("start-time", positionMs)       // VLC usa ms (long)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Apenas adiciona NEW_TASK se o contexto NÃO for uma Activity (ex: Service ou Application)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Abre um arquivo físico local já baixado 100% via FileProvider.
     */
    fun launchFile(
        context: Context,
        file: File,
        title: String,
        mimeType: String = "video/*",
        positionMs: Long = 0L,
    ): Boolean {
        if (!file.isFile || file.length() <= 0) {
            Log.w(TAG, "Arquivo inválido ou vazio para reprodução externa: $file")
            return false
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure { Log.w(TAG, "Cannot hand out $file via FileProvider", it) }.getOrNull() ?: return false

        val intent = createPlayIntent(context, uri, title, mimeType, positionMs)
        return tryLaunch(context, intent)
    }

    /**
     * Abre uma URL (como o proxy HTTP local http://127.0.0.1:porta/... ou stream web).
     */
    fun launchUrl(
        context: Context,
        url: String,
        title: String,
        mimeType: String = "video/*",
        positionMs: Long = 0L,
    ): Boolean {
        if (url.isBlank()) return false
        val uri = Uri.parse(url)
        val intent = createPlayIntent(context, uri, title, mimeType, positionMs)
        return tryLaunch(context, intent)
    }

    private fun tryLaunch(context: Context, intent: Intent): Boolean {
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "Falha ao iniciar player diretamente, tentando com chooser nativo", it)
            runCatching {
                val chooser = Intent.createChooser(intent, "Abrir com...")
                if (context !is Activity) {
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                true
            }.getOrElse {
                Log.w(TAG, "Nenhum aplicativo externo aceitou o vídeo", it)
                Toast.makeText(context, "Nenhum player externo compatível encontrado.", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }
}
