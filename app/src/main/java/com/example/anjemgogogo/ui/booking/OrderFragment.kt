package com.example.anjemgogogo.ui.booking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.anjemgogogo.R
import com.example.anjemgogogo.data.firebase.FirebaseRepository
import com.example.anjemgogogo.data.model.ChatMessage
import com.example.anjemgogogo.data.model.DriverLocation
import com.example.anjemgogogo.data.model.OrderModel
import com.example.anjemgogogo.databinding.FragmentOrderBinding
import com.example.anjemgogogo.ui.chat.ChatBubbleHelper
import com.example.anjemgogogo.utils.H3GeoUtils
import com.google.android.gms.location.LocationServices
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.sin

class OrderFragment : Fragment(R.layout.fragment_order) {

    enum class TripPhase {
        IDLE,
        WAITING_CONFIRMATION,
        DRIVER_TO_PICKUP,
        TRIP_TO_DESTINATION,
        TRIP_COMPLETED
    }

    companion object {
        var tripPhase = TripPhase.IDLE
        var activeOrderId = ""
        var activeDriverId = "driver_ahmad_fauzi"
        var activeDriverName = "Ahmad Fauzi"
        var activePlateNumber = "H 2341 XY"
        var activeServiceName = "Anjem Motor"
        var activeJemput = ""
        var activeTujuan = ""
        var activeCatatan = ""
        var postTripSecondsLeft = 30
    }

    private var _binding: FragmentOrderBinding? = null
    private val binding get() = _binding!!

    private var selectedServiceType = "Motor" // "Motor" or "Kurir"
    private var isSatelliteMode = false // Toggle between Street & Satellite
    private var lastDistanceKm: Double = 1.0
    private var lastFare: Long = 7500L
    private var activePaymentMethod: String = "KampusPay"
    private var fareDeductedForOrderId: String = ""

    private var userListener: FirebaseRepository.ListenerHandle? = null
    private var orderListener: FirebaseRepository.ListenerHandle? = null
    private var driverLocListener: FirebaseRepository.ListenerHandle? = null
    private var overlayChatListener: FirebaseRepository.ListenerHandle? = null

    // Dynamic coordinates chosen by user (Defaults to GPS)
    private var pickupGeoPoint = GeoPoint(-7.0475, 110.4072) // Gerbang Utama UNNES
    private var destinationGeoPoint = GeoPoint(-7.0531, 110.4085) // Banaran

    private var targetSettingField = "jemput" // "jemput" or "tujuan"
    private var isProgrammaticTextUpdate = false

    private var markerPickup: Marker? = null
    private var markerDest: Marker? = null
    private var markerDriver: Marker? = null
    private var routeLine: Polyline? = null
    private var currentWaypoints: List<GeoPoint> = emptyList()

    // Live tracking animation state
    private var isTrackingActive = false
    private val trackingHandler = Handler(Looper.getMainLooper())
    private var trackingRunnable: Runnable? = null
    private val postTripHandler = Handler(Looper.getMainLooper())
    private var postTripRunnable: Runnable? = null

