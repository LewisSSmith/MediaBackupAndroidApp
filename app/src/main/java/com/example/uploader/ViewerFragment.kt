package com.example.uploader

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.uploader.databinding.FragmentViewerBinding
import com.example.uploader.toolbar.ImageToolbarInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ViewerFragment : Fragment(), ImageToolbarInterface {

    enum class State {
        UNLOADED,
        THUMBNAIL,
        ORIGINAL
    }

    private var currentState: State = State.UNLOADED

    private lateinit var fileId: String

    private var _binding: FragmentViewerBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val ARG_FILE_ID = "image_url"

        fun newInstance(fileId: String): ViewerFragment {
            val fragment = ViewerFragment()
            val args = Bundle()
            args.putString(ARG_FILE_ID, fileId)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentViewerBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fileId =
            arguments?.getString(ARG_FILE_ID) ?: throw IllegalArgumentException("File ID is null")
        lifecycleScope.launch {
            val thumbnailBitmap = withContext(Dispatchers.IO) {
                StorageManager.getThumbnail(requireContext(), fileId)
            }

            if (thumbnailBitmap != null) {
                if (currentState == State.UNLOADED) {
                    binding.imageView.setImageBitmap(thumbnailBitmap)
                    currentState = State.THUMBNAIL
                }
            } else {
                Log.e("Gallery", "Thumbnail bitmap is null")
            }
        }

        performGetOriginal(fileId)
        binding.toolbar.setInterface(this)
    }

    fun performGetOriginal(fileId: String) {
        lifecycleScope.launch {
            val img: Bitmap?
            withContext(Dispatchers.IO) {
                val ctx: Context? = activity?.applicationContext
                if (ctx != null) {
                    img = StorageManager.performGetOriginalImage(fileId, ctx)
                } else {
                    throw RuntimeException("No context")
                }
            }

            if (img != null) {
                binding.imageView.setImageBitmap(img)
                currentState = State.ORIGINAL
            }
        }
    }

    override fun print() {
        TODO("Not yet implemented")
    }

    override fun onBackClicked() {
        findNavController().popBackStack()
    }

    override fun onDetailsClicked() {
        TODO("Not yet implemented")
    }

    override fun onEditClicked() {
        TODO("Not yet implemented")
    }

    override fun onDownloadClicked() {
        StorageManager.saveFileToStorage(requireContext(), fileId)
    }
}