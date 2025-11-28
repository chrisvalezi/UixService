package com.chris.uix

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

object CommandServer {

    @Volatile
    private var running = false

    private val executor = Executors.newCachedThreadPool()

    fun start(service: UixAccessibilityService, port: Int = 9001) {
        if (running) return
        running = true

        executor.execute {
            try {
                val localhost = InetAddress.getByName("127.0.0.1")
                val server = ServerSocket(port, 8, localhost)
                Log.i("UixCommandServer", "Listening on 127.0.0.1:$port")
                while (running) {
                    val client = server.accept()
                    executor.execute {
                        handleClient(service, client)
                    }
                }
                server.close()
            } catch (e: Exception) {
                Log.e("UixCommandServer", "Error in server", e)
                running = false
            }
        }
    }

    private fun handleClient(service: UixAccessibilityService, socket: Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val writer = PrintWriter(s.getOutputStream(), true)

            val line = reader.readLine() ?: return
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return

            val parts = trimmed.split(" ", limit = 2)
            val cmd = parts[0].uppercase()
            val arg = if (parts.size > 1) parts[1] else ""

            when (cmd) {
                // ---------- DUMP ----------
                "DUMP" -> {
                    val root = UixAccessibilityService.getLastRoot()
                    val json = UixAccessibilityService.nodeToJson(root)
                    writer.println(json.toString())
                }

                // ---------- FIND / CLICK POR TEXTO ----------
                "FIND_TEXT" -> {
                    val root = UixAccessibilityService.getLastRoot()
                    val node = UixAccessibilityService.findNodeByText(root, arg)
                    val resp = JSONObject()
                    if (node != null) {
                        resp.put("found", true)
                        resp.put("node", UixAccessibilityService.nodeToJson(node))
                        node.recycle()
                    } else {
                        resp.put("found", false)
                    }
                    writer.println(resp.toString())
                }

                "CLICK_TEXT" -> {
                    val root = UixAccessibilityService.getLastRoot()
                    val node = UixAccessibilityService.findNodeByText(root, arg)
                    val resp = JSONObject()
                    if (node != null) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        node.recycle()
                        val cx = (rect.left + rect.right) / 2
                        val cy = (rect.top + rect.bottom) / 2
                        val ok = service.performClick(cx, cy, 80L)
                        resp.put("ok", ok)
                        resp.put("x", cx)
                        resp.put("y", cy)
                    } else {
                        resp.put("ok", false)
                        resp.put("error", "node_not_found")
                    }
                    writer.println(resp.toString())
                }

                // ---------- FIND / CLICK POR VIEW ID ----------
                "FIND_ID" -> {
                    val root = UixAccessibilityService.getLastRoot()
                    val node = UixAccessibilityService.findNodeByViewId(root, arg)
                    val resp = JSONObject()
                    if (node != null) {
                        resp.put("found", true)
                        resp.put("node", UixAccessibilityService.nodeToJson(node))
                        node.recycle()
                    } else {
                        resp.put("found", false)
                    }
                    writer.println(resp.toString())
                }

                "CLICK_ID" -> {
                    val root = UixAccessibilityService.getLastRoot()
                    val node = UixAccessibilityService.findNodeByViewId(root, arg)
                    val resp = JSONObject()
                    if (node != null) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        node.recycle()
                        val cx = (rect.left + rect.right) / 2
                        val cy = (rect.top + rect.bottom) / 2
                        val ok = service.performClick(cx, cy, 80L)
                        resp.put("ok", ok)
                        resp.put("x", cx)
                        resp.put("y", cy)
                    } else {
                        resp.put("ok", false)
                        resp.put("error", "node_not_found")
                    }
                    writer.println(resp.toString())
                }

                // ---------- SET TEXT POR ID ----------
                // SET_TEXT_ID <view_id> <texto>
                "SET_TEXT_ID" -> {
                    val firstSpace = arg.indexOf(' ')
                    val resp = JSONObject()
                    if (firstSpace <= 0) {
                        resp.put("ok", false)
                        resp.put("error", "usage: SET_TEXT_ID <view_id> <text>")
                    } else {
                        val id = arg.substring(0, firstSpace)
                        val text = arg.substring(firstSpace + 1)
                        val root = UixAccessibilityService.getLastRoot()
                        val node = UixAccessibilityService.findNodeByViewId(root, id)
                        if (node != null) {
                            val ok = UixAccessibilityService.setTextOnNode(node, text)
                            node.recycle()
                            resp.put("ok", ok)
                            if (!ok) resp.put("error", "action_failed")
                        } else {
                            resp.put("ok", false)
                            resp.put("error", "node_not_found")
                        }
                    }
                    writer.println(resp.toString())
                }

