package com.example.uploader

import android.R
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.uploader.databinding.FragmentGalleryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class GalleryFragment : Fragment() {

    enum class SortBy(val displayName: String, val urlOption: String) {
        DATE_UPLOADED("Date Uploaded", "uploaded"),
        DATE_CREATED("Date Created", "created"),
        DATE_LAST_MODIFIED("Date Last Modified", "last_modified"),
        SIZE("Size", "size")
    }

    enum class SortDirection(val displayName: String, val urlOption: String) {
        ASCENDING("Ascending", "asc"),
        DESCENDING("Descending", "desc")
    }

    enum class FileType {
        IMAGE,
        VIDEO
    }

    sealed class GalleryItem

    data class GalleryFile(
        val id: String,
        val fileType: FileType
    ) : GalleryItem()

    data class GalleryHeading(
        val text: String
    ) : GalleryItem()

    private lateinit var session: SessionManager

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    var currentSortBy: SortBy = SortBy.DATE_UPLOADED
    var currentSortDirection: SortDirection = SortDirection.ASCENDING

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val customAdapter = CustomAdapter(this)
        val recyclerView: RecyclerView = binding.thumbnailContainer
        val layoutManager = GridLayoutManager(requireContext(), 4)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (customAdapter.getItem(position) is GalleryHeading) {
                    4   // heading occupies all 3 columns
                } else {
                    1   // image occupies one column
                }
            }
        }
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = customAdapter

        val sortBySpinner = binding.sortBySpinner
        val directionSpinner = binding.directionSpinner

        val adapter = ArrayAdapter(
            requireContext(), R.layout.simple_spinner_item,
            SortBy.entries.toTypedArray()
        )
        val directionAdapter = ArrayAdapter(
            requireContext(), R.layout.simple_spinner_item,
            SortDirection.entries.toTypedArray()
        )
        adapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        directionAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        sortBySpinner.adapter = adapter
        directionSpinner.adapter = directionAdapter
        sortBySpinner.isSaveEnabled = false
        directionSpinner.isSaveEnabled = false

        sortBySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selected = parent.getItemAtPosition(position) as SortBy
                customAdapter.clearItems()
                currentSortBy = selected
                loadGallery(customAdapter, currentSortBy, currentSortDirection)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
            }
        }

        directionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selected = parent.getItemAtPosition(position) as SortDirection
                customAdapter.clearItems()
                currentSortDirection = selected
                loadGallery(customAdapter, currentSortBy, currentSortDirection)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
            }
        }

        currentSortBy = sortBySpinner.selectedItem as SortBy
        currentSortDirection = directionSpinner.selectedItem as SortDirection

        session = SessionManager(requireContext())
        loadGallery(customAdapter, currentSortBy, currentSortDirection)
    }

    //TODO fix issues with nulls
    fun addToDateGroup(date: String?, currentGroup: LocalDate?, items: MutableList<GalleryItem>): LocalDate? {
        val day = try {
            OffsetDateTime.parse(date).toLocalDate()
        } catch (_: DateTimeParseException) {
            null
        }

        if (day != currentGroup) {
            val text = if (day != null) day.format(DateTimeFormatter.ofPattern("d MMMM yyyy")) else "Unknown"
            items.add(GalleryHeading(text))
            return day
        }

        return currentGroup
    }

    private fun loadGallery(
        customAdapter: CustomAdapter,
        sortBy: SortBy,
        sortDirection: SortDirection
    ) {
        lifecycleScope.launch {
            val (success, fileDetails) = withContext(Dispatchers.IO) {
                StorageManager.performFileRequest(
                    session.baseUrl + "/file-request",
                    session.authHeader(),
                    sortBy,
                    sortDirection
                )
            }

            if (success) {
                if (fileDetails == null) {
                    Log.d("Gallery", "No files to get yet")
                } else {
                    val items: MutableList<GalleryItem> = mutableListOf()
                    var currentGroup: LocalDate? = null
                    for ((fileId, details) in fileDetails) {
                        StorageManager.create(fileId, details)
                        currentGroup = when (sortBy) {
                            SortBy.DATE_UPLOADED -> addToDateGroup(details.dateUploaded, currentGroup, items)
                            SortBy.DATE_CREATED -> addToDateGroup(details.dateCreated, currentGroup, items)
                            SortBy.DATE_LAST_MODIFIED -> addToDateGroup(details.dateLastModified, currentGroup, items)
                            else -> currentGroup
                        }

                        val fileType: FileType = if (details.mime.startsWith("image")) {
                            FileType.IMAGE
                        } else if (details.mime.startsWith("video")) {
                            FileType.VIDEO
                        } else {
                            Log.w("Gallery", "Unsupported mime type '${details.mime}'")
                            continue
                        }
                        items.add(GalleryFile(fileId, fileType))
                    }

                    customAdapter.addItems(items)
                }
            }
        }
    }

    fun imageThumbnailClicked(fileId: String) {
        val viewerFragment = ViewerFragment.newInstance(fileId)
        childFragmentManager.beginTransaction().replace(binding.viewerContainer.id, viewerFragment)
            .addToBackStack(null).commit()
    }

    fun videoThumbnailClicked(fileId: String) {
        val viewerFragment = VideoViewerFragment.newInstance(fileId)
        childFragmentManager.beginTransaction().replace(binding.viewerContainer.id, viewerFragment)
            .addToBackStack(null).commit()
    }

    val thumbnailCoroutines: MutableMap<String, Job> = mutableMapOf()

    fun retrieveThumbnail(fileId: String, viewHolder: CustomAdapter.FileViewHolder) {
        thumbnailCoroutines[fileId] = lifecycleScope.launch {
            val img = withContext(Dispatchers.IO) {
                StorageManager.getThumbnail(requireContext(), fileId)
            }

            if (img != null) {
                viewHolder.setThumbnail(img)
            } else {
                Log.e("Gallery", "Thumbnail returned null")
            }
        }
    }

    fun cancelGetThumbnail(fileId: String) {
        thumbnailCoroutines.getValue(fileId).cancel()
        // TODO this doesnt actually stop the network request
        thumbnailCoroutines.remove(fileId)
    }

    fun getVideoDuration(fileId: String): Double? {
        return try {
            JSONObject(StorageManager.getFileDetails(fileId).metadata)
                .getJSONArray("streams")
                .getJSONObject(0)
                .getDouble("duration")
        } catch (e: JSONException) {
            Log.w("Gallery Loader", "Failed to get duration for file $fileId", e)
            null
        }
    }
}