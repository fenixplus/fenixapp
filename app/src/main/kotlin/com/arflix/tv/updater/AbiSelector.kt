package com.arflix.tv.updater

import android.os.Build
import com.google.gson.annotations.SerializedName

data class GitHubAssetDto(
    @SerializedName("name") val name: String,
    @SerializedName("browser_download_url") val browserDownloadUrl: String,
    @SerializedName("size") val size: Long?
)

object AbiSelector {
    private val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun chooseBestApkAsset(assets: List<GitHubAssetDto>): GitHubAssetDto? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null
        if (apkAssets.size == 1) return apkAssets.first()

        val supported = Build.SUPPORTED_ABIS?.toList().orEmpty()
        for (abi in supported) {
            val candidate = apkAssets.firstOrNull { it.name.contains(abi, ignoreCase = true) }
            if (candidate != null) return candidate
        }

        val universal = apkAssets.firstOrNull {
            val name = it.name.lowercase()
            name.contains("universal") || name.contains("all") || name.contains("release")
        }
        if (universal != null) return universal

        val noAbiMention = apkAssets.firstOrNull { asset ->
            knownAbis.none { abi -> asset.name.contains(abi, ignoreCase = true) }
        }
        return noAbiMention ?: apkAssets.first()
    }
}
