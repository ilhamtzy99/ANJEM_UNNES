package com.example.anjemgogogo.ui.profile

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.anjemgogogo.R
import com.example.anjemgogogo.data.firebase.FirebaseRepository
import com.example.anjemgogogo.databinding.FragmentProfileBinding
import com.example.anjemgogogo.ui.booking.OrderFragment
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions

class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private var userListener: FirebaseRepository.ListenerHandle? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentProfileBinding.bind(view)

        // Setup Bottom Navigation
        binding.bottomNavigation.selectedItemId = R.id.nav_profile
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    findNavController().navigate(R.id.action_profile_to_home)
                    true
                }
                R.id.nav_orders -> {
                    findNavController().navigate(R.id.action_profile_to_orders)
                    true
                }
                R.id.nav_chat -> {
                    findNavController().navigate(R.id.action_profile_to_chat)
                    true
                }
                R.id.nav_profile -> true
                else -> false
            }
        }

        observeUserProfile()

        binding.btnWhatsapp.setOnClickListener {
            showEditPhoneDialog()
        }

        binding.btnEditProfile.setOnClickListener {
            showEditProfileDialog()
        }

        binding.btnBantuan.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Pusat Bantuan Anjem UNNES")
                .setMessage("Layanan khusus ojek & kurir mahasiswa UNNES Sekaran.\nHubungi Admin Kampus: 0812-3456-7890 (WhatsApp)")
                .setPositiveButton("Tutup", null)
                .show()
        }

        binding.btnSyarat.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Syarat & Ketentuan")
                .setMessage("1. Layanan khusus mahasiswa & civitas akademika UNNES Sekaran.\n2. Pembayaran dapat menggunakan KampusPay atau Tunai.\n3. Harap menjaga sopan santun dan keselamatan bersama.")
                .setPositiveButton("Mengerti", null)
                .show()
        }

        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Keluar Akun")
                .setMessage("Apakah Anda yakin ingin keluar dari Anjem UNNES?")
                .setPositiveButton("Ya, Keluar") { _, _ ->
                    performLogout()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun observeUserProfile() {
        val authUser = FirebaseRepository.currentUser()
        if (authUser != null) {
            binding.tvProfileName.text = authUser.displayName ?: "Mahasiswa UNNES"
            binding.tvProfileEmail.text = authUser.email.orEmpty()
            binding.tvProfileEmail.visibility = if (authUser.email.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvProfileInitials.text = computeInitials(binding.tvProfileName.text.toString())
        }

        val uid = FirebaseRepository.currentUid() ?: return
        userListener?.remove()
        userListener = FirebaseRepository.observeUserProfile(uid) { profile ->
            if (!isAdded || _binding == null || profile == null) return@observeUserProfile
            val name = profile.name.ifBlank { "Mahasiswa UNNES" }
            binding.tvProfileName.text = name
            binding.tvProfileInitials.text = computeInitials(name)
            binding.tvProfileNim.text = profile.nimOrFaculty.ifBlank { "Mahasiswa UNNES" }
            binding.tvProfilePhone.text = profile.phone.ifBlank { "-" }
            if (profile.email.isNotBlank()) {
                binding.tvProfileEmail.text = profile.email
                binding.tvProfileEmail.visibility = View.VISIBLE
            }
        }
    }

    private fun showEditProfileDialog() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etName = EditText(requireContext()).apply {
            hint = "Nama Lengkap"
            setText(binding.tvProfileName.text)
        }
        val etAcademic = EditText(requireContext()).apply {
            hint = "Prodi / Fakultas (cth: Teknik Komputer • FT)"
            setText(binding.tvProfileNim.text)
        }
        container.addView(etName)
        container.addView(etAcademic)

        AlertDialog.Builder(requireContext())
            .setTitle("Edit Profil & Info Akademik")
            .setView(container)
            .setPositiveButton("Simpan") { _, _ ->
                val newName = etName.text.toString().trim()
                val newAcademic = etAcademic.text.toString().trim()
                val uid = FirebaseRepository.currentUid()
                if (newName.isNotEmpty() && uid != null) {
                    val updates = mutableMapOf<String, Any>("name" to newName)
                    if (newAcademic.isNotEmpty()) updates["nimOrFaculty"] = newAcademic
                    FirebaseRepository.updateUserFields(uid, updates) { ok, err ->
                        if (!isAdded) return@updateUserFields
                        if (ok) {
                            Toast.makeText(requireContext(), "Profil berhasil diperbarui!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), "Gagal menyimpan: $err", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showEditPhoneDialog() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etPhone = EditText(requireContext()).apply {
            hint = "Nomor WhatsApp (cth: 081234567890)"
            val current = binding.tvProfilePhone.text.toString()
            if (current != "-") setText(current)
        }
        container.addView(etPhone)

        AlertDialog.Builder(requireContext())
            .setTitle("Perbarui Nomor WhatsApp")
            .setView(container)
            .setPositiveButton("Simpan") { _, _ ->
                val newPhone = etPhone.text.toString().trim()
                val uid = FirebaseRepository.currentUid()
                if (newPhone.isNotEmpty() && uid != null) {
                    FirebaseRepository.updateUserFields(uid, mapOf("phone" to newPhone)) { ok, _ ->
                        if (isAdded && ok) {
                            Toast.makeText(requireContext(), "Nomor WhatsApp diperbarui!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun performLogout() {
        FirebaseRepository.signOut()
        val webClientId = FirebaseRepository.getGoogleWebClientId(requireContext())
        if (webClientId != null) {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .build()
            GoogleSignIn.getClient(requireActivity(), gso).signOut()
        }
        OrderFragment.activeOrderId = ""
        OrderFragment.tripPhase = OrderFragment.TripPhase.IDLE

        Toast.makeText(requireContext(), "Berhasil Keluar", Toast.LENGTH_SHORT).show()
        val navOptions = NavOptions.Builder()
            .setPopUpTo(R.id.nav_graph, true)
            .build()
        findNavController().navigate(R.id.action_profile_to_login, null, navOptions)
    }

    private fun computeInitials(name: String): String {
        val parts = name.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "UN"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "${parts[0].first()}${parts[1].first()}".uppercase()
        }
    }

    override fun onDestroyView() {
        userListener?.remove()
        userListener = null
        super.onDestroyView()
        _binding = null
    }
}
