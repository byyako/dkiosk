package com.byyako.dkiosk.remote

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/** The device's IPv4 address on its current network, for showing where the API can be reached. */
fun localIpAddress(context: Context): String? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val links = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return null
    return links.linkAddresses
        .map { it.address }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
}
