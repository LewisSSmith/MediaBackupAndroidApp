package com.example.uploader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.example.uploader.databinding.FragmentUploadBinding

class UploadFragment : Fragment() {

    private var _binding: FragmentUploadBinding? = null
    private val binding get() = _binding!!

    private lateinit var session: SessionManager
    private var selectedUri: Uri? = null
    private var selectedFileName: String? = null
    private var selectedMimeType: String? = null

    private var selectedFolderUri: Uri? = null

    // Listens for progress/completion broadcasts sent by UploadService,
    // so the UI updates live even though the actual upload work runs in
    // a foreground service, independent of this Fragment's lifecycle.
    private val uploadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (_binding == null) return
            when (intent.action) {
                UploadService.ACTION_PROGRESS -> {
                    val current = intent.getIntExtra(UploadService.EXTRA_CURRENT, 0)
                    val total = intent.getIntExtra(UploadService.EXTRA_TOTAL, 0)
                    val message = intent.getStringExtra(UploadService.EXTRA_MESSAGE) ?: ""
                    binding.folderProgressBar.isIndeterminate = false
                    binding.folderProgressBar.max = total
                    binding.folderProgressBar.progress = current
                    binding.txtFolderStatus.text = getString(R.string.upload_progress, current, total, message)
                    Log.d("Upload progress", "Uploading $current / $total... ($message)")
                }

                UploadService.ACTION_DONE -> {
                    val message = intent.getStringExtra(UploadService.EXTRA_MESSAGE) ?: "Done"
                    binding.txtStatus.text = message
                    binding.txtFolderStatus.text = message
                    binding.btnUpload.isEnabled = selectedUri != null
                    binding.btnUploadFolder.isEnabled = selectedFolderUri != null
                    binding.progressBar.visibility = View.GONE
                    binding.folderProgressBar.visibility = View.GONE
                }
            }
        }
    }

    private val pickDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            //TODO verify if this permission is needed
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't support persistable grants
            }
            handleSelectedUri(uri)
        }
    }

    private val requestMediaLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                requireContext(),
                "Media location permission is required to preserve GPS EXIF data",
                Toast.LENGTH_LONG
            ).show()
        }
        launchPicker()
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't support persistable grants
            }
            selectedFolderUri = uri
            val docFile = DocumentFile.fromTreeUri(requireContext(), uri)
            val displayName = docFile?.name ?: uri.lastPathSegment ?: "Selected folder"
            binding.txtSelectedFolder.text = "Selected: $displayName"
            binding.btnUploadFolder.isEnabled = true
        }
    }

    private val requestMediaLocationPermissionForFolder = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                requireContext(),
                "Media location permission is required to preserve GPS EXIF data",
                Toast.LENGTH_LONG
            ).show()
        }
        launchFolderPicker()
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun launchPicker() {
        pickDocument.launch(arrayOf("image/*", "video/*"))
    }

    private fun launchFolderPicker() {
        pickFolder.launch(null)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUploadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        session = SessionManager(requireContext())
        binding.txtLoggedInAs.text = "Connected to ${session.baseUrl}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifPermission = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotifPermission) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.btnLogout.setOnClickListener {
            session.clear()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }

        binding.btnPick.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_MEDIA_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) launchPicker()
                else requestMediaLocationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            } else {
                launchPicker()
            }
        }

        binding.btnPickFolder.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_MEDIA_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) launchFolderPicker()
                else requestMediaLocationPermissionForFolder.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            } else {
                launchFolderPicker()
            }
        }

        binding.btnUpload.setOnClickListener {
            val uri = selectedUri
            val path = binding.editEndpoint.text.toString().trim()
            val fieldName = binding.editFieldName.text.toString().trim().ifEmpty { "file" }

            if (uri == null) {
                Toast.makeText(requireContext(), "Select a file first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (path.isEmpty()) {
                Toast.makeText(requireContext(), "Enter the upload path", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val endpoint = buildEndpoint(path)

            binding.btnUpload.isEnabled = false
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.isIndeterminate = true
            binding.txtStatus.text = "Starting upload..."

            val serviceIntent = Intent(requireContext(), UploadService::class.java).apply {
                putExtra(UploadService.EXTRA_MODE, UploadService.MODE_SINGLE)
                putExtra(UploadService.EXTRA_URI, uri)
                putExtra(UploadService.EXTRA_ENDPOINT, endpoint)
                putExtra(UploadService.EXTRA_FIELD_NAME, fieldName)
                putExtra(UploadService.EXTRA_FILE_NAME, selectedFileName)
                putExtra(UploadService.EXTRA_MIME_TYPE, selectedMimeType)
                putExtra(UploadService.EXTRA_AUTH_HEADER, session.authHeader())
            }
            ContextCompat.startForegroundService(requireContext(), serviceIntent)

            Toast.makeText(
                requireContext(),
                "Upload started - check the notification for progress",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnUploadFolder.setOnClickListener {
            val folderUri = selectedFolderUri
            val path = binding.editEndpoint.text.toString().trim()
            val fieldName = binding.editFieldName.text.toString().trim().ifEmpty { "file" }

            if (folderUri == null) {
                Toast.makeText(requireContext(), "Select a folder first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (path.isEmpty()) {
                Toast.makeText(requireContext(), "Enter the upload path", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val endpoint = buildEndpoint(path)

            binding.btnUploadFolder.isEnabled = false
            binding.folderProgressBar.visibility = View.VISIBLE
            binding.folderProgressBar.isIndeterminate = true
            binding.txtFolderStatus.text = "Starting upload..."

            val serviceIntent = Intent(requireContext(), UploadService::class.java).apply {
                putExtra(UploadService.EXTRA_MODE, UploadService.MODE_FOLDER)
                putExtra(UploadService.EXTRA_FOLDER_URI, folderUri)
                putExtra(UploadService.EXTRA_ENDPOINT, endpoint)
                putExtra(UploadService.EXTRA_FIELD_NAME, fieldName)
                putExtra(UploadService.EXTRA_AUTH_HEADER, session.authHeader())
            }
            ContextCompat.startForegroundService(requireContext(), serviceIntent)

            Toast.makeText(
                requireContext(),
                "Folder upload started - check the notification for progress",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(UploadService.ACTION_PROGRESS)
            addAction(UploadService.ACTION_DONE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(uploadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(uploadReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            requireContext().unregisterReceiver(uploadReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun handleSelectedUri(uri: Uri) {
        selectedUri = uri
        selectedMimeType = requireContext().contentResolver.getType(uri)
        selectedFileName = queryFileName(uri)
        binding.txtSelectedFile.text = "Selected: $selectedFileName ($selectedMimeType)"
        binding.btnUpload.isEnabled = true

        if (selectedMimeType?.startsWith("image/") == true) {
            try {
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                    binding.imgPreview.setImageBitmap(bitmap)
                    binding.imgPreview.visibility = View.VISIBLE
                }
            } catch (_: SecurityException) {
                binding.imgPreview.visibility = View.GONE
            }
        } else {
            binding.imgPreview.visibility = View.GONE
        }
    }

    private fun buildEndpoint(path: String): String {
        val base = session.baseUrl?.trimEnd('/') ?: ""
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return "$base$normalizedPath"
    }

    private fun queryFileName(uri: Uri): String {
        var name = "upload_${System.currentTimeMillis()}"
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: name
            }
        }
        return name
    }
}