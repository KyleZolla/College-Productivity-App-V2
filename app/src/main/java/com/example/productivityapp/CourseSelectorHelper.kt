package com.example.productivityapp

import android.content.Context
import android.widget.ArrayAdapter
import com.google.android.material.textfield.MaterialAutoCompleteTextView

object CourseSelectorHelper {

    data class CourseOption(
        val id: String?,
        val label: String,
    )

    fun formatCourseLabel(course: SupabaseCoursesApi.CourseRow): String =
        "${course.name} (${course.level})"

    fun buildOptions(
        courses: List<SupabaseCoursesApi.CourseRow>,
        noneLabel: String,
    ): List<CourseOption> {
        val out = ArrayList<CourseOption>(courses.size + 1)
        out.add(CourseOption(id = null, label = noneLabel))
        for (course in courses) {
            out.add(CourseOption(id = course.id, label = formatCourseLabel(course)))
        }
        return out
    }

    fun bind(
        context: Context,
        dropdown: MaterialAutoCompleteTextView,
        options: List<CourseOption>,
        selectedCourseId: String?,
    ) {
        val labels = options.map { it.label }
        dropdown.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, labels),
        )
        val selected = options.find { it.id == selectedCourseId } ?: options.firstOrNull()
        dropdown.setText(selected?.label.orEmpty(), false)
    }

    fun selectedCourseId(
        dropdown: MaterialAutoCompleteTextView,
        options: List<CourseOption>,
    ): String? {
        val text = dropdown.text?.toString()?.trim().orEmpty()
        return options.find { it.label == text }?.id
    }

    fun labelFor(courseId: String?, labels: Map<String, String>): String? =
        courseId?.let { labels[it] }

    fun labelsById(courses: List<SupabaseCoursesApi.CourseRow>): Map<String, String> =
        courses.associate { it.id to it.name }
}
