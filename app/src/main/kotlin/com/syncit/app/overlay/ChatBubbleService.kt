package com.syncit.app.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.syncit.app.SyncItApplication
import com.syncit.app.ui.room.REACTION_EMOJIS
import com.syncit.core.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Optional floating chat bubble drawn over the streaming app (SYSTEM_ALERT_WINDOW).
 * Collapsed: a small draggable 💬 with an unread count. Tapped: last few messages, a text box
 * and reaction buttons. Plain Views: a Compose window outside an Activity needs extra
 * lifecycle plumbing that isn't worth it for this.
 *
 * Runs while the sync foreground service keeps the process in the foreground.
 */
class ChatBubbleService : Service() {

    private val scope = MainScope()
    private lateinit var windowManager: WindowManager
    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var messagesView: TextView
    private lateinit var input: EditText

    private var roomCode: String? = null
    private var chatJob: Job? = null
    private var messages: List<ChatMessage> = emptyList()
    private var seenCount = 0
    private var expanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getStringExtra(EXTRA_ROOM_CODE) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) showBubble()
        if (code != roomCode) observeChat(code)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        scope.cancel()
        super.onDestroy()
    }

    private val container get() = (application as SyncItApplication).container

    private fun observeChat(code: String) {
        roomCode = code
        chatJob?.cancel()
        chatJob = scope.launch {
            container.transport.observeChat(code, limit = 20)
                .catch { }
                .collect { list ->
                    messages = list
                    if (expanded) seenCount = list.size
                    render()
                }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showBubble() {
        windowManager = getSystemService(WindowManager::class.java)

        bubble = TextView(this).apply {
            text = "💬"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6C2410C.toInt()) // Sunset accent, white text 5.2:1
            }
            val size = dp(56)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

        messagesView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 8
        }
        input = EditText(this).apply {
            hint = "Message"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send()
                    true
                } else {
                    false
                }
            }
        }
        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener { send() }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton)
        }
        val reactionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (emoji in REACTION_EMOJIS) {
                addView(TextView(this@ChatBubbleService).apply {
                    text = emoji
                    textSize = 22f
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setOnClickListener { react(emoji) }
                })
            }
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xE6202020.toInt())
            }
            addView(messagesView)
            addView(reactionsRow)
            addView(inputRow)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubble)
            addView(panel, LinearLayout.LayoutParams(dp(280), LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Collapsed: never steal focus or touches outside the bubble from the movie app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(160)
        }

        bubble.setOnTouchListener(DragListener())
        windowManager.addView(root, params)
        render()
    }

    /** Drag to move; a touch that doesn't move is a tap that toggles the panel. */
    private inner class DragListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@ChatBubbleService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        root?.let { windowManager.updateViewLayout(it, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) toggle()
            }
            return true
        }
    }

    private fun toggle() {
        expanded = !expanded
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        // The text box needs focus to type; only take it while the panel is open.
        params.flags = if (expanded) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (expanded) seenCount = messages.size
        root?.let { windowManager.updateViewLayout(it, params) }
        render()
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        if (root == null) return
        val unread = (messages.size - seenCount).coerceAtLeast(0)
        bubble.text = if (unread > 0 && !expanded) "💬$unread" else "💬"
        messagesView.text = messages.takeLast(6).joinToString("\n") { "${it.name}: ${it.text}" }
            .ifEmpty { "No messages yet" }
    }

    private fun send() {
        val code = roomCode ?: return
        val text = input.text.toString()
        if (text.isBlank()) return
        input.setText("")
        launchSafely { container.transport.sendChat(code, container.prefs.displayName, text) }
    }

    private fun react(emoji: String) {
        val code = roomCode ?: return
        launchSafely { container.transport.sendReaction(code, container.prefs.displayName, emoji) }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: Firebase queues the write; nothing useful to show in the bubble.
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ACTION_STOP = "com.syncit.app.BUBBLE_STOP"
        private const val EXTRA_ROOM_CODE = "room_code"

        fun start(context: Context, roomCode: String) {
            context.startService(
                Intent(context, ChatBubbleService::class.java).putExtra(EXTRA_ROOM_CODE, roomCode),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChatBubbleService::class.java))
        }
    }
}
