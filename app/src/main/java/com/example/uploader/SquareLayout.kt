package com.example.uploader

import android.content.Context
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout

// Used for displaying thumbnails in gallery
class SquareLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        val squareHeightSpec = MeasureSpec.makeMeasureSpec(
            width,
            MeasureSpec.EXACTLY
        )

        super.onMeasure(widthMeasureSpec, squareHeightSpec)
    }
}