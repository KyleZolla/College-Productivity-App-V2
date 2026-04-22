package com.example.productivityapp

/** Values match the `tasks.status` column in Supabase (exact strings). */
enum class TaskStatus(val apiValue: String) {
    NOT_STARTED("Not Started"),
    IN_PROGRESS("In Progress"),
    COMPLETE("Complete"),
    ;

    companion object {
        fun fromApi(raw: String?): TaskStatus {
            if (raw.isNullOrBlank()) return NOT_STARTED
            val trimmed = raw.trim()
            entries.firstOrNull { it.apiValue.equals(trimmed, ignoreCase = true) }?.let { return it }
            return when (trimmed.lowercase()) {
                "open", "todo", "pending", "not started", "notstarted" -> NOT_STARTED
                "in_progress", "inprogress" -> IN_PROGRESS
                "done", "completed", "complete" -> COMPLETE
                else -> NOT_STARTED
            }
        }
    }
}

object TaskStatusUi {
    /** Same text PostgREST stores and returns for `status` (no separate “display” mapping). */
    fun label(status: TaskStatus): String = status.apiValue
}
