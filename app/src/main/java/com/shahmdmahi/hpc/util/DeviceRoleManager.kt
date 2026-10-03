package com.shahmdmahi.hpc.util

import android.content.Context

enum class DeviceRole(val title: String, val path: String) {
    WAITING_ROOM_TV("Waiting Room TV Queue", "/"),
    RECEPTIONIST("Reception Counter", "/receptionist"),
    DOCTOR("Doctor Desk", "/doctor"),
    HANDLER("Therapy Floor", "/handler"),
    CASHIER("Cashier Counter", "/cashier"),
    ADMIN("Admin Desk", "/admin"),
    DEFAULT("Waiting Room TV Queue", "/")
}

object DeviceRoleManager {
    private const val PREFS_NAME = "hpc_device_role_prefs"
    private const val KEY_DEVICE_ROLE = "selected_device_role"

    fun getSavedRole(context: Context): DeviceRole {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val roleName = prefs.getString(KEY_DEVICE_ROLE, DeviceRole.WAITING_ROOM_TV.name)
        return try {
            val role = DeviceRole.valueOf(roleName ?: DeviceRole.WAITING_ROOM_TV.name)
            if (role.name == "DEFAULT" || role.path == "/login") {
                DeviceRole.WAITING_ROOM_TV
            } else {
                role
            }
        } catch (e: Exception) {
            DeviceRole.WAITING_ROOM_TV
        }
    }

    fun saveRole(context: Context, role: DeviceRole) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEVICE_ROLE, role.name).apply()
    }

    fun buildFullTargetUrl(baseUrl: String, role: DeviceRole): String {
        val cleanBase = baseUrl.trimEnd('/')
        val path = role.path
        return if (path == "/") cleanBase else "$cleanBase$path"
    }
}
