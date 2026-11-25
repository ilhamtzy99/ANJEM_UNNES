package com.example.anjemgogogo.ui.chat

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.anjemgogogo.R
import com.example.anjemgogogo.data.firebase.FirebaseRepository
import com.example.anjemgogogo.data.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared chat bubble renderer used by both ChatFragment and OrderFragment overlay chat.
 * Aligns current user's sent messages on the right and peer messages on the left.
 */
object ChatBubbleHelper {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun renderMessages(
        context: Context,
        container: LinearLayout,
        scrollView: ScrollView,
        messages: List<ChatMessage>,
        keepFirstChildCount: Int = 0
    ) {
        while (container.childCount > keepFirstChildCount) {
            container.removeViewAt(container.childCount - 1)
        }

        val currentUid = FirebaseRepository.currentUid()
        for (msg in messages) {
            val isMine = if (currentUid != null) {
                msg.senderId == currentUid
            } else {
                msg.senderRole == "customer"
            }
            container.addView(createBubbleView(context, msg, isMine))
        }

        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun createBubbleView(context: Context, msg: ChatMessage, isMine: Boolean): View {
        val formattedTime = timeFormat.format(Date(if (msg.timestamp > 0) msg.timestamp else System.currentTimeMillis()))

        val bubbleLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isMine) Gravity.END else Gravity.START
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isMine) Gravity.END else Gravity.START
                bottomMargin = 20
                if (isMine) marginStart = 48 else marginEnd = 48
            }
            layoutParams = params
            setBackgroundResource(if (isMine) R.drawable.bg_bubble_user else R.drawable.bg_bubble_driver)
            setPadding(30, 18, 30, 18)
        }

        val tvMessage = TextView(context).apply {
            text = msg.text
            setTextColor(if (isMine) Color.WHITE else Color.parseColor("#0B2530"))
            textSize = 13f
        }

        val tvTime = TextView(context).apply {
            text = formattedTime
            setTextColor(if (isMine) Color.parseColor("#CCFFFFFF") else Color.parseColor("#8FA8B4"))
            textSize = 10f
            gravity = Gravity.END
        }

        bubbleLayout.addView(tvMessage)
        bubbleLayout.addView(tvTime)
        return bubbleLayout
    }
}
