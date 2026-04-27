package com.example.productivityapp

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

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

        /**
         * Shifts parseable calendar dates in [recommendedDate] by [deltaDays].
         * Unparseable values are left unchanged.
         */
        fun shiftRecommendedDates(steps: List<RoadmapStep>, deltaDays: Long): List<RoadmapStep> {
            if (deltaDays == 0L) return steps
            return steps.map { step ->
                val parsed = parseRecommendedDate(step.recommendedDate) ?: return@map step
                val shifted = parsed.plusDays(deltaDays)
                step.copy(recommendedDate = shifted.format(DateTimeFormatter.ISO_LOCAL_DATE))
            }
        }

        /** Step [recommendedDate] as a calendar day, if parseable. */
        fun recommendedLocalDate(step: RoadmapStep): LocalDate? =
            parseRecommendedDate(step.recommendedDate)

        private fun parseRecommendedDate(raw: String): LocalDate? {
            val s = raw.trim()
            if (s.isEmpty()) return null

            // Common formats from Edge Functions / UI.
            val formatters = listOf(
                DateTimeFormatter.ISO_LOCAL_DATE, // yyyy-MM-dd
                DateTimeFormatter.ofPattern("M/d/yyyy"),
                DateTimeFormatter.ofPattern("MM/dd/yyyy"),
                DateTimeFormatter.ofPattern("MMM d, yyyy"),
                DateTimeFormatter.ofPattern("MMMM d, yyyy"),
            )
            for (fmt in formatters) {
                try {
                    return LocalDate.parse(s, fmt)
                } catch (_: DateTimeParseException) {
                    // try next
                }
            }

            // Last resort: try ISO-ish datetime strings.
            return try {
                TaskDueParsing.parseFlexible(s)?.toLocalDate()
            } catch (_: Exception) {
                null
            }
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

