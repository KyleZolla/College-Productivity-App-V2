package com.example.productivityapp

import android.view.View
import com.google.android.material.button.MaterialButton

object RoadmapEstimateFeedbackUi {

    fun bind(
        root: View,
        showPrompt: Boolean,
        enabled: Boolean,
        onFeedbackSelected: (String) -> Unit,
    ) {
        val block = root.findViewById<View>(R.id.roadmapEstimateFeedbackBlock)
        val tooShort = root.findViewById<MaterialButton>(R.id.roadmapEstimateFeedbackTooShort)
        val aboutRight = root.findViewById<MaterialButton>(R.id.roadmapEstimateFeedbackAboutRight)
        val tooLong = root.findViewById<MaterialButton>(R.id.roadmapEstimateFeedbackTooLong)

        block.visibility = if (showPrompt) View.VISIBLE else View.GONE
        if (!showPrompt) return

        tooShort.setOnClickListener(null)
        aboutRight.setOnClickListener(null)
        tooLong.setOnClickListener(null)

        tooShort.isEnabled = enabled
        aboutRight.isEnabled = enabled
        tooLong.isEnabled = enabled

        tooShort.setOnClickListener { onFeedbackSelected(EstimateFeedback.TOO_SHORT) }
        aboutRight.setOnClickListener { onFeedbackSelected(EstimateFeedback.ABOUT_RIGHT) }
        tooLong.setOnClickListener { onFeedbackSelected(EstimateFeedback.TOO_LONG) }
    }
}
