package com.example.uploader.toolbar

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.example.uploader.R

class Toolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    private var backButton: Button
    private var printButton: Button

    init {
        inflate(context, R.layout.viewer_toolbar, this)
        backButton = findViewById(R.id.backButton)
        printButton = findViewById(R.id.printButton)
    }

    fun showPrint(show: Boolean) {
        printButton.isVisible = show
    }

    fun setInterface(toolbarInterface: ToolbarInterface) {
        backButton.setOnClickListener { toolbarInterface.onBackClicked() }
    }

    fun setInterface(toolbarInterface: ImageToolbarInterface) {
        setInterface(toolbarInterface as ToolbarInterface)
        printButton.setOnClickListener { toolbarInterface.print() }
        showPrint(true)
    }

    fun setInterface(toolbarInterface: VideoToolbarInterface) {
        setInterface(toolbarInterface as ToolbarInterface)
        backButton.setOnClickListener { toolbarInterface.onBackClicked() }
        showPrint(false)
    }
}