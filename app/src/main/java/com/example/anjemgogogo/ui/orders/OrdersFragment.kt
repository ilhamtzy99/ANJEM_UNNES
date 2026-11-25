package com.example.anjemgogogo.ui.orders

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.anjemgogogo.R
import com.example.anjemgogogo.data.firebase.FirebaseRepository
import com.example.anjemgogogo.data.model.OrderModel
import com.example.anjemgogogo.databinding.FragmentOrdersBinding
import com.example.anjemgogogo.databinding.ItemOrderHistoryBinding
import com.example.anjemgogogo.ui.booking.OrderFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OrdersFragment : Fragment(R.layout.fragment_orders) {

    private var _binding: FragmentOrdersBinding? = null
    private val binding get() = _binding!!
    private var ordersListener: FirebaseRepository.ListenerHandle? = null
    private val dateFormat = SimpleDateFormat("dd MMM yyyy • HH:mm 'WIB'", Locale("id", "ID"))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentOrdersBinding.bind(view)

        val isDriver = FirebaseRepository.isDriver()
        binding.tvSummarySpentLabel.text = if (isDriver) "Total pendapatan" else "Total pengeluaran"
        binding.btnOrdersNotification.setOnClickListener {
            if (!FirebaseRepository.isDriver()) {
                findNavController().navigate(R.id.action_orders_to_notifications)
            } else {
                Toast.makeText(requireContext(), "Notifikasi khusus mode penumpang", Toast.LENGTH_SHORT).show()
            }
        }

        binding.bottomNavigation.selectedItemId = R.id.nav_orders
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    val homeAction = if (FirebaseRepository.isDriver()) {
                        R.id.action_orders_to_driver_home
                    } else {
                        R.id.action_orders_to_home
                    }
                    findNavController().navigate(homeAction)
                    true
                }
                R.id.nav_orders -> true
                R.id.nav_chat -> {
                    findNavController().navigate(R.id.action_orders_to_chat_list)
                    true
                }
                R.id.nav_profile -> {
                    findNavController().navigate(R.id.action_orders_to_profile)
                    true
                }
                else -> false
            }
        }

        observeOrdersLive()
    }

    private fun observeOrdersLive() {
        val uid = FirebaseRepository.currentUid()
        if (uid == null) {
            binding.tvEmptyOrders.visibility = View.VISIBLE
            return
        }

        ordersListener?.remove()
        ordersListener = if (FirebaseRepository.isDriver()) {
            FirebaseRepository.observeDriverOrders(uid) { orders ->
                if (!isAdded || _binding == null) return@observeDriverOrders
                renderOrders(orders)
            }
        } else {
            FirebaseRepository.observeUserOrders(uid) { orders ->
                if (!isAdded || _binding == null) return@observeUserOrders
                renderOrders(orders)
            }
        }
    }

    private fun renderOrders(orders: List<OrderModel>) {
        binding.llOrdersContainer.removeAllViews()

        if (orders.isEmpty()) {
            binding.tvEmptyOrders.visibility = View.VISIBLE
            binding.tvTotalTrips.text = "0"
            binding.tvTotalSpent.text = "Rp 0"
            return
        }

        binding.tvEmptyOrders.visibility = View.GONE
        val validOrders = orders.filter { it.status != OrderModel.STATUS_CANCELLED }
        val totalAmount = validOrders.sumOf { it.fare }
        binding.tvTotalTrips.text = validOrders.size.toString()
        binding.tvTotalSpent.text = formatRupiah(totalAmount)

        val inflater = LayoutInflater.from(requireContext())
        for (order in orders) {
            val itemBinding = ItemOrderHistoryBinding.inflate(inflater, binding.llOrdersContainer, false)
            itemBinding.tvOrderDate.text = if (order.createdAt > 0) {
                dateFormat.format(Date(order.createdAt))
            } else {
                "Baru saja"
            }

            val (statusLabel, statusBgRes, statusColorRes) = mapStatusDisplay(order.status)
            itemBinding.tvOrderStatus.text = statusLabel
            itemBinding.tvOrderStatus.setBackgroundResource(statusBgRes)
            itemBinding.tvOrderStatus.setTextColor(ContextCompat.getColor(requireContext(), statusColorRes))

            itemBinding.tvOrderPickup.text = order.pickupName.ifBlank { "Titik Jemput" }
            itemBinding.tvOrderDestination.text = order.destName.ifBlank { "Titik Tujuan" }
            itemBinding.tvOrderServicePayment.text = "${order.serviceType} • ${order.paymentMethod}"
            itemBinding.tvOrderFare.text = formatRupiah(order.fare)

            itemBinding.root.setOnClickListener {
                OrderFragment.activeOrderId = order.orderId
                if (order.driverName.isNotBlank()) OrderFragment.activeDriverName = order.driverName
                if (order.plateNumber.isNotBlank()) OrderFragment.activePlateNumber = order.plateNumber
                Toast.makeText(requireContext(), "Membuka chat pesanan ($statusLabel)", Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.action_orders_to_chat)
            }

            binding.llOrdersContainer.addView(itemBinding.root)
        }
    }

    private fun mapStatusDisplay(status: String): Triple<String, Int, Int> {
        return when (status) {
            OrderModel.STATUS_SEARCHING, OrderModel.STATUS_WAITING -> Triple("Menunggu Driver", R.drawable.bg_badge_yellow, R.color.amber_text)
            OrderModel.STATUS_DRIVER_TO_PICKUP -> Triple("Menuju Jemput", R.drawable.bg_badge_verified, R.color.palette_dark)
            OrderModel.STATUS_ON_TRIP -> Triple("Dalam Perjalanan", R.drawable.bg_badge_verified, R.color.palette_dark)
            OrderModel.STATUS_COMPLETED -> Triple("Selesai", R.drawable.bg_status_done, R.color.palette_dark)
            OrderModel.STATUS_CANCELLED -> Triple("Dibatalkan", R.drawable.bg_logout_button, R.color.danger)
            else -> Triple(status, R.drawable.bg_status_done, R.color.palette_dark)
        }
    }

    private fun formatRupiah(amount: Long): String {
        return "Rp ${String.format(Locale.US, "%,d", amount).replace(',', '.')}"
    }

    override fun onDestroyView() {
        ordersListener?.remove()
        ordersListener = null
        super.onDestroyView()
        _binding = null
    }
}
