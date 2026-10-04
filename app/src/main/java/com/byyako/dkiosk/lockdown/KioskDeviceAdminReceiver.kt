package com.byyako.dkiosk.lockdown

import android.app.admin.DeviceAdminReceiver

/** Provisioned as Device Owner only on dedicated devices; ordinary installs need no admin rights. */
class KioskDeviceAdminReceiver : DeviceAdminReceiver()
