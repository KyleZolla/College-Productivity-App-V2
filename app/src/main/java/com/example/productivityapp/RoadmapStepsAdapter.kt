package com.example.productivityapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RoadmapStepsAdapter(
    private val onToggle: (index: Int, checked: Boolean) -> Unit,
) : RecyclerView.Adapter<RoadmapStepsAdapter.VH>() {

    private val items = ArrayList<RoadmapStep>()
    private var suppressCallback = false

    fun submitList(steps: List<RoadmapStep>) {
        items.clear()
        items.addAll(steps)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_roadmap_step, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.description.text = item.description
        holder.priority.text = when (item.priority) {
            RoadmapStep.Priority.HIGH -> "High"
            RoadmapStep.Priority.MEDIUM -> "Medium"
            RoadmapStep.Priority.LOW -> "Low"
        }
        holder.recommendedDate.text = if (item.recommendedDate.isBlank()) "" else "Recommended: ${item.recommendedDate}"
        holder.estimatedHours.text = item.estimatedHours?.let { "~${trimHours(it)}h" }.orEmpty()

        suppressCallback = true
        holder.check.isChecked = item.completed
        suppressCallback = false

        holder.check.setOnCheckedChangeListener { _, isChecked ->
            if (suppressCallback) return@setOnCheckedChangeListener
            onToggle(position, isChecked)
        }
    }

    private fun trimHours(v: Double): String {
        val rounded = (kotlin.math.round(v * 10.0) / 10.0)
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    class VH(root: View) : RecyclerView.ViewHolder(root) {
        val check: CheckBox = root.findViewById(R.id.roadmapStepCheck)
        val title: TextView = root.findViewById(R.id.roadmapStepTitle)
        val priority: TextView = root.findViewById(R.id.roadmapStepPriority)
        val description: TextView = root.findViewById(R.id.roadmapStepDescription)
        val recommendedDate: TextView = root.findViewById(R.id.roadmapStepRecommendedDate)
        val estimatedHours: TextView = root.findViewById(R.id.roadmapStepEstimatedHours)
    }
}

