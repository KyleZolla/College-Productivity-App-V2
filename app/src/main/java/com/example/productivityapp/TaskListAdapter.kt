package com.example.productivityapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.time.LocalDate
import java.time.LocalDateTime

class TaskListAdapter(
    private val onTaskClick: (SupabaseTasksApi.TaskRow) -> Unit,
) : ListAdapter<SupabaseTasksApi.TaskRow, TaskListAdapter.TaskVH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskVH {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.item_task_row, parent, false)
        return TaskVH(view)
    }

    override fun onBindViewHolder(holder: TaskVH, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.status.text = TaskStatusUi.label(item.status)
        val ctx = holder.itemView.context
        val today = LocalDate.now()
        val due: LocalDateTime? = item.dueDate
        val dueFormatted = due?.let { DueDateTimeFormat.displayListRow(it) }
            ?: ctx.getString(R.string.due_date_not_set)
        holder.due.text = if (DueDateHumanLabel.isOverdue(due, item.status)) {
            ctx.getString(R.string.due_overdue_was_due, dueFormatted)
        } else {
            ctx.getString(R.string.task_row_due_line, dueFormatted)
        }
        holder.card.setOnClickListener { onTaskClick(item) }
    }

    class TaskVH(root: View) : RecyclerView.ViewHolder(root) {
        val card: MaterialCardView = root as MaterialCardView
        val title: TextView = card.findViewById(R.id.taskRowTitle)
        val status: TextView = card.findViewById(R.id.taskRowStatus)
        val due: TextView = card.findViewById(R.id.taskRowDue)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SupabaseTasksApi.TaskRow>() {
            override fun areItemsTheSame(a: SupabaseTasksApi.TaskRow, b: SupabaseTasksApi.TaskRow) = a.id == b.id
            override fun areContentsTheSame(a: SupabaseTasksApi.TaskRow, b: SupabaseTasksApi.TaskRow) =
                a == b
        }
    }
}
