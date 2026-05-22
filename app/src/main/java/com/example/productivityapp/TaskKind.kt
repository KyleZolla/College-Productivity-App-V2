package com.example.productivityapp

/**
 * Simple tasks have no roadmap steps — completion is a single checkbox.
 * Complex tasks use AI-generated roadmap steps.
 */
object TaskKind {
    fun isSimpleTask(task: SupabaseTasksApi.TaskRow): Boolean =
        RoadmapStep.parseList(task.roadmap).isEmpty()
}
