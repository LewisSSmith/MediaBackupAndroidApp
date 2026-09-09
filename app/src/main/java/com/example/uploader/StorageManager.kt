package com.example.uploader

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.example.uploader.GalleryFragment.SortBy
import com.example.uploader.GalleryFragment.SortDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object StorageManager {

    private val thumbnailSubfolder = "thumbnails"
    private val originalsSubfolder = "originals"

    lateinit var session: SessionManager
    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    data class FileDetails(
        val id: String,
        val hash: String,
        val filename: String,
        val mime: String,
        val size: Int,
        val dateUploaded: String,
        val dateCreated: String,
        val dateLastModified: String,
        val metadata: String
    )

    enum class MediaContainerState {
        UNLOADED, LOADING, LOADED
    }

    private data class MediaCache(
        var status: MediaContainerState,
        var file: File?
    )

    private data class FileContainer(
        val details: FileDetails,
        val thumbnail: MediaCache,
        val original: MediaCache
    )

    private val files: MutableMap<String, FileContainer> = mutableMapOf()

    fun initialize(context: Context) {
        session = SessionManager(context)
    }

    fun create(fileId: String, details: FileDetails) {
        if (!files.containsKey(fileId)) {
            files[fileId] = FileContainer(
                details,
                MediaCache(MediaContainerState.UNLOADED, null),
                MediaCache(MediaContainerState.UNLOADED, null)
            )
        }
    }

    fun getFileDetails(fileId: String): FileDetails {
        return files.getValue(fileId).details
    }

    suspend fun getThumbnail(context: Context, fileId: String): Bitmap? {
        val thumbnailCacheFolder = File(context.cacheDir, thumbnailSubfolder)
        val thumbnailCacheFile = File(thumbnailCacheFolder, fileId)
        if (thumbnailCacheFile.exists()) {
            return BitmapFactory.decodeFile(thumbnailCacheFile.path)
        } else {
            return performGetThumbnail(context, fileId)
        }
    }

    // This download thumbnail and saves it to cache, potentially rename this function to make it clear
    suspend fun performGetThumbnail(context: Context, fileId: String): Bitmap? {
        val file = files.getValue(fileId)
        file.thumbnail.status = MediaContainerState.LOADING
        val cachedFile = withContext(Dispatchers.IO) {
            downloadThumbnail(context, fileId)
        }

        if (cachedFile != null) {
            file.thumbnail.status = MediaContainerState.LOADED
            file.thumbnail.file = cachedFile
            return BitmapFactory.decodeFile(cachedFile.path)
        } else {
            Log.d("THUMBNAIL", "Thumbnail returned null $fileId")
            return null
        }
        //TODO display error to user so it doesnt look like thumbnail is taking forever to load
    }

    suspend fun performGetOriginalImage(fileId: String, context: Context): Bitmap? {
        val file = files.getValue(fileId)
        file.original.status = MediaContainerState.LOADING
        val cachedFile: File? = withContext(Dispatchers.IO) {
            downloadOriginal(context, fileId)
        }

        if (cachedFile != null) {
            val bytes = cachedFile.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val orientation = ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            val img = rotateBitmapIfRequired(bitmap, orientation)
            //setOriginalImage(fileId, img)

            file.original.status = MediaContainerState.LOADED
            file.original.file = cachedFile
            return img
        } else {
            return null
        }
        //TODO display error to user so it doesnt look like thumbnail is taking forever to load
    }

    suspend fun performGetOriginalVideo(fileId: String, context: Context): Uri? {
        files.getValue(fileId).original.status = MediaContainerState.LOADING
        val file: File? = withContext(Dispatchers.IO) {
            downloadOriginal(context, fileId)
        }

        return file?.toUri()
    }

    private fun downloadFile(
        context: Context,
        fileId: String,
        subFolder: String,
        endpoint: String,
        authHeader: String
    ): File? {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", authHeader)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body
                if (body != null) {
                    val folder = File(context.cacheDir, subFolder)
                    folder.mkdirs()
                    val destinationFile = File(folder, fileId)

                    body.byteStream().use { inputStream ->
                        destinationFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    return destinationFile
                } else {
                    return null
                }
            } else {
                return null
            }
        }
    }

    private fun downloadThumbnail(context: Context, fileId: String): File? {
        return downloadFile(
            context,
            fileId,
            "thumbnails",
            session.baseUrl + "/thumbnail/" + fileId,
            session.authHeader()
        )
    }

    private fun downloadOriginal(context: Context, fileId: String): File? {
        return downloadFile(
            context,
            fileId,
            "originals",
            session.baseUrl + "/media/" + fileId,
            session.authHeader()
        )
    }

    private fun rotateBitmapIfRequired(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap // ORIENTATION_NORMAL or ORIENTATION_UNDEFINED
        }

        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
    }

    fun performFileRequest(
        endpoint: String,
        authHeader: String,
        sortBy: SortBy,
        sortDirection: SortDirection
    ): Pair<Boolean, Map<String, FileDetails>?> {
        val (success, fileDetails) =
            fileRequest(endpoint, authHeader, sortBy, sortDirection)

        return Pair(success, fileDetails)
    }

    private fun createFileDetailsFromJSON(json: JSONObject): FileDetails {
        return FileDetails(
            id = json.getString("id"),
            hash = json.getString("hash"),
            filename = json.getString("filename"),
            mime = json.getString("mime"),
            size = json.getInt("size"),
            dateUploaded = json.getString("date_uploaded"),
            dateCreated = json.getString("date_created"),
            dateLastModified = json.getString("date_last_modified"),
            metadata = json.getString("metadata")
        )
    }

    private fun fileRequest(
        endpoint: String,
        authHeader: String,
        sortBy: SortBy,
        sortDirection: SortDirection
    ): Pair<Boolean, Map<String, FileDetails>?> {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("sort", sortBy.urlOption)
            .addQueryParameter("direction", sortDirection.urlOption)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonArray = JSONArray(bodyStr)
                val fileDetailsMap = mutableMapOf<String, FileDetails>()
                for (i in 0 until jsonArray.length()) {
                    val details = jsonArray.getJSONObject(i)
                    val fileDetails = createFileDetailsFromJSON(details)
                    val id = fileDetails.id
                    fileDetailsMap[id] = fileDetails
                }

                return Pair(true, fileDetailsMap)
            } else {
                "Failed to get thumbnail (${response.code}): $bodyStr"
                return Pair(false, null)
            }
        }
    }

    // Downloads the file to device
    //TODO This still needs to be implemented correctly and tested
    fun saveFileToStorage(context: Context, fileId: String) {
        // assume image for now
        when (files.getValue(fileId).original.status) {
            MediaContainerState.UNLOADED -> Log.e("Storage manager", "This shouldn't be possible")
            MediaContainerState.LOADING -> {
                Log.d("Storage manager", "Okay cool, wait until its loaded")
            }

            MediaContainerState.LOADED -> {
                Log.d("Storage manager", "We have the file, lets write it to storage")
                moveCacheFileToMediaStore(context, fileId)
            }
        }
    }

    // TODO This still needs to be implemented correctly and tested
    fun moveCacheFileToMediaStore(
        context: Context,
        fileId: String
    ): Uri? {
        val file = files.getValue(fileId)
        val cacheFile: File = file.original.file ?: throw IllegalStateException("File not cached")
        val resolver = context.contentResolver

        val collection: Uri
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.details.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, file.details.mime)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                collection = MediaStore.Files.getContentUri("external")
                put(
                    MediaStore.MediaColumns.DATA,
                    File(downloadsDir, file.details.filename).absolutePath
                )
            }
        }

        val itemUri = resolver.insert(collection, values) ?: return null

        return try {
            // Stream bytes from cache file into the MediaStore Uri
            resolver.openOutputStream(itemUri)?.use { output ->
                cacheFile.inputStream().use { input ->
                    input.copyTo(output, bufferSize = 8 * 1024)
                }
            } ?: throw IOException("Failed to open output stream for $itemUri")

            // Unmark pending so the file becomes visible/usable to other apps
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }

            // Clean up the original cache file now that it's safely stored
            //cacheFile.delete()

            itemUri
        } catch (e: IOException) {
            // Clean up the incomplete MediaStore entry on failure
            resolver.delete(itemUri, null, null)
            e.printStackTrace()
            null
        }
    }
}