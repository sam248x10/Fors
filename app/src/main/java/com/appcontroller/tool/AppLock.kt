package com.appcontroller.tool

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal class AppLock(context: Context) {
    private val prefs = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    val enabled: Boolean get() = prefs.contains("password")
    val hasOneTimeCode: Boolean get() = prefs.contains("one_time")
    val isCoolingDown: Boolean get() {
        val remaining = prefs.getLong("retry_at", 0) - SystemClock.elapsedRealtime()
        return remaining in 1..30_000L
    }

    fun setPassword(password: String): Boolean =
        prefs.edit().putString("password", encode(password)).remove("one_time")
            .remove("failures").remove("retry_at").commit()

    fun issueOneTimeCode(): String? {
        val code = List(12) { random.nextInt(10) }.joinToString("")
        return code.takeIf { prefs.edit().putString("one_time", encode(code)).commit() }
    }

    fun disable(): Boolean = prefs.edit().clear().commit()

    fun verify(input: String, permanentOnly: Boolean = false): Boolean {
        if (isCoolingDown || input.length !in 1..128) return false
        val permanent = matches(input, prefs.getString("password", null))
        val oneTime = !permanent && !permanentOnly && matches(input, prefs.getString("one_time", null))
        val edit = prefs.edit()
        if (permanent || oneTime) {
            if (oneTime) edit.remove("one_time")
            return edit.remove("failures").remove("retry_at").commit()
        }
        val failures = prefs.getInt("failures", 0) + 1
        if (failures >= 5) edit.putInt("failures", 0).putLong("retry_at", SystemClock.elapsedRealtime() + 30_000)
        else edit.putInt("failures", failures)
        edit.apply()
        return false
    }

    private fun encode(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val algorithm = if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
        return listOf(algorithm, Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(derive(password, salt, algorithm), Base64.NO_WRAP)).joinToString(":")
    }

    private fun matches(input: String, record: String?): Boolean {
        val fields = record?.split(':') ?: return false
        if (fields.size != 3) return false
        val salt = Base64.decode(fields[1], Base64.NO_WRAP)
        val expected = Base64.decode(fields[2], Base64.NO_WRAP)
        return MessageDigest.isEqual(expected, derive(input, salt, fields[0]))
    }

    private fun derive(password: String, salt: ByteArray, algorithm: String): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
        try {
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
