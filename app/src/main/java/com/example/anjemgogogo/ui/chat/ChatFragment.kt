package com.example.anjemgogogo.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.anjemgogogo.R
import com.example.anjemgogogo.data.firebase.FirebaseRepository
import com.example.anjemgogogo.data.model.ChatMessage
import com.example.anjemgogogo.databinding.FragmentChatBinding
import com.example.anjemgogogo.ui.booking.OrderFragment

class ChatFragment : Fragment(R.layout.fragment_chat) {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var chatListener: FirebaseRepository.ListenerHandle? = null
    private var activeChatOrderId: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentChatBinding.bind(view)

        binding.tvDriverName.text = "${OrderFragment.activeDriverName} (Anjem)"
        binding.tvDriverStatus.text = "Honda Beat • ${OrderFragment.activePlateNumber} (★ 4.9)"

        binding.btnBackChat.setOnClickListener {
            if (!findNavController().navigateUp()) {
                findNavController().navigate(R.id.action_chat_to_home)
            }
        }

        binding.btnCallDriver.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:081234567890"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Menghubungi Driver ${OrderFragment.activeDriverName} (0812-3456-7890)...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSendChat.setOnClickListener {
            val text = binding.etChatMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendLiveMessage(text)
                binding.etChatMessage.setText("")
            }
        }

        binding.chipReply1.setOnClickListener { sendLiveMessage(binding.chipReply1.text.toString()) }
        binding.chipReply2.setOnClickListener { sendLiveMessage(binding.chipReply2.text.toString()) }
        binding.chipReply3.setOnClickListener { sendLiveMessage(binding.chipReply3.text.toString()) }

        resolveAndObserveChat()
        startPostTripTimer()
    }

    private fun resolveAndObserveChat() {
        val currentOrderId = OrderFragment.activeOrderId
        if (currentOrderId.isNotBlank()) {
            attachChatListener(currentOrderId)
            return
        }

        val uid = FirebaseRepository.currentUid() ?: return
        FirebaseRepository.fetchLatestOrderForUser(uid) { latestOrder ->
            if (!isAdded || _binding == null) return@fetchLatestOrderForUser
            if (latestOrder != null) {
                OrderFragment.activeOrderId = latestOrder.orderId
                if (latestOrder.driverName.isNotBlank()) {
                    OrderFragment.activeDriverName = latestOrder.driverName
                    binding.tvDriverName.text = "${latestOrder.driverName} (Anjem)"
                }
                if (latestOrder.plateNumber.isNotBlank()) {
                    OrderFragment.activePlateNumber = latestOrder.plateNumber
                    binding.tvDriverStatus.text = "Honda Beat • ${latestOrder.plateNumber} (★ 4.9)"
                }
                attachChatListener(latestOrder.orderId)
            } else {
                attachChatListener("support_$uid")
            }
        }
    }

    private fun attachChatListener(orderId: String) {
        activeChatOrderId = orderId
        chatListener?.remove()
        chatListener = FirebaseRepository.observeChatMessages(orderId) { messages ->
            if (!isAdded || _binding == null) return@observeChatMessages
            // Pertahankan 2 child awal dari XML (pill keamanan + sapaan awal driver)
            val initialChildCount = 2.coerceAtMost(binding.chatMessageContainer.childCount)
            ChatBubbleHelper.renderMessages(
                requireContext(),
                binding.chatMessageContainer,
                binding.chatScrollView,
                messages,
                keepFirstChildCount = initialChildCount
            )
        }
    }

    private fun sendLiveMessage(text: String) {
        val uid = FirebaseRepository.currentUid()
        if (uid == null || activeChatOrderId.isBlank()) {
            Toast.makeText(requireContext(), "Menunggu koneksi ruang chat...", Toast.LENGTH_SHORT).show()
            return
        }
        val userMsg = ChatMessage(
            orderId = activeChatOrderId,
            senderId = uid,
            senderName = FirebaseRepository.currentUser()?.displayName ?: "Customer",
            senderRole = "customer",
            text = text,
            timestamp = System.currentTimeMillis()
        )
        FirebaseRepository.sendChatMessage(activeChatOrderId, userMsg) { sent ->
            if (sent && isAdded) {
                scheduleSimulatedDriverReply(activeChatOrderId)
            }
        }
    }

    private fun scheduleSimulatedDriverReply(orderId: String) {
        timerHandler.postDelayed({
            if (!isAdded || activeChatOrderId != orderId) return@postDelayed
            val replies = listOf(
                "Siap kak! Saya meluncur ke titik lokasi sekarang.",
                "Baik kak, posisi saya sudah dekat sekitar 200 meter lagi.",
                "Siap, saya tunggu di titik jemput depan pos ya kak.",
                "Oke kak, siap laksanakan!"
            )
            val driverMsg = ChatMessage(
                orderId = orderId,
                senderId = OrderFragment.activeDriverId,
                senderName = OrderFragment.activeDriverName,
                senderRole = "driver",
                text = replies.random(),
                timestamp = System.currentTimeMillis()
            )
            FirebaseRepository.sendChatMessage(orderId, driverMsg)
        }, 1200)
    }

    private fun startPostTripTimer() {
        if (OrderFragment.tripPhase == OrderFragment.TripPhase.TRIP_COMPLETED) {
            timerRunnable = object : Runnable {
                override fun run() {
                    if (OrderFragment.postTripSecondsLeft > 0 && isAdded && _binding != null) {
                        binding.tvDriverName.text = "${OrderFragment.activeDriverName} (⏱️ ${OrderFragment.postTripSecondsLeft}s tersisa)"
                        timerHandler.postDelayed(this, 1000)
                    } else if (isAdded && _binding != null) {
                        binding.tvDriverName.text = "${OrderFragment.activeDriverName} (Sesi Chat Berakhir)"
                        binding.etChatMessage.isEnabled = false
                        binding.etChatMessage.hint = "Sesi chat 1 menit telah berakhir"
                        binding.btnSendChat.isEnabled = false
                        Toast.makeText(requireContext(), "Sesi chat 1 menit telah berakhir.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            timerHandler.post(timerRunnable!!)
        }
    }

    override fun onDestroyView() {
        chatListener?.remove()
        chatListener = null
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        super.onDestroyView()
        _binding = null
    }
}
