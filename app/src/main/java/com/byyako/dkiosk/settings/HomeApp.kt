package com.byyako.dkiosk.settings

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Optional home-screen mode. The HOME intent filter sits on a disabled activity alias so that
 * installing dKiosk never makes Android ask which home app to use; turning the setting on enables
 * the alias, and the user then picks dKiosk in Android's own home app setting.
 */
object HomeApp {

    private fun alias(context: Context) = ComponentName(context, "com.byyako.dkiosk.HomeScreen")

    fun isOffered(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setOffered(context: Context, offered: Boolean) {
        val state = if (offered) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(alias(context), state, PackageManager.DONT_KILL_APP)
    }

    fun isDefault(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    fun openHomeSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            // Some ROMs don't have a dedicated home app screen.
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