                // ---------- GLOBAL ACTIONS ----------
                // GLOBAL BACK | HOME | RECENTS | NOTIFICATIONS | QUICK_SETTINGS
                "GLOBAL" -> {
                    val action = arg.uppercase()
                    val resp = JSONObject()
                    val ok = when (action) {
                        "BACK" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        "HOME" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        "RECENTS" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                        "NOTIFICATIONS" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                        "QUICK_SETTINGS" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                        else -> {
                            resp.put("ok", false)
                            resp.put("error", "unknown_global_action")
                            writer.println(resp.toString())
                            false
                        }
                    }
                    if (ok) {
                        resp.put("ok", true)
                        resp.put("action", action)
                        writer.println(resp.toString())
                    }
                }

                // ---------- SWIPE x1 y1 x2 y2 [durationMs] ----------
                "SWIPE" -> {
                    val tokens = arg.split(" ")
                        .filter { it.isNotBlank() }
                    val resp = JSONObject()
                    if (tokens.size < 4) {
                        resp.put("ok", false)
                        resp.put("error", "usage: SWIPE x1 y1 x2 y2 [durationMs]")
                    } else {
                        try {
                            val x1 = tokens[0].toInt()
                            val y1 = tokens[1].toInt()
                            val x2 = tokens[2].toInt()
                            val y2 = tokens[3].toInt()
                            val duration = if (tokens.size >= 5) tokens[4].toLong() else 300L
                            val ok = service.performSwipe(x1, y1, x2, y2, duration)
                            resp.put("ok", ok)
                            resp.put("x1", x1)
                            resp.put("y1", y1)
                            resp.put("x2", x2)
                            resp.put("y2", y2)
                            resp.put("duration", duration)
                        } catch (e: Exception) {
                            resp.put("ok", false)
                            resp.put("error", "invalid_arguments")
                        }
                    }
                    writer.println(resp.toString())
                }

                // ---------- WAIT_TEXT <texto> <timeoutMs> ----------
                "WAIT_TEXT" -> {
                    val tokens = arg.split(" ", limit = 2)
                    val resp = JSONObject()
                    if (tokens.size < 2) {
                        resp.put("ok", false)
                        resp.put("error", "usage: WAIT_TEXT <text> <timeoutMs>")
                    } else {
                        val text = tokens[0]
                        val timeoutMs = tokens[1].toLongOrNull() ?: 5000L
                        val node = UixAccessibilityService.waitForNodeByText(timeoutMs, text)
                        if (node != null) {
                            resp.put("ok", true)
                            resp.put("node", UixAccessibilityService.nodeToJson(node))
                            node.recycle()
                        } else {
                            resp.put("ok", false)
                            resp.put("error", "timeout")
                        }
                    }
                    writer.println(resp.toString())
                }

                // ---------- WAIT_ID <view_id> <timeoutMs> ----------
                "WAIT_ID" -> {
                    val tokens = arg.split(" ", limit = 2)
                    val resp = JSONObject()
                    if (tokens.size < 2) {
                        resp.put("ok", false)
                        resp.put("error", "usage: WAIT_ID <view_id> <timeoutMs>")
                    } else {
                        val id = tokens[0]
                        val timeoutMs = tokens[1].toLongOrNull() ?: 5000L
                        val node = UixAccessibilityService.waitForNodeById(timeoutMs, id)
                        if (node != null) {
                            resp.put("ok", true)
                            resp.put("node", UixAccessibilityService.nodeToJson(node))
                            node.recycle()
                        } else {
                            resp.put("ok", false)
                            resp.put("error", "timeout")
                        }
                    }
                    writer.println(resp.toString())
                }

                // ---------- DEFAULT ----------
                else -> {
                    val resp = JSONObject()
                    resp.put("ok", false)
                    resp.put("error", "unknown_command")
                    writer.println(resp.toString())
                }
            }
        }
    }
}
