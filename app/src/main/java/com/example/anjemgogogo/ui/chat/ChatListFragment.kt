package com.example.anjemgogogo.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.anjemgogogo.R
import com.example.anjemgogogo.data.firebase.FirebaseRepository
import com.example.anjemgogogo.data.model.ChatMessage
import com.example.anjemgogogo.data.model.OrderModel
import com.example.anjemgogogo.databinding.FragmentChatListBinding
import com.example.anjemgogogo.databinding.ItemChatThreadBinding
import com.example.anjemgogogo.ui.booking.OrderFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatListFragment : Fragment(R.layout.fragment_chat_list) {

    private var _binding: FragmentChatListBinding? = null
    private val binding get() = _binding!!

    private var ordersListener: FirebaseRepository.ListenerHandle? = null
    private val lastMsgListeners = mutableListOf<FirebaseRepository.ListenerHandle>()
    private val lastMessagesByOrder = mutableMapOf<String, ChatMessage?>()
    private var currentOrders: List<OrderModel> = emptyList()

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentChatListBinding.bind(view)

        val isDriver = FirebaseRepository.isDriver()
        binding.tvChatListSubtitle.text = if (isDriver) {
            "Daftar percakapan dengan penumpang"
        } else {
            "Daftar percakapan dengan driver"
        }

        binding.bottomNavigation.selectedItemId = R.id.nav_chat
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    val action = if (FirebaseRepository.isDriver()) {
                        R.id.action_chat_list_to_driver_home
                    } else {
                        R.id.action_chat_list_to_home
                    }
                    findNavController().navigate(action)
                    true
                }
                R.id.nav_chat -> true
                R.id.nav_orders -> {
                    findNavController().navigate(R.id.action_chat_list_to_orders)
                    true
                }
                R.id.nav_profile -> {
                    findNavController().navigate(R.id.action_chat_list_to_profile)
                    true
                }
                else -> false
            }
        }

        observeThreadsLive()
    }

    private fun observeThreadsLive() {
        val uid = FirebaseRepository.currentUid()
        if (uid == null) {
            binding.tvEmptyChats.visibility = View.VISIBLE
            return
        }

        ordersListener?.remove()
        ordersListener = if (FirebaseRepository.isDriver()) {
            FirebaseRepository.observeDriverOrders(uid) { orders ->
                if (!isAdded || _binding == null) return@observeDriverOrders
                onOrdersUpdated(orders)
            }
        } else {
            FirebaseRepository.observeUserOrders(uid) { orders ->
                if (!isAdded || _binding == null) return@observeUserOrders
                onOrdersUpdated(orders.filter { it.driverId.isNotBlank() })
            }
        }
    }

    private fun onOrdersUpdated(orders: List<OrderModel>) {
        currentOrders = orders
        clearLastMessageListeners()

        if (orders.isEmpty()) {
            renderThreads()
            return
        }

        for (order in orders) {
            val handle = FirebaseRepository.observeLastChatMessage(order.orderId) { lastMsg ->
                if (!isAdded || _binding == null) return@observeLastChatMessage
                lastMessagesByOrder[order.orderId] = lastMsg
                renderThreads()
            }
            lastMsgListeners.add(handle)
        }
        renderThreads()
    }

    private fun renderThreads() {
        binding.llChatThreads.removeAllViews()
        if (currentOrders.isEmpty()) {
            binding.tvEmptyChats.visibility = View.VISIBLE
            return
        }

        binding.tvEmptyChats.visibility = View.GONE
        val isDriver = FirebaseRepository.isDriver()
        val sortedOrders = currentOrders.sortedByDescending { order ->
            val msgTs = lastMessagesByOrder[order.orderId]?.timestamp ?: 0L
            maxOf(msgTs, order.updatedAt, order.createdAt)
        }

        val inflater = LayoutInflater.from(requireContext())
        for (order in sortedOrders) {
            val item = ItemChatThreadBinding.inflate(inflater, binding.llChatThreads, false)
            val peerName = if (isDriver) {
                order.customerName.ifBlank { "Penumpang UNNES" }
            } else {
                order.driverName.ifBlank { "Driver Anjem" }
            }

            item.tvThreadAvatarInitials.text = computeInitials(peerName)
            item.tvThreadName.text = peerName

            val lastMsg = lastMessagesByOrder[order.orderId]
            val displayTs = lastMsg?.timestamp?.takeIf { it > 0 } ?: order.updatedAt.takeIf { it > 0 } ?: order.createdAt
            item.tvThreadTime.text = if (displayTs > 0) timeFormat.format(Date(displayTs)) else ""
            item.tvThreadLastMessage.text = if (lastMsg != null) {
                val prefix = if (lastMsg.senderId == FirebaseRepository.currentUid()) "Anda: " else ""
                "$prefix${lastMsg.text}"
            } else {
                "Belum ada pesan • Ketuk untuk mulai chat"
            }

            item.tvThreadRoute.text = "${order.serviceType} • ${order.pickupName} → ${order.destName}"

            val (statusLabel, bgRes, colorRes) = when (order.status) {
                OrderModel.STATUS_DRIVER_TO_PICKUP -> Triple("Menuju Jemput", R.drawable.bg_badge_verified, R.color.palette_dark)
                OrderModel.STATUS_ON_TRIP -> Triple("Dalam Perjalanan", R.drawable.bg_badge_verified, R.color.palette_dark)
                OrderModel.STATUS_COMPLETED -> Triple("Selesai", R.drawable.bg_status_done, R.color.palette_dark)
                OrderModel.STATUS_CANCELLED -> Triple("Dibatalkan", R.drawable.bg_logout_button, R.color.danger)
                else -> Triple("Aktif", R.drawable.bg_badge_yellow, R.color.amber_text)
            }
            item.tvThreadStatus.text = statusLabel
            item.tvThreadStatus.setBackgroundResource(bgRes)
            item.tvThreadStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes))

            item.root.setOnClickListener {
                OrderFragment.activeOrderId = order.orderId
                OrderFragment.activeDriverId = order.driverId
                OrderFragment.activeDriverName = order.driverName.ifBlank { "Driver Anjem" }
                OrderFragment.activePlateNumber = order.plateNumber.ifBlank { "-" }
                findNavController().navigate(R.id.action_chat_list_to_chat)
            }

            binding.llChatThreads.addView(item.root)
        }
    }

    private fun computeInitials(name: String): String {
        val parts = name.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "AN"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "${parts[0].first()}${parts[1].first()}".uppercase()
        }
    }

    private fun clearLastMessageListeners() {
        for (handle in lastMsgListeners) {
            handle.remove()
        }
        lastMsgListeners.clear()
    }

    override fun onDestroyView() {
        ordersListener?.remove()
        ordersListener = null
        clearLastMessageListeners()
        super.onDestroyView()
        _binding = null
    }
}
