package com.chris.uix

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class UixAccessibilityService : AccessibilityService() {

    companion object {
        private val lastRootRef = AtomicReference<AccessibilityNodeInfo?>()

        fun getLastRoot(): AccessibilityNodeInfo? {
            return lastRootRef.get()
        }

        fun setLastRoot(root: AccessibilityNodeInfo?) {
            if (root == null) {
                lastRootRef.set(null)
            } else {
                lastRootRef.set(AccessibilityNodeInfo.obtain(root))
            }
        }

        // ---------- SERIALIZAÇÃO PARA JSON ----------

        fun nodeToJson(node: AccessibilityNodeInfo?): JSONObject {
            val obj = JSONObject()
            if (node == null) return obj

            obj.put("text", node.text?.toString() ?: "")
            obj.put("content_desc", node.contentDescription?.toString() ?: "")
            obj.put("view_id", node.viewIdResourceName ?: "")
            obj.put("class_name", node.className?.toString() ?: "")
            obj.put("package_name", node.packageName?.toString() ?: "")
            obj.put("clickable", node.isClickable)
            obj.put("enabled", node.isEnabled)
            obj.put("focusable", node.isFocusable)
            obj.put("checked", node.isChecked)
            obj.put("editable", node.isEditable)

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val boundsJson = JSONObject()
            boundsJson.put("left", rect.left)
            boundsJson.put("top", rect.top)
            boundsJson.put("right", rect.right)
            boundsJson.put("bottom", rect.bottom)
            obj.put("bounds", boundsJson)

            val children = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    children.put(nodeToJson(child))
                    child.recycle()
                }
            }
            obj.put("children", children)

            return obj
        }

        // ---------- BUSCAS ----------

        fun findNodeByText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (root == null) return null
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(AccessibilityNodeInfo.obtain(root))

            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()

                val nText = node.text?.toString() ?: ""
                val cDesc = node.contentDescription?.toString() ?: ""

                if (nText.contains(text, ignoreCase = true) ||
                    cDesc.contains(text, ignoreCase = true)
                ) {
                    val result = AccessibilityNodeInfo.obtain(node)
                    node.recycle()
                    queue.forEach { it.recycle() }
                    return result
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        queue.add(child)
                    }
                }

                node.recycle()
            }
            return null
        }

        fun findNodeByViewId(root: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
            if (root == null) return null
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(AccessibilityNodeInfo.obtain(root))

            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()

                val id = node.viewIdResourceName ?: ""
                if (id == viewId) {
                    val result = AccessibilityNodeInfo.obtain(node)
                    node.recycle()
                    queue.forEach { it.recycle() }
                    return result
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        queue.add(child)
                    }
                }

                node.recycle()
            }
            return null
        }

        fun waitForNodeByText(timeoutMs: Long, text: String): AccessibilityNodeInfo? {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                val root = getLastRoot()
                val node = findNodeByText(root, text)
                if (node != null) return node
                SystemClock.sleep(100)
            }
            return null
        }

        fun waitForNodeById(timeoutMs: Long, id: String): AccessibilityNodeInfo? {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                val root = getLastRoot()
                val node = findNodeByViewId(root, id)
                if (node != null) return node
                SystemClock.sleep(100)
            }
            return null
        }

        fun setTextOnNode(node: AccessibilityNodeInfo?, text: String): Boolean {
            if (node == null) return false
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        CommandServer.start(this, 9001)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow
        if (root != null) {
            setLastRoot(root)
            root.recycle()
        }
    }

    override fun onInterrupt() {
        // nada
    }

    fun performClick(x: Int, y: Int, durationMs: Long = 80L): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var result = false
        val latch = CountDownLatch(1)

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                result = false
                latch.countDown()
            }
        }, null)

        latch.await()
        return result
    }

    fun performSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long = 300L
    ): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var result = false
        val latch = CountDownLatch(1)

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                result = false
                latch.countDown()
            }
        }, null)

        latch.await()
        return result
    }
}
