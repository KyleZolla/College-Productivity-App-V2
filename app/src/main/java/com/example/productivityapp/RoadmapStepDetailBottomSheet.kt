package com.example.productivityapp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlin.math.roundToInt

object RoadmapStepDetailBottomSheet {

    fun show(
        context: Context,
        step: RoadmapStep,
        taskTitle: String? = null,
    ) {
        val dialog = BottomSheetDialog(context)
        val content = LayoutInflater.from(context)
            .inflate(R.layout.bottom_sheet_roadmap_step_detail, null, false)

        val taskTitleView = content.findViewById<TextView>(R.id.stepDetailTaskTitle)
        if (taskTitle.isNullOrBlank()) {
            taskTitleView.visibility = View.GONE
        } else {
            taskTitleView.visibility = View.VISIBLE
            taskTitleView.text = taskTitle
        }

        content.findViewById<TextView>(R.id.stepDetailStepTitle).text = step.title

        val descriptionView = content.findViewById<TextView>(R.id.stepDetailDescription)
        val description = step.description.trim()
        descriptionView.text = if (description.isEmpty()) {
            context.getString(R.string.roadmap_step_detail_no_description)
        } else {
            description
        }
        descriptionView.setTextColor(
            if (description.isEmpty()) {
                com.google.android.material.color.MaterialColors.getColor(
                    descriptionView,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
            } else {
                com.google.android.material.color.MaterialColors.getColor(
                    descriptionView,
                    com.google.android.material.R.attr.colorOnSurface,
                )
            },
        )

        bindMetaChips(context, content.findViewById(R.id.stepDetailMetaChips), step)

        dialog.setContentView(content)
        dialog.show()
    }

    private fun bindMetaChips(context: Context, chipGroup: ChipGroup, step: RoadmapStep) {
        chipGroup.removeAllViews()

        val priorityLabel = when (step.priority) {
            RoadmapStep.Priority.HIGH -> context.getString(R.string.home_today_plan_priority_high)
            RoadmapStep.Priority.MEDIUM -> context.getString(R.string.home_today_plan_priority_medium)
            RoadmapStep.Priority.LOW -> context.getString(R.string.home_today_plan_priority_low)
        }
        chipGroup.addView(createMetaChip(context, priorityLabel))

        val hoursLabel = formatEstimatedHours(context, step.estimatedHours)
        if (hoursLabel.isNotEmpty()) {
            chipGroup.addView(createMetaChip(context, hoursLabel))
        }

        val recommended = step.recommendedDate.trim()
        if (recommended.isNotEmpty()) {
            chipGroup.addView(
                createMetaChip(
                    context,
                    context.getString(R.string.roadmap_step_detail_recommended, recommended),
                ),
            )
        }

        if (step.completed) {
            chipGroup.addView(createMetaChip(context, context.getString(R.string.roadmap_step_detail_completed)))
        }
    }

    private fun createMetaChip(context: Context, label: String): Chip {
        return Chip(context).apply {
            text = label
            isCheckable = false
            isClickable = false
            setEnsureMinTouchTargetSize(false)
        }
    }

    private fun formatEstimatedHours(context: Context, hours: Double?): String {
        if (hours == null) return ""
        return when {
            hours < 1.0 / 60.0 -> context.getString(R.string.home_today_plan_step_time_minutes, 1)
            hours < 1.0 -> context.getString(
                R.string.home_today_plan_step_time_minutes,
                (hours * 60.0).roundToInt().coerceAtLeast(1),
            )
            else -> context.getString(R.string.home_today_plan_step_time_hours, hours)
        }
    }
}
