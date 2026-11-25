package com.example.anjemgogogo.ui.notifications

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.anjemgogogo.R
import com.example.anjemgogogo.data.firebase.FirebaseRepository
import com.example.anjemgogogo.data.model.AppNotification
import com.example.anjemgogogo.data.model.OrderModel
import com.example.anjemgogogo.databinding.FragmentNotificationsBinding
import com.example.anjemgogogo.databinding.ItemNotificationBinding
import com.example.anjemgogogo.ui.booking.OrderFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationsFragment : Fragment(R.layout.fragment_notifications) {

    private data class DisplayNotif(
        val icon: String,
        val title: String,
        val message: String,
        val tag: String,
        val bgRes: Int,
        val colorRes: Int,
        val timestamp: Long,
        val order: OrderModel? = null
    )

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!

    private var ordersListener: FirebaseRepository.ListenerHandle? = null
    private var customNotifListener: FirebaseRepository.ListenerHandle? = null

    private var latestOrders: List<OrderModel> = emptyList()
    private var latestCustomNotifs: List<AppNotification> = emptyList()
    private val dateTimeFormat = SimpleDateFormat("dd MMM • HH:mm", Locale("id", "ID"))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentNotificationsBinding.bind(view)

        binding.btnBackNotifications.setOnClickListener {
            findNavController().navigateUp()
        }

        observeLiveNotifications()
    }

    private fun observeLiveNotifications() {
        val uid = FirebaseRepository.currentUid()
        if (uid == null) {
            binding.tvEmptyNotifications.visibility = View.VISIBLE
            return
        }

        ordersListener?.remove()
        ordersListener = FirebaseRepository.observeUserOrders(uid) { orders ->
            if (!isAdded || _binding == null) return@observeUserOrders
            latestOrders = orders
            renderCombinedNotifications()
        }

        customNotifListener?.remove()
        customNotifListener = FirebaseRepository.observeUserNotifications(uid) { notifs ->
            if (!isAdded || _binding == null) return@observeUserNotifications
            latestCustomNotifs = notifs
            renderCombinedNotifications()
        }
    }

    private fun renderCombinedNotifications() {
        val combined = mutableListOf<DisplayNotif>()

        for (order in latestOrders) {
            val ts = order.updatedAt.takeIf { it > 0 } ?: order.createdAt
            val fareText = "Rp ${String.format(Locale.US, "%,d", order.fare).replace(',', '.')}"
            val item = when (order.status) {
                OrderModel.STATUS_SEARCHING, OrderModel.STATUS_WAITING -> DisplayNotif(
                    icon = "🔍",
                    title = "Mencari Driver (${order.serviceType})",
                    message = "Pesanan dari ${order.pickupName} ke ${order.destName} sedang menunggu konfirmasi driver.",
                    tag = "Menunggu",
                    bgRes = R.drawable.bg_badge_yellow,
                    colorRes = R.color.amber_text,
                    timestamp = ts,
                    order = order
                )
                OrderModel.STATUS_DRIVER_TO_PICKUP -> DisplayNotif(
                    icon = "🛵",
                    title = "Driver Menuju Titik Jemput",
                    message = "${order.driverName.ifBlank { "Driver" }} (${order.plateNumber}) sedang menuju ${order.pickupName}.",
                    tag = "Menuju Jemput",
                    bgRes = R.drawable.bg_badge_verified,
                    colorRes = R.color.palette_dark,
                    timestamp = ts,
                    order = order
                )
                OrderModel.STATUS_ON_TRIP -> DisplayNotif(
                    icon = "🚀",
                    title = "Dalam Perjalanan",
                    message = "Anda sedang menuju ${order.destName} bersama ${order.driverName.ifBlank { "Driver" }}.",
                    tag = "Di Jalan",
                    bgRes = R.drawable.bg_badge_verified,
                    colorRes = R.color.palette_dark,
                    timestamp = ts,
                    order = order
                )
                OrderModel.STATUS_COMPLETED -> DisplayNotif(
                    icon = "🎉",
                    title = "Perjalanan Selesai",
                    message = "Tiba di ${order.destName}. Total tarif $fareText (${order.paymentMethod}).",
                    tag = "Selesai",
                    bgRes = R.drawable.bg_status_done,
                    colorRes = R.color.palette_dark,
                    timestamp = ts,
                    order = order
                )
                OrderModel.STATUS_CANCELLED -> DisplayNotif(
                    icon = "❌",
                    title = "Pesanan Dibatalkan",
                    message = "Pesanan dari ${order.pickupName} ke ${order.destName} telah dibatalkan.",
                    tag = "Dibatalkan",
                    bgRes = R.drawable.bg_logout_button,
                    colorRes = R.color.danger,
                    timestamp = ts,
                    order = order
                )
                else -> null
            }
            if (item != null) combined.add(item)
        }

        for (n in latestCustomNotifs) {
            combined.add(
                DisplayNotif(
                    icon = "💰",
                    title = n.title.ifBlank { "Info KampusPay" },
                    message = n.message,
                    tag = n.tag.ifBlank { "KampusPay" },
                    bgRes = R.drawable.bg_badge_yellow,
                    colorRes = R.color.amber_text,
                    timestamp = n.timestamp
                )
            )
        }

        combined.sortByDescending { it.timestamp }

        binding.llNotificationsContainer.removeAllViews()
        if (combined.isEmpty()) {
            binding.tvEmptyNotifications.visibility = View.VISIBLE
            return
        }

        binding.tvEmptyNotifications.visibility = View.GONE
        val inflater = LayoutInflater.from(requireContext())
        for (notif in combined) {
            val itemBinding = ItemNotificationBinding.inflate(inflater, binding.llNotificationsContainer, false)
            itemBinding.tvNotifIcon.text = notif.icon
            itemBinding.tvNotifTitle.text = notif.title
            itemBinding.tvNotifMessage.text = notif.message
            itemBinding.tvNotifTime.text = if (notif.timestamp > 0) dateTimeFormat.format(Date(notif.timestamp)) else ""
            itemBinding.tvNotifTag.text = notif.tag
            itemBinding.tvNotifTag.setBackgroundResource(notif.bgRes)
            itemBinding.tvNotifTag.setTextColor(ContextCompat.getColor(requireContext(), notif.colorRes))

            itemBinding.root.setOnClickListener {
                val order = notif.order
                if (order != null && order.driverId.isNotBlank()) {
                    OrderFragment.activeOrderId = order.orderId
                    OrderFragment.activeDriverId = order.driverId
                    OrderFragment.activeDriverName = order.driverName
                    OrderFragment.activePlateNumber = order.plateNumber
                    findNavController().navigate(R.id.action_notifications_to_chat)
                } else if (order != null) {
                    findNavController().navigate(R.id.action_notifications_to_order)
                } else {
                    Toast.makeText(requireContext(), notif.message, Toast.LENGTH_SHORT).show()
                }
            }
            binding.llNotificationsContainer.addView(itemBinding.root)
        }
    }

    override fun onDestroyView() {
        ordersListener?.remove()
        ordersListener = null
        customNotifListener?.remove()
        customNotifListener = null
        super.onDestroyView()
        _binding = null
    }
}
