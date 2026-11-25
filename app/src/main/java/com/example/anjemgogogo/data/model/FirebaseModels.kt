package com.example.anjemgogogo.data.model

import com.google.firebase.database.IgnoreExtraProperties
import com.google.firebase.database.PropertyName

@IgnoreExtraProperties
data class UserProfile(
    var uid: String = "",
    var name: String = "",
    var email: String = "",
    var photoUrl: String = "",
    var phone: String = "-",
    var nimOrFaculty: String = "Mahasiswa UNNES",
    var balance: Long = 45000L,
    var role: String = ROLE_PASSENGER, // "customer" | "driver"
    var vehicleModel: String = "Honda Beat",
    var vehiclePlate: String = "H 2341 XY",
    @get:PropertyName("isOnline")
    @set:PropertyName("isOnline")
    var isOnline: Boolean = false,
    var createdAt: Long = 0L
) {
    companion object {
        const val ROLE_PASSENGER = "customer"
        const val ROLE_DRIVER = "driver"
    }
}

@IgnoreExtraProperties
data class OrderModel(
    var orderId: String = "",
    var customerId: String = "",
    var customerName: String = "",
    var driverId: String = "",
    var driverName: String = "",
    var plateNumber: String = "",
    var serviceType: String = "Anjem Motor",
    var pickupName: String = "",
    var pickupLat: Double = 0.0,
    var pickupLng: Double = 0.0,
    var destName: String = "",
    var destLat: Double = 0.0,
    var destLng: Double = 0.0,
    var notes: String = "",
    var distanceKm: Double = 0.0,
    var fare: Long = 0L,
    var paymentMethod: String = "KampusPay",
    var status: String = STATUS_SEARCHING,
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L
) {
    companion object {
        const val STATUS_SEARCHING = "WAITING_DRIVER"
        const val STATUS_WAITING = "WAITING_CONFIRMATION"
        const val STATUS_DRIVER_TO_PICKUP = "DRIVER_TO_PICKUP"
        const val STATUS_ON_TRIP = "TRIP_TO_DESTINATION"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}

@IgnoreExtraProperties
data class DriverLocation(
    var driverId: String = "",
    var driverName: String = "",
    var plateNumber: String = "",
    var lat: Double = 0.0,
    var lng: Double = 0.0,
    var h3Index: String = "",
    var phase: String = "",
    var orderId: String = "",
    var updatedAt: Long = 0L
)

@IgnoreExtraProperties
data class ChatMessage(
    var messageId: String = "",
    var orderId: String = "",
    var senderId: String = "",
    var senderName: String = "",
    var senderRole: String = "customer", // "customer" | "driver" | "system"
    var text: String = "",
    var timestamp: Long = 0L
)

@IgnoreExtraProperties
data class AppNotification(
    var notifId: String = "",
    var title: String = "",
    var message: String = "",
    var tag: String = "Info",
    var orderId: String = "",
    var timestamp: Long = 0L
)
