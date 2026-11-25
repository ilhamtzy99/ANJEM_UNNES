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
    private var orderHeaderListener: FirebaseRepository.ListenerHandle? = null
    private var activeChatOrderId: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentChatBinding.bind(view)

        val isDriver = FirebaseRepository.isDriver()
        if (isDriver) {
            binding.tvDriverName.text = "Chat dengan Penumpang"
            binding.tvDriverStatus.text = "Order Aktif Driver"
            binding.etChatMessage.hint = "Ketik pesan untuk penumpang..."
            binding.chipReply1.text = "📍 Saya sedang menuju titik jemput"
            binding.chipReply2.text = "🛵 Saya sudah sampai di lokasi"
            binding.chipReply3.text = "⏳ Mohon ditunggu sebentar ya kak"
        } else {
            binding.tvDriverName.text = "${OrderFragment.activeDriverName} (Anjem)"
            binding.tvDriverStatus.text = "Honda Beat • ${OrderFragment.activePlateNumber} (★ 4.9)"
        }

        binding.btnBackChat.setOnClickListener {
            if (!findNavController().navigateUp()) {
                val homeAction = if (FirebaseRepository.isDriver()) {
                    R.id.action_chat_to_driver_home
                } else {
                    R.id.action_chat_to_home
                }
                findNavController().navigate(homeAction)
            }
        }

        binding.btnCallDriver.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:081234567890"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Menghubungi (0812-3456-7890)...", Toast.LENGTH_SHORT).show()
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
        if (!isDriver) {
            startPostTripTimer()
        }
    }

    private fun resolveAndObserveChat() {
        val currentOrderId = OrderFragment.activeOrderId
        if (currentOrderId.isNotBlank()) {
            attachOrderHeader(currentOrderId)
            attachChatListener(currentOrderId)
            return
        }

        val uid = FirebaseRepository.currentUid() ?: return
        val isDriver = FirebaseRepository.isDriver()
        FirebaseRepository.fetchLatestOrderForCurrentRole(uid, isDriver) { latestOrder ->
            if (!isAdded || _binding == null) return@fetchLatestOrderForCurrentRole
            if (latestOrder != null) {
                OrderFragment.activeOrderId = latestOrder.orderId
                updateHeaderFromOrder(latestOrder.customerName, latestOrder.driverName, latestOrder.plateNumber, latestOrder.pickupName, latestOrder.destName)
                attachChatListener(latestOrder.orderId)
            } else {
                attachChatListener("support_$uid")
            }
        }
    }

    private fun attachOrderHeader(orderId: String) {
        orderHeaderListener?.remove()
        orderHeaderListener = FirebaseRepository.observeOrder(orderId) { order ->
            if (!isAdded || _binding == null || order == null) return@observeOrder
            updateHeaderFromOrder(order.customerName, order.driverName, order.plateNumber, order.pickupName, order.destName)
        }
    }

    private fun updateHeaderFromOrder(
        customerName: String,
        driverName: String,
        plateNumber: String,
        pickup: String,
        dest: String
    ) {
        if (FirebaseRepository.isDriver()) {
            binding.tvDriverName.text = customerName.ifBlank { "Penumpang UNNES" }
            binding.tvDriverStatus.text = "$pickup → $dest"
        } else {
            val dName = driverName.ifBlank { OrderFragment.activeDriverName }
            val dPlate = plateNumber.ifBlank { OrderFragment.activePlateNumber }
            binding.tvDriverName.text = "$dName (Anjem)"
            binding.tvDriverStatus.text = "Honda Beat • $dPlate (★ 4.9)"
        }
    }

    private fun attachChatListener(orderId: String) {
        activeChatOrderId = orderId
        chatListener?.remove()
        chatListener = FirebaseRepository.observeChatMessages(orderId) { messages ->
            if (!isAdded || _binding == null) return@observeChatMessages
            val initialChildCount = 1.coerceAtMost(binding.chatMessageContainer.childCount)
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
        val senderRole = if (FirebaseRepository.isDriver()) "driver" else "customer"
        val userMsg = ChatMessage(
            orderId = activeChatOrderId,
            senderId = uid,
            senderName = FirebaseRepository.currentUser()?.displayName ?: if (senderRole == "driver") "Driver" else "Customer",
            senderRole = senderRole,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        FirebaseRepository.sendChatMessage(activeChatOrderId, userMsg)
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
        orderHeaderListener?.remove()
        orderHeaderListener = null
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        super.onDestroyView()
        _binding = null
    }
}
