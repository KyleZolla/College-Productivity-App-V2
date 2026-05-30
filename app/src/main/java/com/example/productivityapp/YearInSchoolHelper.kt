package com.example.productivityapp

import android.content.Context
import android.widget.ArrayAdapter
import com.google.android.material.textfield.MaterialAutoCompleteTextView

object YearInSchoolHelper {

    val OPTIONS = listOf(
        "Freshman",
        "Sophomore",
        "Junior",
        "Senior",
        "Graduate",
    )

    fun bind(
        context: Context,
        dropdown: MaterialAutoCompleteTextView,
        selected: String?,
    ) {
        dropdown.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, OPTIONS),
        )
        dropdown.setText(selected?.takeIf { it in OPTIONS }.orEmpty(), false)
    }

    fun selectedValue(dropdown: MaterialAutoCompleteTextView): String? {
        val text = dropdown.text?.toString()?.trim().orEmpty()
        return text.takeIf { it in OPTIONS }
    }
}
