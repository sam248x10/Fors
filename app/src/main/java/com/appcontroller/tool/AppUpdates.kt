package com.appcontroller.tool

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object AppUpdates {
    const val RELEASES_PAGE = "https://github.com/sam248x10/Fors/releases/latest"
    private const val API = "https://api.github.com/repos/sam248x10/Fors/releases/latest"

    fun newerVersion(installedVersion: String): String? {
        val connection = URL(API).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (status != HttpURLConnection.HTTP_OK) throw IOException("GitHub returned HTTP $status")
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = release.optString("tag_name").removePrefix("v")
            val hasApk = release.optJSONArray("assets")?.let { assets ->
                (0 until assets.length()).any { index ->
                    assets.optJSONObject(index)?.optString("name")?.endsWith(".apk", ignoreCase = true) == true
                }
            } ?: false
            return tag.takeIf { hasApk && isNewer(it, installedVersion) }
        } finally {
            connection.disconnect()
        }
    }

    private fun isNewer(candidate: String, installed: String): Boolean {
        val latest = parseVersion(candidate) ?: return false
        val current = parseVersion(installed) ?: return false
        for (index in 0 until maxOf(latest.size, current.size)) {
            val comparison = latest.getOrElse(index) { 0 }.compareTo(current.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    private fun parseVersion(version: String): List<Int>? =
        version.split('.').map { it.toIntOrNull() ?: return null }
}
