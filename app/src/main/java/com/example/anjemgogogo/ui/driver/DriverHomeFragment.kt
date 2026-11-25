package com.example.anjemgogogo.ui.driver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.anjemgogogo.R
import com.example.anjemgogogo.data.firebase.FirebaseRepository
import com.example.anjemgogogo.data.model.DriverLocation
import com.example.anjemgogogo.data.model.OrderModel
import com.example.anjemgogogo.data.model.UserProfile
import com.example.anjemgogogo.databinding.FragmentDriverHomeBinding
import com.example.anjemgogogo.databinding.ItemDriverOrderBinding
import com.example.anjemgogogo.ui.booking.OrderFragment
import com.example.anjemgogogo.utils.H3GeoUtils
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale

class DriverHomeFragment : Fragment(R.layout.fragment_driver_home) {

    private var _binding: FragmentDriverHomeBinding? = null
    private val binding get() = _binding!!

    private var driverProfile: UserProfile? = null
    private var activeOrder: OrderModel? = null
    private var isDriverOnline = true

    private var userListener: FirebaseRepository.ListenerHandle? = null
    private var availableOrdersListener: FirebaseRepository.ListenerHandle? = null
    private var driverOrdersListener: FirebaseRepository.ListenerHandle? = null

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private val simHandler = Handler(Looper.getMainLooper())
    private var simRunnable: Runnable? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && isAdded) {
            startLiveGpsBroadcast()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDriverHomeBinding.bind(view)

        binding.bottomNavigation.selectedItemId = R.id.nav_home
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_orders -> {
                    findNavController().navigate(R.id.action_driver_home_to_orders)
                    true
                }
                R.id.nav_chat -> {
                    findNavController().navigate(R.id.action_driver_home_to_chat_list)
                    true
                }
                R.id.nav_profile -> {
                    findNavController().navigate(R.id.action_driver_home_to_profile)
                    true
                }
                else -> false
            }
        }

        binding.switchDriverOnline.setOnCheckedChangeListener { _, isChecked ->
            isDriverOnline = isChecked
            binding.tvDriverOnlineLabel.text = if (isChecked) {
                "Online • Siap Terima Order"
            } else {
                "Offline • Tidak Menerima Order"
            }
            FirebaseRepository.currentUid()?.let { uid ->
                FirebaseRepository.updateUserFields(uid, mapOf("isOnline" to isChecked))
            }
            if (isChecked) {
                startLiveGpsBroadcast()
                observeIncomingOrders()
            } else {
                stopLiveGpsBroadcast()
                availableOrdersListener?.remove()
                availableOrdersListener = null
                binding.llIncomingOrders.removeAllViews()
                binding.tvEmptyIncomingOrders.visibility = View.VISIBLE
                binding.tvEmptyIncomingOrders.text = "Anda sedang Offline.\nAktifkan status Online untuk menerima orderan."
            }
        }

        setupActiveOrderActions()
        observeDriverData()
        ensureLocationPermissionAndBroadcast()
    }

    private fun observeDriverData() {
        val uid = FirebaseRepository.currentUid() ?: return
        userListener?.remove()
        userListener = FirebaseRepository.observeUserProfile(uid) { profile ->
            if (!isAdded || _binding == null || profile == null) return@observeUserProfile
            driverProfile = profile
            val firstName = profile.name.ifBlank { "Driver" }.substringBefore(" ")
            binding.tvDriverGreeting.text = "Halo, $firstName!"
            binding.tvDriverVehicle.text = "${profile.vehicleModel} • ${profile.vehiclePlate}"
            isDriverOnline = profile.isOnline
            if (binding.switchDriverOnline.isChecked != profile.isOnline) {
                binding.switchDriverOnline.isChecked = profile.isOnline
            }
        }

        driverOrdersListener?.remove()
        driverOrdersListener = FirebaseRepository.observeDriverOrders(uid) { orders ->
            if (!isAdded || _binding == null) return@observeDriverOrders
            val completed = orders.filter { it.status == OrderModel.STATUS_COMPLETED }
            binding.tvDriverTripsCount.text = completed.size.toString()
            binding.tvDriverEarnings.text = formatRupiah(completed.sumOf { it.fare })

            val currentActive = orders.firstOrNull {
                it.status == OrderModel.STATUS_DRIVER_TO_PICKUP || it.status == OrderModel.STATUS_ON_TRIP
            }
            activeOrder = currentActive
            renderActiveOrder(currentActive)
        }

        observeIncomingOrders()
    }

    private fun observeIncomingOrders() {
        if (!isDriverOnline) return
        availableOrdersListener?.remove()
        availableOrdersListener = FirebaseRepository.observeAvailableOrders { list ->
            if (!isAdded || _binding == null) return@observeAvailableOrders
            renderIncomingOrders(list)
        }
    }

    private fun renderIncomingOrders(orders: List<OrderModel>) {
        binding.llIncomingOrders.removeAllViews()
        if (orders.isEmpty()) {
            binding.tvEmptyIncomingOrders.visibility = View.VISIBLE
            binding.tvEmptyIncomingOrders.text = "Belum ada orderan masuk.\nOrderan baru dari penumpang akan muncul otomatis di sini."
            return
        }
        binding.tvEmptyIncomingOrders.visibility = View.GONE
        val inflater = LayoutInflater.from(requireContext())
        for (order in orders) {
            val item = ItemDriverOrderBinding.inflate(inflater, binding.llIncomingOrders, false)
            item.tvIncomingService.text = "${order.serviceType} • ${String.format(Locale.US, "%.1f", order.distanceKm)} km"
            item.tvIncomingFare.text = formatRupiah(order.fare)
            item.tvIncomingCustomer.text = "Penumpang: ${order.customerName.ifBlank { "Mahasiswa" }} • ${order.paymentMethod}"
            item.tvIncomingPickup.text = "🟢 Jemput: ${order.pickupName}"
            item.tvIncomingDest.text = "🔴 Tujuan: ${order.destName}"

            item.btnAcceptOrder.isEnabled = activeOrder == null
            item.btnAcceptOrder.setOnClickListener {
                val profile = driverProfile ?: UserProfile(
                    uid = FirebaseRepository.currentUid().orEmpty(),
                    name = FirebaseRepository.currentUser()?.displayName ?: "Driver Anjem"
                )
                item.btnAcceptOrder.isEnabled = false
                FirebaseRepository.acceptOrder(order.orderId, profile) { res ->
                    if (!isAdded || _binding == null) return@acceptOrder
                    res.onSuccess { accepted ->
                        OrderFragment.activeOrderId = accepted.orderId
                        OrderFragment.activeDriverId = profile.uid
                        OrderFragment.activeDriverName = accepted.driverName
                        OrderFragment.activePlateNumber = accepted.plateNumber
                        Toast.makeText(requireContext(), "Order diterima! Segera menuju titik jemput.", Toast.LENGTH_SHORT).show()
                        publishDriverLocationNow(accepted.pickupLat, accepted.pickupLng, OrderModel.STATUS_DRIVER_TO_PICKUP, accepted.orderId)
                    }.onFailure { err ->
                        item.btnAcceptOrder.isEnabled = activeOrder == null
                        Toast.makeText(requireContext(), err.localizedMessage ?: "Gagal mengambil order.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            binding.llIncomingOrders.addView(item.root)
        }
    }

    private fun renderActiveOrder(order: OrderModel?) {
        if (order == null) {
            binding.cardActiveDriverOrder.visibility = View.GONE
            stopSimulatedRouteMove()
            return
        }

        OrderFragment.activeOrderId = order.orderId
        OrderFragment.activeDriverId = order.driverId
        OrderFragment.activeDriverName = order.driverName
        OrderFragment.activePlateNumber = order.plateNumber

        binding.cardActiveDriverOrder.visibility = View.VISIBLE
        binding.tvActiveCustomerName.text = "Penumpang: ${order.customerName.ifBlank { "Mahasiswa UNNES" }}"
        binding.tvActivePickup.text = "🟢 Jemput: ${order.pickupName}"
        binding.tvActiveDest.text = "🔴 Tujuan: ${order.destName}"
        if (order.notes.isNotBlank()) {
            binding.tvActiveNotes.visibility = View.VISIBLE
            binding.tvActiveNotes.text = "📝 Catatan: ${order.notes}"
        } else {
            binding.tvActiveNotes.visibility = View.GONE
        }
        binding.tvActiveFarePayment.text = "${order.serviceType} • ${formatRupiah(order.fare)} • ${order.paymentMethod}"

        if (order.status == OrderModel.STATUS_DRIVER_TO_PICKUP) {
            binding.tvActiveOrderStatusBadge.text = "Menuju Jemput"
            binding.btnDriverNextStep.text = "🚀 Sudah Jemput & Mulai Antar"
        } else {
            binding.tvActiveOrderStatusBadge.text = "Dalam Perjalanan"
            binding.btnDriverNextStep.text = "✅ Selesaikan Perjalanan"
        }
    }

    private fun setupActiveOrderActions() {
        binding.btnDriverNextStep.setOnClickListener {
            val order = activeOrder ?: return@setOnClickListener
            if (order.status == OrderModel.STATUS_DRIVER_TO_PICKUP) {
                FirebaseRepository.updateOrderStatus(order.orderId, OrderModel.STATUS_ON_TRIP)
                Toast.makeText(requireContext(), "Status diperbarui: Dalam Perjalanan ke Tujuan", Toast.LENGTH_SHORT).show()
            } else if (order.status == OrderModel.STATUS_ON_TRIP) {
                stopSimulatedRouteMove()
                FirebaseRepository.updateOrderStatus(order.orderId, OrderModel.STATUS_COMPLETED)
                Toast.makeText(requireContext(), "Perjalanan selesai! Pendapatan tercatat.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDriverChatPassenger.setOnClickListener {
            val order = activeOrder ?: return@setOnClickListener
            OrderFragment.activeOrderId = order.orderId
            OrderFragment.activeDriverName = order.driverName
            OrderFragment.activePlateNumber = order.plateNumber
            findNavController().navigate(R.id.action_driver_home_to_chat)
        }

        binding.btnDriverSimulateMove.setOnClickListener {
            val order = activeOrder ?: return@setOnClickListener
            startSimulatedRouteMove(order)
        }

        binding.btnDriverCancelActive.setOnClickListener {
            val order = activeOrder ?: return@setOnClickListener
            stopSimulatedRouteMove()
            FirebaseRepository.updateOrderStatus(order.orderId, OrderModel.STATUS_CANCELLED)
            Toast.makeText(requireContext(), "Order dibatalkan.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSimulatedRouteMove(order: OrderModel) {
        stopSimulatedRouteMove()
        val startLat = if (order.status == OrderModel.STATUS_DRIVER_TO_PICKUP) order.pickupLat + 0.003 else order.pickupLat
        val startLng = if (order.status == OrderModel.STATUS_DRIVER_TO_PICKUP) order.pickupLng + 0.003 else order.pickupLng
        val endLat = if (order.status == OrderModel.STATUS_DRIVER_TO_PICKUP) order.pickupLat else order.destLat
        val endLng = if (order.status == OrderModel.STATUS_DRIVER_TO_PICKUP) order.pickupLng else order.destLng

        val steps = 10
        var idx = 0
        Toast.makeText(requireContext(), "📡 Mengirim simulasi pergerakan GPS driver ke Firebase...", Toast.LENGTH_SHORT).show()
        simRunnable = object : Runnable {
            override fun run() {
                if (!isAdded || _binding == null || activeOrder == null || idx > steps) return
                val f = idx.toDouble() / steps
                val lat = startLat + (endLat - startLat) * f
                val lng = startLng + (endLng - startLng) * f
                publishDriverLocationNow(lat, lng, order.status, order.orderId)
                idx++
                simHandler.postDelayed(this, 600)
            }
        }
        simHandler.post(simRunnable!!)
    }

    private fun stopSimulatedRouteMove() {
        simRunnable?.let { simHandler.removeCallbacks(it) }
        simRunnable = null
    }

    private fun ensureLocationPermissionAndBroadcast() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLiveGpsBroadcast()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startLiveGpsBroadcast() {
        if (!isAdded || !isDriverOnline) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val client = fusedLocationClient ?: LocationServices.getFusedLocationProviderClient(requireActivity()).also {
                fusedLocationClient = it
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
                .setMinUpdateIntervalMillis(2500L)
                .build()

            locationCallback?.let { client.removeLocationUpdates(it) }
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    val order = activeOrder
                    publishDriverLocationNow(
                        loc.latitude,
                        loc.longitude,
                        order?.status ?: "ONLINE",
                        order?.orderId.orEmpty()
                    )
                    if (isAdded && _binding != null) {
                        binding.tvDriverLocationStatus.text = "📡 GPS Live aktif (${String.format(Locale.US, "%.4f, %.4f", loc.latitude, loc.longitude)})"
                    }
                }
            }
            client.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopLiveGpsBroadcast() {
        locationCallback?.let { cb ->
            fusedLocationClient?.removeLocationUpdates(cb)
        }
        locationCallback = null
        if (_binding != null) {
            binding.tvDriverLocationStatus.text = "Status Offline • GPS tidak disiarkan"
        }
    }

    private fun publishDriverLocationNow(lat: Double, lng: Double, phase: String, orderId: String) {
        val uid = FirebaseRepository.currentUid() ?: return
        val profile = driverProfile
        FirebaseRepository.updateDriverLocation(
            DriverLocation(
                driverId = uid,
                driverName = profile?.name ?: FirebaseRepository.currentUser()?.displayName ?: "Driver Anjem",
                plateNumber = profile?.vehiclePlate ?: "H 2341 XY",
                lat = lat,
                lng = lng,
                h3Index = H3GeoUtils.getH3Index(lat, lng),
                phase = phase,
                orderId = orderId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun formatRupiah(amount: Long): String {
        return "Rp ${String.format(Locale.US, "%,d", amount).replace(',', '.')}"
    }

    override fun onDestroyView() {
        userListener?.remove()
        userListener = null
        availableOrdersListener?.remove()
        availableOrdersListener = null
        driverOrdersListener?.remove()
        driverOrdersListener = null
        stopSimulatedRouteMove()
        stopLiveGpsBroadcast()
        super.onDestroyView()
        _binding = null
    }
}
