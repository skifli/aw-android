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

    // To check if the hostname migration has already been run
    fun hasMigratedHostname(): Boolean {
        return sharedPreferences.getBoolean("hasMigratedHostname", false)
    }

    // To mark the hostname migration as done so it won't run again
    fun setHostnameMigrated() {
        sharedPreferences.edit().putBoolean("hasMigratedHostname", true).apply()
    }

    // Whether to run the embedded Rust server inside the app
    fun useEmbeddedServer(): Boolean {
        return sharedPreferences.getBoolean("useEmbeddedServer", false)
    }

    fun setUseEmbeddedServer(value: Boolean) {
        sharedPreferences.edit().putBoolean("useEmbeddedServer", value).apply()
    }

    // Remote server hostname (if empty, device name will be used)
    fun getRemoteServerHost(): String {
        return sharedPreferences.getString("remoteServerHost", "pifi") ?: "pifi"
    }

    fun setRemoteServerHost(host: String) {
        sharedPreferences.edit().putString("remoteServerHost", host).apply()
    }

    // Remote server port (default 5600)
    fun getRemoteServerPort(): Int {
        return sharedPreferences.getInt("remoteServerPort", 5600)
    }

    fun setRemoteServerPort(port: Int) {
        sharedPreferences.edit().putInt("remoteServerPort", port).apply()
    }
}
