package com.example.anjemgogogo.ui.auth

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.anjemgogogo.R
import com.example.anjemgogogo.data.firebase.FirebaseRepository
import com.example.anjemgogogo.data.model.UserProfile
import com.example.anjemgogogo.databinding.FragmentLoginBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

class LoginFragment : Fragment(R.layout.fragment_login) {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private var isRegisterMode = false

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account?.idToken
                if (!idToken.isNullOrBlank()) {
                    setLoading(true)
                    FirebaseRepository.signInWithGoogleIdToken(idToken, selectedRole()) { authResult ->
                        if (!isAdded || _binding == null) return@signInWithGoogleIdToken
                        setLoading(false)
                        authResult.onSuccess { profile ->
                            Toast.makeText(requireContext(), "Login Google berhasil!", Toast.LENGTH_SHORT).show()
                            navigateByRole(profile.role)
                        }.onFailure { err ->
                            Toast.makeText(requireContext(), "Login Google gagal: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "Token Google tidak tersedia. Periksa konfigurasi Web Client ID.", Toast.LENGTH_LONG).show()
                }
            } catch (e: ApiException) {
                Toast.makeText(requireContext(), "Google Sign-In gagal (kode ${e.statusCode}). Pastikan SHA-1 terdaftar di Firebase Console.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLoginBinding.bind(view)

        val currentUser = FirebaseRepository.currentUser()
        if (currentUser != null) {
            setLoading(true)
            FirebaseRepository.fetchUserRole(currentUser.uid) { role ->
                if (!isAdded || _binding == null) return@fetchUserRole
                navigateByRole(role)
            }
            return
        }

        updateModeUi()

        binding.btnToRegister.setOnClickListener {
            isRegisterMode = !isRegisterMode
            updateModeUi()
        }

        binding.btnLogin.setOnClickListener {
            if (!ensureFirebaseConfigured()) return@setOnClickListener

            val email = binding.etEmail.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString()?.trim().orEmpty()
            val name = binding.etName.text?.toString()?.trim().orEmpty()

            if (isRegisterMode && name.isEmpty()) {
                binding.etName.error = "Nama lengkap wajib diisi"
                return@setOnClickListener
            }
            if (email.isEmpty()) {
                binding.etEmail.error = "Email wajib diisi"
                return@setOnClickListener
            }
            if (password.length < 6) {
                binding.etPassword.error = "Password minimal 6 karakter"
                return@setOnClickListener
            }

            setLoading(true)
            val role = selectedRole()
            if (isRegisterMode) {
                FirebaseRepository.registerWithEmail(name, email, password, role) { result ->
                    if (!isAdded || _binding == null) return@registerWithEmail
                    setLoading(false)
                    result.onSuccess { profile ->
                        Toast.makeText(requireContext(), "Pendaftaran berhasil!", Toast.LENGTH_SHORT).show()
                        navigateByRole(profile.role)
                    }.onFailure { err ->
                        Toast.makeText(requireContext(), "Daftar gagal: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                FirebaseRepository.signInWithEmail(email, password, role) { result ->
                    if (!isAdded || _binding == null) return@signInWithEmail
                    setLoading(false)
                    result.onSuccess { profile ->
                        Toast.makeText(requireContext(), "Login berhasil!", Toast.LENGTH_SHORT).show()
                        navigateByRole(profile.role)
                    }.onFailure { err ->
                        Toast.makeText(requireContext(), "Login gagal: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        binding.btnGoogleSignIn.setOnClickListener {
            if (!ensureFirebaseConfigured()) return@setOnClickListener

            val webClientId = FirebaseRepository.getGoogleWebClientId(requireContext())
            if (webClientId == null) {
                showConfigDialog(
                    "Google Sign-In Belum Dikonfigurasi",
                    "Pastikan Anda sudah:\n1. Mengaktifkan Provider Google di Firebase Console (Authentication).\n2. Menambahkan SHA-1 keystore ke Project Settings.\n3. Mengunduh ulang google-services.json terbaru ke folder app/."
                )
                return@setOnClickListener
            }

            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .build()
            val client = GoogleSignIn.getClient(requireActivity(), gso)
            client.signOut().addOnCompleteListener {
                if (isAdded) {
                    googleSignInLauncher.launch(client.signInIntent)
                }
            }
        }
    }

    private fun selectedRole(): String {
        return if (binding.rgLoginRole.checkedRadioButtonId == R.id.rbRoleDriver) {
            UserProfile.ROLE_DRIVER
        } else {
            UserProfile.ROLE_PASSENGER
        }
    }

    private fun updateModeUi() {
        if (isRegisterMode) {
            binding.tilName.visibility = View.VISIBLE
            binding.btnLogin.text = "Daftar Akun"
            binding.btnToRegister.text = "Sudah punya akun? Masuk"
        } else {
            binding.tilName.visibility = View.GONE
            binding.btnLogin.text = getString(R.string.btn_login)
            binding.btnToRegister.text = "Belum punya akun? Daftar"
        }
    }

    private fun setLoading(loading: Boolean) {
        if (_binding == null) return
        binding.btnLogin.isEnabled = !loading
        binding.btnGoogleSignIn.isEnabled = !loading
        binding.btnToRegister.isEnabled = !loading
        binding.btnLogin.text = when {
            loading -> "Memproses..."
            isRegisterMode -> "Daftar Akun"
            else -> getString(R.string.btn_login)
        }
    }

    private fun ensureFirebaseConfigured(): Boolean {
        if (FirebaseRepository.isUsingDummyConfig()) {
            showConfigDialog(
                "Firebase Belum Terhubung",
                "File app/google-services.json masih berisi data dummy."
            )
            return false
        }
        return true
    }

    private fun showConfigDialog(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Mengerti", null)
            .show()
    }

    private fun navigateByRole(role: String) {
        val navOptions = NavOptions.Builder()
            .setPopUpTo(R.id.loginFragment, true)
            .build()
        val actionId = if (role == UserProfile.ROLE_DRIVER) {
            R.id.action_login_to_driver_home
        } else {
            R.id.action_login_to_home
        }
        findNavController().navigate(actionId, null, navOptions)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
