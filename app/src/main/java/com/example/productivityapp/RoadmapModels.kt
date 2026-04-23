package com.example.productivityapp

import org.json.JSONArray
import org.json.JSONObject

data class RoadmapStep(
    val title: String,
    val description: String,
    val recommendedDate: String,
    val estimatedHours: Double?,
    val priority: Priority,
    val completed: Boolean,
) {
    enum class Priority { HIGH, MEDIUM, LOW }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("title", title)
            .put("description", description)
            .put("recommendedDate", recommendedDate)
            .put("estimatedHours", estimatedHours ?: JSONObject.NULL)
            .put("priority", when (priority) {
                Priority.HIGH -> "High"
                Priority.MEDIUM -> "Medium"
                Priority.LOW -> "Low"
            })
            .put("completed", completed)
    }

    companion object {
        fun parseList(roadmap: JSONArray?): List<RoadmapStep> {
            if (roadmap == null) return emptyList()
            val out = ArrayList<RoadmapStep>(roadmap.length())
            for (i in 0 until roadmap.length()) {
                val obj = roadmap.optJSONObject(i) ?: continue
                out.add(fromJson(obj))
            }
            return out
        }

        fun toJsonArray(steps: List<RoadmapStep>): JSONArray {
            val arr = JSONArray()
            steps.forEach { arr.put(it.toJson()) }
            return arr
        }

        fun fromJson(obj: JSONObject): RoadmapStep {
            val title = obj.optString("title")
                .ifBlank { obj.optString("name") }
                .ifBlank { "Step" }
            val description = obj.optString("description")
                .ifBlank { obj.optString("details") }
                .ifBlank { "" }
            val recommendedDate = obj.optString("recommendedDate")
                .ifBlank { obj.optString("date") }
                .ifBlank { obj.optString("recommended_date") }
                .ifBlank { "" }
            val estimatedHours = when {
                !obj.has("estimatedHours") || obj.isNull("estimatedHours") -> null
                else -> obj.optDouble("estimatedHours").takeIf { !it.isNaN() }
            }
            val priorityRaw = obj.optString("priority")
                .ifBlank { obj.optString("priorityLabel") }
            val priority = when (priorityRaw.trim().lowercase()) {
                "high" -> Priority.HIGH
                "low" -> Priority.LOW
                else -> Priority.MEDIUM
            }
            val completed = obj.optBoolean("completed", false)
            return RoadmapStep(
                title = title,
                description = description,
                recommendedDate = recommendedDate,
                estimatedHours = estimatedHours,
                priority = priority,
                completed = completed,
            )
        }
    }
}

object RoadmapProgress {
    data class Summary(val completed: Int, val total: Int) {
        val percent: Int = if (total <= 0) 0 else ((completed.toDouble() / total.toDouble()) * 100.0).toInt()
    }

    fun summarize(roadmap: JSONArray?): Summary {
        val steps = RoadmapStep.parseList(roadmap)
        val total = steps.size
        val completed = steps.count { it.completed }
        return Summary(completed = completed, total = total)
    }
}

