package com.appcontroller.tool

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID

internal object StopSchedules {
    data class Entry(val id: String, val name: String, val target: String, val group: Boolean, val minutes: Int)

    private fun prefs(context: Context) = context.getSharedPreferences("stop_schedules", Context.MODE_PRIVATE)

    fun list(context: Context): List<Entry> {
        val entries = JSONObject(prefs(context).getString("entries", "{}") ?: "{}")
        return entries.keys().asSequence().map { id ->
            val entry = entries.getJSONObject(id)
            Entry(id, entry.getString("name"), entry.getString("target"), entry.getBoolean("group"), entry.getInt("minutes"))
        }.sortedBy { it.name }.toList()
    }

    fun add(context: Context, name: String, target: String, group: Boolean, minutes: Int) {
        val entry = Entry(UUID.randomUUID().toString(), name, target, group, minutes)
        save(context, list(context) + entry)
        arm(context, entry)
    }

    fun remove(context: Context, entry: Entry) {
        save(context, list(context).filter { it.id != entry.id })
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(context, entry.id))
    }

    fun restore(context: Context) {
        list(context).forEach { arm(context, it) }
    }

    private fun save(context: Context, entries: List<Entry>) {
        val data = JSONObject()
        for (entry in entries) {
            data.put(entry.id, JSONObject()
                .put("name", entry.name).put("target", entry.target)
                .put("group", entry.group).put("minutes", entry.minutes))
        }
        prefs(context).edit().putString("entries", data.toString()).apply()
    }

    private fun arm(context: Context, entry: Entry) {
        val interval = entry.minutes * 60_000L
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + interval, interval, pending(context, entry.id))
    }

    private fun pending(context: Context, id: String): PendingIntent {
        val intent = Intent(context, StopScheduleReceiver::class.java)
            .setAction("com.appcontroller.tool.RUN_SCHEDULE")
            .setData(android.net.Uri.fromParts("fors-schedule", id, null))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }
}

class StopScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            StopSchedules.restore(context)
            return
        }
        if (intent.action != "com.appcontroller.tool.RUN_SCHEDULE") return
        val id = intent.data?.schemeSpecificPart ?: return
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!power.isInteractive || keyguard.isKeyguardLocked) return
        val entry = StopSchedules.list(context).firstOrNull { it.id == id } ?: return
        val packages = if (entry.group) {
            val prefs = context.getSharedPreferences("app_selection", Context.MODE_PRIVATE)
            val groups = JSONObject(prefs.getString("app_groups", "{}") ?: "{}")
            val group = groups.optJSONArray(entry.target) ?: return
            (0 until group.length()).map { group.getString(it) }.toSet()
        } else {
            setOf(entry.target)
        }
        ForceStopAccessibilityService.enqueueScheduled(packages)
    }
}
