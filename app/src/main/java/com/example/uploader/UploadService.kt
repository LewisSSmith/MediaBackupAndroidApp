package com.example.uploader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class UploadService : Service() {

    companion object {
        const val CHANNEL_ID = "upload_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_MODE = "mode"
        const val MODE_SINGLE = "single"
        const val MODE_FOLDER = "folder"

        const val EXTRA_URI = "uri"
        const val EXTRA_FOLDER_URI = "folder_uri"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_FIELD_NAME = "field_name"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_AUTH_HEADER = "auth_header"

        const val ACTION_PROGRESS = "com.example.uploader.UPLOAD_PROGRESS"
        const val ACTION_DONE = "com.example.uploader.UPLOAD_DONE"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_TOTAL = "total"

        // usually refers to filename of the file being uploaded
        // but can be used to display error messages
        const val EXTRA_MESSAGE = "message"

        const val EXTRA_SUCCESS_COUNT = "success_count"
        const val EXTRA_FAIL_COUNT = "fail_count"
    }

    val supportedMimes = setOf("image/jpeg", "video/mp4")
    val initialIcon = android.R.drawable.stat_sys_upload
    val uploadIcon = android.R.drawable.stat_sys_upload
    val completeIcon = android.R.drawable.stat_sys_upload_done
    val errorIcon = android.R.drawable.stat_notify_error

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val client = OkHttpClient.Builder().build()
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: return stopSelfResult()
        val fieldName = intent.getStringExtra(EXTRA_FIELD_NAME) ?: "file"
        val authHeader = intent.getStringExtra(EXTRA_AUTH_HEADER) ?: ""
        val mode = intent.getStringExtra(EXTRA_MODE)

        // Show an initial notification immediately and call
        // startForeground within a few seconds, as required by Android.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                "Starting upload",
                "Starting upload...",
                0,
                0,
                indeterminate = true,
                icon = initialIcon
            )
        )

        when (mode) {
            MODE_SINGLE -> {
                val uri = intent.getUriExtraCompat(EXTRA_URI)
                    ?: return stopSelfResult()
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "upload"
                val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "application/octet-stream"
                runSingleUpload(uri, endpoint, fieldName, fileName, mimeType, authHeader)
            }

            MODE_FOLDER -> {
                val folderUri = intent.getUriExtraCompat(EXTRA_FOLDER_URI)
                    ?: return stopSelfResult()
                runFolderUpload(folderUri, endpoint, fieldName, authHeader)
            }

            else -> return stopSelfResult()
        }

        return START_NOT_STICKY
    }

    private fun stopSelfResult(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    private fun runSingleUpload(
        uri: Uri,
        endpoint: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        authHeader: String
    ) {
        serviceScope.launch {
            try {
                val tempFile = copyUriToTempFile(uri, fileName)
                updateNotification(
                    "Uploading",
                    "Uploading $fileName...",
                    0,
                    1,
                    indeterminate = true,
                    icon = uploadIcon
                )

                val isSuccessful =
                    performUpload(tempFile, endpoint, fieldName, fileName, mimeType, authHeader)
                tempFile.delete()

                val summary = if (isSuccessful) "Upload complete" else "Upload failed"
                updateNotification(
                    summary,
                    summary,
                    1,
                    1,
                    indeterminate = false,
                    ongoing = false,
                    icon = completeIcon
                )

                broadcastUploadDone(summary, if (isSuccessful) 1 else 0, if (isSuccessful) 0 else 1)
            } catch (e: Exception) {
                updateNotification(
                    "Upload error",
                    "Upload failed: ${e.message}",
                    0,
                    1,
                    indeterminate = false,
                    ongoing = false,
                    icon = errorIcon
                )
                broadcastUploadDone("Upload failed: ${e.message}", 0, 1)
            } finally {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun runFolderUpload(
        folderUri: Uri,
        endpoint: String,
        fieldName: String,
        authHeader: String
    ) {
        serviceScope.launch {
            val rootDoc = DocumentFile.fromTreeUri(this@UploadService, folderUri)
            if (rootDoc == null || !rootDoc.isDirectory) {
                updateNotification(
                    "Upload error",
                    "Could not read folder",
                    0,
                    0,
                    indeterminate = false,
                    ongoing = false,
                    icon = errorIcon
                )
                broadcastUploadDone("Could not read folder", 0, 0)
                stopForegroundCompat()
                stopSelf()
                return@launch
            }

            val mediaFiles = collectMediaFiles(rootDoc)
            if (mediaFiles.isEmpty()) {
                updateNotification(
                    "Upload error",
                    "No media files found",
                    0,
                    0,
                    indeterminate = false,
                    ongoing = false,
                    icon = errorIcon
                )
                broadcastUploadDone("No media files found in folder", 0, 0)
                stopForegroundCompat()
                stopSelf()
                return@launch
            }

            var successCount = 0
            var failCount = 0
            val total = mediaFiles.size

            for ((index, doc) in mediaFiles.withIndex()) {
                val name = doc.name ?: "file_$index"
                val mimeType = doc.type ?: "application/octet-stream"

                val percentageComplete = (index.toFloat() / total * 100).roundToInt()

                updateNotification(
                    "Uploading ${percentageComplete}%",
                    "Uploading $index / $total ($name)",
                    index,
                    total,
                    indeterminate = false,
                    icon = uploadIcon
                )
                broadcastUploadProgress(index + 1, total, name, successCount, failCount)

                try {
                    val tempFile = copyUriToTempFile(doc.uri, name)
                    val isSuccessful =
                        performUpload(tempFile, endpoint, fieldName, name, mimeType, authHeader)
                    tempFile.delete()

                    if (isSuccessful) successCount++ else failCount++
                } catch (_: Exception) {
                    failCount++
                }
            }

            val summary = "$successCount succeeded, $failCount failed of $total"
            updateNotification(
                "Upload Complete",
                summary,
                total,
                total,
                indeterminate = false,
                ongoing = false,
                icon = completeIcon
            )
            broadcastUploadDone(summary, successCount, failCount)

            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun collectMediaFiles(dir: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()

        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                result.addAll(collectMediaFiles(child))
                continue
            }

            if (!child.isFile) continue

            val name = child.name?.lowercase() ?: ""
            val mime = child.type ?: ""

            // Ignore Android/gallery thumbnail files
            // TODO there should be a better way of filtering this
            val isThumbnail =
                name.contains("thumb") ||
                        name.contains("thumbnail") ||
                        name.startsWith(".") ||
                        name.contains("cache") ||
                        name.contains("preview")

            if (isThumbnail) {
                Log.d("Upload filter", "Skipping thumbnail: $name")
                continue
            }

            if (mime in supportedMimes) {
                result.add(child)
            }
        }

        return result
    }

    // A temp file is needed for the request body when uploading
    private fun copyUriToTempFile(uri: Uri, filename: String): File {
        val tempFile = File(cacheDir, "upload_tmp_${System.currentTimeMillis()}_$filename")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open file: $filename")
        return tempFile
    }

    private fun performUpload(
        file: File,
        endpoint: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        authHeader: String
    ): Boolean {
        val fileBody: RequestBody = file.asRequestBody(mimeType.toMediaTypeOrNull())

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(fieldName, fileName, fileBody)
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", authHeader)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Uploads",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shows progress of file uploads"
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        title: String?,
        innerMessage: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
        ongoing: Boolean = true,
        icon: Int
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title ?: "Uploader")
            .setContentText(innerMessage)
            .setSmallIcon(icon)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (max > 0) {
            builder.setProgress(max, progress, indeterminate)
        } else if (indeterminate) {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(
        title: String?,
        innerMessage: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
        ongoing: Boolean = true,
        icon: Int
    ) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(title, innerMessage, progress, max, indeterminate, ongoing, icon)
        )
    }

    private fun broadcastUploadProgress(
        current: Int,
        total: Int,
        message: String,
        successCount: Int,
        failCount: Int
    ) {
        val intent = Intent(ACTION_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_CURRENT, current)
            putExtra(EXTRA_TOTAL, total)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_SUCCESS_COUNT, successCount)
            putExtra(EXTRA_FAIL_COUNT, failCount)
        }
        sendBroadcast(intent)
    }

    private fun broadcastUploadDone(message: String, successCount: Int, failCount: Int) {
        val intent = Intent(ACTION_DONE).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_SUCCESS_COUNT, successCount)
            putExtra(EXTRA_FAIL_COUNT, failCount)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

private fun Intent.getUriExtraCompat(key: String): Uri? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}
