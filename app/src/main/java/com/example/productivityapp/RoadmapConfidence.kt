package com.example.productivityapp

/**
 * Heuristic confidence label for AI-generated roadmaps on complex tasks.
 * Computed locally at task creation — no AI involved.
 */
enum class RoadmapConfidence(val apiValue: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low");

    companion object {
        fun fromApi(raw: String?): RoadmapConfidence? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return entries.firstOrNull { it.apiValue.equals(trimmed, ignoreCase = true) }
        }
    }
}

data class RoadmapConfidenceInput(
    val documentContent: String? = null,
    val photoText: String? = null,
    val requirements: String? = null,
    val userEstimatedHours: Double? = null,
    val courseSelected: Boolean = false,
)

object RoadmapConfidenceCalculator {
    fun calculate(input: RoadmapConfidenceInput): RoadmapConfidence {
        if (!hasQualifyingInput(input)) return RoadmapConfidence.LOW
        if (hasAssignmentInstructions(input)) return RoadmapConfidence.HIGH
        return RoadmapConfidence.MEDIUM
    }

    /** At least one of: course, document, photo, time estimate, or additional details. */
    private fun hasQualifyingInput(input: RoadmapConfidenceInput): Boolean {
        if (input.courseSelected) return true
        if (input.userEstimatedHours != null) return true
        if (hasAssignmentInstructions(input)) return true
        return false
    }

    private fun hasAssignmentInstructions(input: RoadmapConfidenceInput): Boolean {
        if (input.documentContent.isNotBlank()) return true
        if (input.photoText.isNotBlank()) return true
        if (input.requirements.isNotBlank()) return true
        return false
    }

    private fun String?.isNotBlank(): Boolean = !this.isNullOrBlank()
}