    // Debounce Handler to prevent map scrolling lag
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingDebounceRunnable: Runnable? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && isAdded && _binding != null) {
            moveToCurrentGpsLocation()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentOrderBinding.bind(view)

        // Initialize OSMDroid Configuration with valid package User-Agent to comply with tile policy
        val ctx = requireContext().applicationContext
        Configuration.getInstance().userAgentValue = "Mozilla/5.0 (Android; Mobile) AnjemUNNES/1.0 (${ctx.packageName})"
        Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

        setupMapView()
        setupServiceToggle()
        setupEditorActionListeners()
        setupOverlayChatListeners()
        observeUserBalance()

        // Activate GPS location for default pickup
        checkLocationPermissionAndMove()

        binding.btnToggleMapStyle.setOnClickListener {
            isSatelliteMode = !isSatelliteMode
            if (isSatelliteMode) {
                setSatelliteMapMode()
                Toast.makeText(requireContext(), "🛰️ Mode Peta Satelit HD Aktif", Toast.LENGTH_SHORT).show()
            } else {
                setStreetMapMode()
                Toast.makeText(requireContext(), "🗺️ Mode Peta Google Street HD Aktif", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnUseMapForJemput.setOnClickListener {
            targetSettingField = "jemput"
            Toast.makeText(requireContext(), "🟢 Geser peta untuk tentukan Titik Jemput", Toast.LENGTH_SHORT).show()
        }

        binding.btnUseMapForTujuan.setOnClickListener {
            targetSettingField = "tujuan"
            Toast.makeText(requireContext(), "🔴 Geser peta untuk tentukan Titik Tujuan", Toast.LENGTH_SHORT).show()
        }

        binding.fabMyLocation.setOnClickListener {
            checkLocationPermissionAndMove()
        }

        binding.btnBatal.setOnClickListener {
            if (activeOrderId.isNotBlank() && tripPhase != TripPhase.IDLE && tripPhase != TripPhase.TRIP_COMPLETED) {
                FirebaseRepository.updateOrderStatus(activeOrderId, OrderModel.STATUS_CANCELLED)
            }
            resetTripState()
            findNavController().navigateUp()
        }

        binding.btnPesan.setOnClickListener {
            val jemput = binding.spJemput.text.toString().trim()
            val tujuan = binding.spTujuan.text.toString().trim()

            if (jemput.isEmpty() || tujuan.isEmpty()) {
                Toast.makeText(requireContext(), "Harap isi titik jemput dan tujuan terlebih dahulu!", Toast.LENGTH_SHORT).show()
            } else if (jemput.equals(tujuan, ignoreCase = true)) {
                Toast.makeText(requireContext(), "Titik jemput dan tujuan tidak boleh sama!", Toast.LENGTH_SHORT).show()
            } else {
                startOrderMatchingWorkflow()
            }
        }
    }

    private fun observeUserBalance() {
        val uid = FirebaseRepository.currentUid() ?: return
        userListener?.remove()
        userListener = FirebaseRepository.observeUserProfile(uid) { profile ->
            if (!isAdded || _binding == null || profile == null) return@observeUserProfile
            val formatted = String.format(Locale.US, "%,d", profile.balance).replace(',', '.')
            binding.rbKampusPay.text = "KampusPay\nSisa: Rp $formatted"
        }
    }

    private fun setupOverlayChatListeners() {
        binding.tvOverlayDriverName.text = "$activeDriverName (★ 4.9)"

        binding.btnBackOverlayChat.setOnClickListener {
            binding.chatOverlayContainer.visibility = View.GONE
            showInitialConfirmationDialog()
        }

        binding.btnOverlaySendChat.setOnClickListener {
            val text = binding.etOverlayChatMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendOverlayLiveMessage(text)
                binding.etOverlayChatMessage.setText("")
            }
        }

        binding.chipOverlayReply1.setOnClickListener {
            sendOverlayLiveMessage(binding.chipOverlayReply1.text.toString())
        }

        binding.chipOverlayReply2.setOnClickListener {
            sendOverlayLiveMessage(binding.chipOverlayReply2.text.toString())
        }
    }

    private fun observeOverlayChat(orderId: String) {
        if (orderId.isBlank()) return
        overlayChatListener?.remove()
        overlayChatListener = FirebaseRepository.observeChatMessages(orderId) { messages ->
            if (!isAdded || _binding == null) return@observeChatMessages
            val initialChildCount = 1.coerceAtMost(binding.chatOverlayMessageContainer.childCount)
            ChatBubbleHelper.renderMessages(
                requireContext(),
                binding.chatOverlayMessageContainer,
                binding.chatOverlayScrollView,
                messages,
                keepFirstChildCount = initialChildCount
            )
        }
    }

    private fun sendOverlayLiveMessage(message: String) {
        val uid = FirebaseRepository.currentUid()
        if (uid == null || activeOrderId.isBlank()) {
            Toast.makeText(requireContext(), "Pesanan belum terhubung ke Firebase.", Toast.LENGTH_SHORT).show()
            return
        }
        val msg = ChatMessage(
            orderId = activeOrderId,
            senderId = uid,
            senderName = FirebaseRepository.currentUser()?.displayName ?: "Customer",
            senderRole = "customer",
            text = message,
            timestamp = System.currentTimeMillis()
        )
        FirebaseRepository.sendChatMessage(activeOrderId, msg) { sent ->
            if (sent && isAdded && _binding != null) {
                simulateOverlayDriverReply(activeOrderId)
            }
        }
    }

    private fun simulateOverlayDriverReply(orderId: String) {
        mainHandler.postDelayed({
            if (!isAdded || _binding == null || activeOrderId != orderId) return@postDelayed
            val replies = listOf(
                "Siap kak! Saya meluncur ke titik lokasi sekarang.",
                "Baik kak, posisi saya sudah dekat sekitar 200 meter lagi.",
                "Siap, saya tunggu di titik jemput depan pos ya kak.",
                "Oke kak, siap laksanakan!"
            )
            val driverMsg = ChatMessage(
                orderId = orderId,
                senderId = activeDriverId,
                senderName = activeDriverName,
                senderRole = "driver",
                text = replies.random(),
                timestamp = System.currentTimeMillis()
            )
            FirebaseRepository.sendChatMessage(orderId, driverMsg)
        }, 1000)
    }

    override fun onResume() {
        super.onResume()
        if (isAdded && !isDetached && _binding != null) {
            binding.osmMapView.onResume()
            if (tripPhase == TripPhase.WAITING_CONFIRMATION && binding.chatOverlayContainer.visibility != View.VISIBLE) {
                view?.postDelayed({
                    if (isAdded && !isDetached && _binding != null && tripPhase == TripPhase.WAITING_CONFIRMATION) {
                        showInitialConfirmationDialog()
                    }
                }, 250)
            }
        }
    }

    override fun onPause() {
        if (_binding != null) {
            binding.osmMapView.onPause()
        }
        super.onPause()
    }

    private fun startOrderMatchingWorkflow() {
        val uid = FirebaseRepository.currentUid()
        if (uid == null) {
            Toast.makeText(requireContext(), "Silakan login terlebih dahulu!", Toast.LENGTH_SHORT).show()
            return
        }

        activeJemput = binding.spJemput.text.toString().trim()
        activeTujuan = binding.spTujuan.text.toString().trim()
        activeCatatan = binding.etCatatan.text.toString().trim()
        activeServiceName = if (selectedServiceType == "Motor") "Anjem Motor" else "Kurir / Titip Beli"
        activePaymentMethod = if (binding.rgPayment.checkedRadioButtonId == R.id.rbCash) "Tunai" else "KampusPay"

        val driverIds = listOf("driver_ahmad_fauzi", "driver_bayu_pratama", "driver_rizki_mahendra", "driver_eko_prasetyo")
        val driverNames = listOf("Ahmad Fauzi", "Bayu Pratama", "Rizki Mahendra", "Eko Prasetyo")
        val plates = listOf("H 2341 XY", "H 5432 AB", "H 1289 ZC", "H 9876 KL")
        val randomIdx = driverNames.indices.random()
        activeDriverId = driverIds[randomIdx]
        activeDriverName = driverNames[randomIdx]
        activePlateNumber = plates[randomIdx]
        binding.tvOverlayDriverName.text = "$activeDriverName (★ 4.9)"

        val newOrder = OrderModel(
            customerId = uid,
            customerName = FirebaseRepository.currentUser()?.displayName ?: "Mahasiswa UNNES",
            driverId = activeDriverId,
            driverName = activeDriverName,
            plateNumber = activePlateNumber,
            serviceType = activeServiceName,
            pickupName = activeJemput,
            pickupLat = pickupGeoPoint.latitude,
            pickupLng = pickupGeoPoint.longitude,
            destName = activeTujuan,
            destLat = destinationGeoPoint.latitude,
            destLng = destinationGeoPoint.longitude,
            notes = activeCatatan,
            distanceKm = lastDistanceKm,
            fare = lastFare,
            paymentMethod = activePaymentMethod,
            status = OrderModel.STATUS_WAITING
        )

        binding.btnPesan.isEnabled = false
        binding.btnPesan.text = "Menghubungkan..."
        FirebaseRepository.createOrder(newOrder) { result ->
            if (!isAdded || _binding == null) return@createOrder
            binding.btnPesan.isEnabled = true
            binding.btnPesan.text = "➔ Pesan Sekarang"
            result.onSuccess { created ->
                activeOrderId = created.orderId
                tripPhase = TripPhase.WAITING_CONFIRMATION
                observeLiveOrderAndDriver()
                observeOverlayChat(created.orderId)
                showInitialConfirmationDialog()
            }.onFailure { err ->
                Toast.makeText(requireContext(), "Gagal membuat pesanan: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun observeLiveOrderAndDriver() {
        if (activeOrderId.isBlank()) return
        orderListener?.remove()
        orderListener = FirebaseRepository.observeOrder(activeOrderId) { order ->
            if (!isAdded || _binding == null || order == null) return@observeOrder
            if (order.status == OrderModel.STATUS_CANCELLED && tripPhase != TripPhase.IDLE) {
                resetTripState()
            }
        }

        driverLocListener?.remove()
        driverLocListener = FirebaseRepository.observeDriverLocation(activeDriverId) { loc ->
            if (!isAdded || _binding == null || loc == null || !isTrackingActive) return@observeDriverLocation
            if (loc.lat == 0.0 && loc.lng == 0.0) return@observeDriverLocation

            val currentPos = GeoPoint(loc.lat, loc.lng)
            if (markerDriver == null) {
                markerDriver = Marker(binding.osmMapView).apply {
                    title = "🛵 Driver: ${loc.driverName} (${loc.plateNumber})"
                }
            }
            if (!binding.osmMapView.overlays.contains(markerDriver)) {
                binding.osmMapView.overlays.add(markerDriver)
            }
            markerDriver?.position = currentPos
            binding.osmMapView.controller.animateTo(currentPos)
            binding.osmMapView.invalidate()
        }
    }

    private fun showInitialConfirmationDialog() {
        if (!isAdded || isDetached || _binding == null || binding.chatOverlayContainer.visibility == View.VISIBLE) return

        val message = """
            Driver $activeServiceName Ditemukan!
            
            👤 Driver: $activeDriverName (★ 4.9)
            🏍️ Kendaraan: Honda Beat ($activePlateNumber)
            
            📍 Jemput: $activeJemput
            🏁 Tujuan: $activeTujuan
            💳 Pembayaran: $activePaymentMethod
            ${if (activeCatatan.isNotBlank()) "📝 Catatan: $activeCatatan" else ""}
            
            Silakan mulai perjalanan atau hubungi driver.
        """.trimIndent()

        try {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Konfirmasi Pesanan $activeServiceName")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("🚀 Mulai Perjalanan") { dialog, _ ->
                    dialog.dismiss()
                    if (isAdded && _binding != null) {
                        tripPhase = TripPhase.DRIVER_TO_PICKUP
                        FirebaseRepository.updateOrderStatus(activeOrderId, OrderModel.STATUS_DRIVER_TO_PICKUP)
                        startRandomDriverToPickupAnimation()
                    }
                }
                .setNeutralButton("💬 Chat Driver") { dialog, _ ->
                    dialog.dismiss()
                    if (isAdded && _binding != null) {
                        binding.chatOverlayContainer.visibility = View.VISIBLE
                    }
                }
                .setNegativeButton("❌ Batalkan Pesanan") { dialog, _ ->
                    FirebaseRepository.updateOrderStatus(activeOrderId, OrderModel.STATUS_CANCELLED)
                    resetTripState()
                    dialog.dismiss()
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Pesanan dibatalkan.", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startRandomDriverToPickupAnimation() {
        if (!isAdded || _binding == null) return
        val randomLatOffset = (Math.random() - 0.5) * 0.009
        val randomLngOffset = (Math.random() - 0.5) * 0.009
        val driverStartPoint = GeoPoint(pickupGeoPoint.latitude + randomLatOffset, pickupGeoPoint.longitude + randomLngOffset)

        val pickupWaypoints = mutableListOf<GeoPoint>()
        val steps = 8
        for (i in 0..steps) {
            val fraction = i.toDouble() / steps
            val lat = driverStartPoint.latitude + (pickupGeoPoint.latitude - driverStartPoint.latitude) * fraction
            val lng = driverStartPoint.longitude + (pickupGeoPoint.longitude - driverStartPoint.longitude) * fraction
            pickupWaypoints.add(GeoPoint(lat, lng))
        }

        isTrackingActive = true
        var stepIndex = 0

        if (markerDriver == null) {
            markerDriver = Marker(binding.osmMapView).apply {
                title = "🛵 Driver: $activeDriverName ($activePlateNumber)"
                snippet = "Menuju titik jemput Anda (maks 0.5 km)"
            }
        }

        if (!binding.osmMapView.overlays.contains(markerDriver)) {
            binding.osmMapView.overlays.add(markerDriver)
        }

        Toast.makeText(requireContext(), "🛵 Driver $activeDriverName meluncur ke titik jemput Anda...", Toast.LENGTH_LONG).show()

        trackingRunnable = object : Runnable {
            override fun run() {
                if (!isTrackingActive || !isAdded || _binding == null) {
                    return
                }
                if (stepIndex >= pickupWaypoints.size) {
                    startTripToDestinationAnimation()
                    return
                }

                val currentPos = pickupWaypoints[stepIndex]
                publishDriverPositionToFirebase(currentPos, OrderModel.STATUS_DRIVER_TO_PICKUP)
                binding.tvMapH3Info.text = "🛵 Driver $activeDriverName menuju titik jemput..."

                stepIndex++
                trackingHandler.postDelayed(this, 180)
            }
        }

        trackingHandler.post(trackingRunnable!!)
    }

    private fun startTripToDestinationAnimation() {
        if (!isTrackingActive || !isAdded || _binding == null) return
        tripPhase = TripPhase.TRIP_TO_DESTINATION
        FirebaseRepository.updateOrderStatus(activeOrderId, OrderModel.STATUS_ON_TRIP)
        if (currentWaypoints.isEmpty()) {
            currentWaypoints = listOf(pickupGeoPoint, destinationGeoPoint)
        }

        isTrackingActive = true
        var stepIndex = 0

        if (markerDriver == null) {
            markerDriver = Marker(binding.osmMapView).apply {
                title = "🛵 Driver: $activeDriverName ($activePlateNumber)"
                snippet = "Menuju tujuan"
            }
        }

        if (!binding.osmMapView.overlays.contains(markerDriver)) {
            binding.osmMapView.overlays.add(markerDriver)
        }

        trackingRunnable = object : Runnable {
            override fun run() {
                if (!isTrackingActive || !isAdded || _binding == null) {
                    return
                }
                if (stepIndex >= currentWaypoints.size) {
                    onTripCompleted()
                    return
                }

                val currentPos = currentWaypoints[stepIndex]
                publishDriverPositionToFirebase(currentPos, OrderModel.STATUS_ON_TRIP)

                val percent = (stepIndex + 1) * 100 / currentWaypoints.size
                binding.tvMapH3Info.text = "🛵 Perjalanan ke Tujuan • $percent%"

                stepIndex++
                trackingHandler.postDelayed(this, 150)
            }
        }

        trackingHandler.post(trackingRunnable!!)
    }

    private fun publishDriverPositionToFirebase(pos: GeoPoint, phase: String) {
        val location = DriverLocation(
            driverId = activeDriverId,
            driverName = activeDriverName,
            plateNumber = activePlateNumber,
            lat = pos.latitude,
            lng = pos.longitude,
            h3Index = H3GeoUtils.getH3Index(pos.latitude, pos.longitude),
            phase = phase,
            orderId = activeOrderId,
            updatedAt = System.currentTimeMillis()
        )
        FirebaseRepository.updateDriverLocation(location)
    }

    private fun onTripCompleted() {
        stopLiveDriverAnimation()
        tripPhase = TripPhase.TRIP_COMPLETED
        postTripSecondsLeft = 30
        FirebaseRepository.updateOrderStatus(activeOrderId, OrderModel.STATUS_COMPLETED)

        val uid = FirebaseRepository.currentUid()
        if (uid != null && activeOrderId.isNotBlank() && fareDeductedForOrderId != activeOrderId && activePaymentMethod == "KampusPay") {
            fareDeductedForOrderId = activeOrderId
            FirebaseRepository.adjustBalance(uid, -lastFare)
        }

        if (!isAdded || _binding == null) return
        binding.tvMapH3Info.text = "🎉 Perjalanan Selesai! Terima kasih."

        postTripRunnable?.let { postTripHandler.removeCallbacks(it) }
        postTripRunnable = object : Runnable {
            override fun run() {
                if (postTripSecondsLeft > 0 && tripPhase == TripPhase.TRIP_COMPLETED) {
                    postTripSecondsLeft--
                    postTripHandler.postDelayed(this, 1000)
                } else {
                    tripPhase = TripPhase.IDLE
                }
            }
        }
        postTripHandler.post(postTripRunnable!!)

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Perjalanan Selesai! 🎉")
            .setMessage("Anda telah tiba di tujuan ($activeTujuan).\n\nTombol Chat Driver pos-perjalanan (30 dtk) tersedia di menu Home.")
            .setPositiveButton("Selesai & Beri Ulasan") { _, _ ->
                resetTripState()
                if (isAdded) {
                    findNavController().navigate(R.id.action_order_to_chat)
                }
            }
            .show()
    }

    private fun resetTripState() {
        tripPhase = TripPhase.IDLE
        stopLiveDriverAnimation()
        postTripRunnable?.let { postTripHandler.removeCallbacks(it) }
    }

    private fun stopLiveDriverAnimation() {
        isTrackingActive = false
        trackingRunnable?.let { trackingHandler.removeCallbacks(it) }
        if (_binding != null) {
            markerDriver?.let { binding.osmMapView.overlays.remove(it) }
            binding.osmMapView.invalidate()
        }
    }

    private fun setupEditorActionListeners() {
        binding.spJemput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val text = v.text.toString().trim()
                if (text.isNotEmpty()) {
                    geocodeTypedTextAndMoveMap(text, isJemput = true)
                }
                hideKeyboard(v)
                true
            } else {
                false
            }
        }

        binding.spTujuan.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val text = v.text.toString().trim()
                if (text.isNotEmpty()) {
                    geocodeTypedTextAndMoveMap(text, isJemput = false)
                }
                hideKeyboard(v)
                true
            } else {
                false
            }
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun geocodeTypedTextAndMoveMap(text: String, isJemput: Boolean) {
        val campusMap = mapOf(
            "Gerbang Utama" to GeoPoint(-7.0475, 110.4072),
            "Banaran" to GeoPoint(-7.0531, 110.4085),
            "Fakultas Teknik" to GeoPoint(-7.0490, 110.4060),
            "Teknik" to GeoPoint(-7.0490, 110.4060),
            "Gedung Rektorat" to GeoPoint(-7.0482, 110.4080),
            "Rektorat" to GeoPoint(-7.0482, 110.4080),
            "Fakultas MIPA" to GeoPoint(-7.0465, 110.4050),
            "MIPA" to GeoPoint(-7.0465, 110.4050),
            "Fakultas Bahasa" to GeoPoint(-7.0460, 110.4088),
            "Asrama" to GeoPoint(-7.0515, 110.4110),
            "Perpustakaan" to GeoPoint(-7.0478, 110.4075)
        )

        val matchedKey = campusMap.keys.find { text.contains(it, ignoreCase = true) }
        if (matchedKey != null) {
            val point = campusMap[matchedKey]!!
            if (isJemput) {
                pickupGeoPoint = point
            } else {
                destinationGeoPoint = point
            }
            binding.osmMapView.controller.animateTo(point)
            updateMapOverlaysAndRoute()
            Toast.makeText(requireContext(), "📍 Peta diarahkan ke $matchedKey", Toast.LENGTH_SHORT).show()
            return
        }

        val appContext = context?.applicationContext ?: return
        Thread {
            try {
                val geocoder = Geocoder(appContext, Locale.getDefault())
                val query = if (text.contains("UNNES", true) || text.contains("Semarang", true)) text else "$text, UNNES, Semarang"
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val point = GeoPoint(addr.latitude, addr.longitude)
                    mainHandler.post {
                        if (!isAdded || _binding == null) return@post
                        if (isJemput) {
                            pickupGeoPoint = point
                        } else {
                            destinationGeoPoint = point
                        }
                        binding.osmMapView.controller.animateTo(point)
                        updateMapOverlaysAndRoute()
                        Toast.makeText(requireContext(), "📍 Peta diarahkan ke lokasi yang dicari", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun setupMapView() {
        setStreetMapMode()
        binding.osmMapView.setMultiTouchControls(true)
        binding.osmMapView.isTilesScaledToDpi = true
        binding.osmMapView.maxZoomLevel = 19.0
        binding.osmMapView.minZoomLevel = 4.0
        binding.osmMapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

        val controller = binding.osmMapView.controller
        controller.setZoom(16.5)
        controller.setCenter(pickupGeoPoint)

        binding.osmMapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                scheduleDebouncedMapUpdate()
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                scheduleDebouncedMapUpdate()
                return true
            }
        })

        updateMapOverlaysAndRoute()
    }

    private val googleMapsStreetSource = object : OnlineTileSourceBase(
        "GoogleMapsStreet",
        0, 20, 256, ".png",
        arrayOf(
            "https://mt0.google.com/vt/lyrs=m&x=",
            "https://mt1.google.com/vt/lyrs=m&x=",
            "https://mt2.google.com/vt/lyrs=m&x=",
            "https://mt3.google.com/vt/lyrs=m&x="
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return baseUrl + MapTileIndex.getX(pMapTileIndex) +
                    "&y=" + MapTileIndex.getY(pMapTileIndex) +
                    "&z=" + MapTileIndex.getZoom(pMapTileIndex)
        }
    }

    private val googleMapsSatelliteSource = object : OnlineTileSourceBase(
        "GoogleMapsSatellite",
        0, 20, 256, ".png",
        arrayOf(
            "https://mt0.google.com/vt/lyrs=y&x=",
            "https://mt1.google.com/vt/lyrs=y&x=",
            "https://mt2.google.com/vt/lyrs=y&x=",
            "https://mt3.google.com/vt/lyrs=y&x="
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return baseUrl + MapTileIndex.getX(pMapTileIndex) +
                    "&y=" + MapTileIndex.getY(pMapTileIndex) +
                    "&z=" + MapTileIndex.getZoom(pMapTileIndex)
        }
    }

    private fun setStreetMapMode() {
        binding.osmMapView.tileProvider.clearTileCache()
        binding.osmMapView.setTileSource(googleMapsStreetSource)
        binding.osmMapView.invalidate()
    }

    private fun setSatelliteMapMode() {
        binding.osmMapView.tileProvider.clearTileCache()
        binding.osmMapView.setTileSource(googleMapsSatelliteSource)
        binding.osmMapView.invalidate()
    }

    private fun scheduleDebouncedMapUpdate() {
        if (isTrackingActive || tripPhase != TripPhase.IDLE || _binding == null) return
        pendingDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDebounceRunnable = Runnable {
            onMapMoved()
        }
        mainHandler.postDelayed(pendingDebounceRunnable!!, 400)
    }

    private fun checkLocationPermissionAndMove() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            moveToCurrentGpsLocation()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun moveToCurrentGpsLocation() {
        try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (!isAdded || _binding == null || location == null) return@addOnSuccessListener
                val currentPoint = GeoPoint(location.latitude, location.longitude)
                binding.osmMapView.controller.animateTo(currentPoint)
                pickupGeoPoint = currentPoint
                updateMapOverlaysAndRoute()
                resolveAddressAsync(pickupGeoPoint) { address ->
                    if (!isAdded || _binding == null) return@resolveAddressAsync
                    setTextProgrammatically(isJemput = true, address)
                    Toast.makeText(requireContext(), "📍 Titik jemput default diatur dari lokasi GPS Anda", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun setTextProgrammatically(isJemput: Boolean, text: String) {
        if (_binding == null) return
        isProgrammaticTextUpdate = true
        if (isJemput) {
            binding.spJemput.setText(text)
        } else {
            binding.spTujuan.setText(text)
        }
        isProgrammaticTextUpdate = false
    }

    private fun onMapMoved() {
        if (isTrackingActive || tripPhase != TripPhase.IDLE || !isAdded || _binding == null) return
        val center = binding.osmMapView.mapCenter as GeoPoint
        val h3Index = H3GeoUtils.getH3Index(center.latitude, center.longitude)

        if (targetSettingField == "jemput") {
            pickupGeoPoint = center
        } else {
            destinationGeoPoint = center
        }

        binding.tvMapH3Info.text = "⚡ H3 Index: $h3Index"
        updateMapOverlaysAndRoute()

        resolveAddressAsync(center) { address ->
            if (!isAdded || _binding == null) return@resolveAddressAsync
            setTextProgrammatically(isJemput = (targetSettingField == "jemput"), address)
        }
    }

    private fun resolveAddressAsync(point: GeoPoint, onResult: (String) -> Unit) {
        val fallback = String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", point.latitude, point.longitude)
        val appContext = context?.applicationContext
        if (appContext == null) {
            onResult(fallback)
            return
        }
        Thread {
            var resolved = fallback
            try {
                val geocoder = Geocoder(appContext, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(point.latitude, point.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    resolved = addr.getAddressLine(0) ?: "${addr.featureName ?: "Lokasi"}, ${addr.subLocality ?: "UNNES"}"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            mainHandler.post { onResult(resolved) }
        }.start()
    }

    private fun updateMapOverlaysAndRoute() {
        if (!isAdded || _binding == null) return
        binding.osmMapView.overlays.clear()

        // 1. Add Pickup Marker (🟢 Green)
        markerPickup = Marker(binding.osmMapView)
        markerPickup?.position = pickupGeoPoint
        markerPickup?.title = "🟢 Jemput: ${binding.spJemput.text}"
        binding.osmMapView.overlays.add(markerPickup)

        // 2. Add Destination Marker (🔴 Red)
        markerDest = Marker(binding.osmMapView)
        markerDest?.position = destinationGeoPoint
        markerDest?.title = "🔴 Tujuan: ${binding.spTujuan.text}"
        binding.osmMapView.overlays.add(markerDest)

        // Re-add Driver Marker if tracking is active
        markerDriver?.let {
            if (!binding.osmMapView.overlays.contains(it)) {
                binding.osmMapView.overlays.add(it)
            }
        }

        // 3. Fetch Real Turn-by-Turn Road Route (OSRM Engine)
        fetchOSRMDriverRoute(pickupGeoPoint, destinationGeoPoint)
    }

    private fun fetchOSRMDriverRoute(start: GeoPoint, end: GeoPoint) {
        Thread {
            val waypoints = mutableListOf<GeoPoint>()
            var distanceMeters = 0.0
            var success = false

            val endpoints = listOf(
                "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson",
                "https://routing.openstreetmap.de/routed-car/route/v1/driving/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
            )

            for (urlString in endpoints) {
                try {
                    val url = URL(urlString)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; AnjemUNNES/1.0)")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000

                    if (conn.responseCode == 200) {
                        val stream = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(stream)
                        val routes = json.getJSONArray("routes")
                        if (routes.length() > 0) {
                            val route = routes.getJSONObject(0)
                            distanceMeters = route.getDouble("distance")
                            val geometry = route.getJSONObject("geometry")
                            val coordinates = geometry.getJSONArray("coordinates")

                            for (i in 0 until coordinates.length()) {
                                val coord = coordinates.getJSONArray(i)
                                val lng = coord.getDouble(0)
                                val lat = coord.getDouble(1)
                                waypoints.add(GeoPoint(lat, lng))
                            }
                            success = true
                            break
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (success && waypoints.isNotEmpty()) {
                currentWaypoints = waypoints
                mainHandler.post {
                    drawRoadRoutePolyline(waypoints, distanceMeters)
                }
            } else {
                val curvedWaypoints = generateRoadInterpolatedWaypoints(start, end)
                currentWaypoints = curvedWaypoints
                val results = FloatArray(1)
                android.location.Location.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude, results)
                mainHandler.post {
                    drawRoadRoutePolyline(curvedWaypoints, results[0].toDouble() * 1.22)
                }
            }
        }.start()
    }

    private fun setupServiceToggle() {
        binding.btnTypeMotor.setOnClickListener {
            selectedServiceType = "Motor"
            binding.btnTypeMotor.setBackgroundResource(R.drawable.bg_selected_primary)
            binding.imgMotor.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.palette_normal))
            binding.tvMotor.setTextColor(ContextCompat.getColor(requireContext(), R.color.palette_normal))

            binding.btnTypeKurir.setBackgroundResource(R.drawable.bg_border_card)
            binding.imgKurir.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            binding.tvKurir.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

            updateMapOverlaysAndRoute()
        }

        binding.btnTypeKurir.setOnClickListener {
            selectedServiceType = "Kurir"
            binding.btnTypeKurir.setBackgroundResource(R.drawable.bg_selected_primary)
            binding.imgKurir.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.palette_normal))
            binding.tvKurir.setTextColor(ContextCompat.getColor(requireContext(), R.color.palette_normal))

            binding.btnTypeMotor.setBackgroundResource(R.drawable.bg_border_card)
            binding.imgMotor.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            binding.tvMotor.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

            updateMapOverlaysAndRoute()
        }
    }

    private fun drawRoadRoutePolyline(waypoints: List<GeoPoint>, distanceMeters: Double) {
        if (!isAdded || _binding == null) return
        if (routeLine != null) {
            binding.osmMapView.overlays.remove(routeLine)
        }

        routeLine = Polyline().apply {
            setPoints(waypoints)
            outlinePaint.color = Color.parseColor("#0088CC")
            outlinePaint.strokeWidth = 12f
            outlinePaint.strokeCap = Paint.Cap.ROUND
        }

        binding.osmMapView.overlays.add(0, routeLine)
        binding.osmMapView.invalidate()

        val distanceKm = distanceMeters / 1000.0
        val roundedDist = String.format(Locale.US, "%.1f", distanceKm)
        val minutes = ((distanceKm / 25.0) * 60).toInt().coerceAtLeast(2)

        val baseFare = if (selectedServiceType == "Motor") 5000 else 7000
        val perKmRate = if (selectedServiceType == "Motor") 2500 else 3500
        val totalPrice = baseFare + (distanceKm * perKmRate).toInt()
        lastDistanceKm = roundedDist.toDoubleOrNull() ?: distanceKm
        lastFare = totalPrice.toLong()
        val formattedPrice = String.format(Locale("id", "ID"), "Rp %,d", totalPrice)

        binding.tvJarak.text = "Estimasi Jarak: $roundedDist km (Real Map)"
        binding.tvWaktu.text = "Waktu tempuh sekitar $minutes menit"
        binding.tvHarga.text = formattedPrice
    }

    override fun onDestroyView() {
        userListener?.remove()
        userListener = null
        orderListener?.remove()
        orderListener = null
        driverLocListener?.remove()
        driverLocListener = null
        overlayChatListener?.remove()
        overlayChatListener = null
        stopLiveDriverAnimation()
        postTripRunnable?.let { postTripHandler.removeCallbacks(it) }
        pendingDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }

    private fun generateRoadInterpolatedWaypoints(start: GeoPoint, end: GeoPoint): List<GeoPoint> {
        val list = mutableListOf<GeoPoint>()
        val steps = 25
        for (i in 0..steps) {
            val fraction = i.toDouble() / steps
            val lat = start.latitude + (end.latitude - start.latitude) * fraction
            val lng = start.longitude + (end.longitude - start.longitude) * fraction
            val curveOffset = sin(fraction * Math.PI) * 0.0015
            list.add(GeoPoint(lat + curveOffset, lng - curveOffset))
        }
        return list
    }
}
