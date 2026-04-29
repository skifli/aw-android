package net.activitywatch.android

import android.content.Context
import android.content.SharedPreferences

class AWPreferences(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("AWPreferences", Context.MODE_PRIVATE)

    // To check if it is the first time the app is being run
    // Set to false when user finishes onboarding
    fun isFirstTime(): Boolean {
        return sharedPreferences.getBoolean("isFirstTime", true)
    }

    // To set the first time flag to false after the first run
    fun setFirstTimeRunFlag() {
        val editor = sharedPreferences.edit()
        editor.putBoolean("isFirstTime", false)
        editor.apply()
    }

    // Optional: To reset the first time flag to true (for debugging, perhaps)
    fun resetFirstTimeRunFlag() {
        val editor = sharedPreferences.edit()
        editor.putBoolean("isFirstTime", true)
        editor.apply()
    }

    // Remote server hostname
    fun getRemoteServerHost(): String {
        val rawValue = sharedPreferences.getString("remoteServerHost", "100.120.18.23") ?: "100.120.18.23"
        return normalizeRemoteHost(rawValue)
    }

    fun setRemoteServerHost(host: String) {
        sharedPreferences.edit().putString("remoteServerHost", normalizeRemoteHost(host)).apply()
    }

    // Remote server port (default 5600)
    fun getRemoteServerPort(): Int {
        return sharedPreferences.getInt("remoteServerPort", 5600)
    }

    fun setRemoteServerPort(port: Int) {
        sharedPreferences.edit().putInt("remoteServerPort", port).apply()
    }

    private fun normalizeRemoteHost(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed

        val withoutScheme = trimmed.removePrefix("http://").removePrefix("https://")
        val hostPortAndPath = withoutScheme.substringBefore("/")
        val host = hostPortAndPath.substringBefore(":")

        return host.ifEmpty { trimmed }
    }
}
