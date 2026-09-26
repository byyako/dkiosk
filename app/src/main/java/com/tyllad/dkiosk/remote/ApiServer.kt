package com.tyllad.dkiosk.remote

import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Serves [KioskApi] on [port] on every network interface. Once stopped, make a new one to start again. */
class ApiServer(val port: Int, private val api: KioskApi) {

    // Two workers and a short queue: plenty for a few automations, and a flood can't pile up forever.
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16))
    private var socket: ServerSocket? = null

    /** Throws if the port can't be opened, e.g. because another app is using it. */
    fun start() {
        val server = ServerSocket(port)
        socket = server
        thread(name = "api-server", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break // closed by stop()
                }
                try {
                    workers.execute { serve(client) }
                } catch (_: RejectedExecutionException) {
                    client.close()
                }
            }
        }
    }

    fun stop() {
        socket?.close()
        socket = null
        workers.shutdownNow()
    }

    private fun serve(client: Socket): Unit = client.use {
        it.soTimeout = 5_000
        val response = try {
            api.handle(HttpRequest.read(it.getInputStream()))
        } catch (e: BadRequestException) {
            HttpResponse.error(400, e.message.orEmpty())
        } catch (e: IOException) {
            return // timed out or the client went away
        } catch (e: Exception) {
            Log.w(TAG, "API request failed", e)
            HttpResponse.error(500, "Internal error")
        }
        try {
            response.write(it.getOutputStream())
        } catch (_: IOException) {
            // Client gone; nothing to do.
        }
    }

    private companion object {
        const val TAG = "ApiServer"
    }
}
