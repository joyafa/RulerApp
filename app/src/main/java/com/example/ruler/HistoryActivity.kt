package com.example.ruler

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 测量历史记录列表。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: HistoryAdapter
    private lateinit var btnClear: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        recyclerView = findViewById(R.id.recyclerView)
        btnClear = findViewById(R.id.btnClear)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = HistoryAdapter()
        recyclerView.adapter = adapter

        btnClear.setOnClickListener {
            MeasurementStore.clear(this)
            adapter.refresh()
            Toast.makeText(this, "已清空历史", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        adapter.refresh()
    }

    inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {
        private var items = listOf<MeasurementStore.Record>()
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun refresh() {
            items = MeasurementStore.getAll(this@HistoryActivity)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            val typeLabel = when (r.type) {
                "ruler" -> "屏幕尺"
                "camera" -> "相机"
                "ar" -> "AR"
                else -> r.type
            }
            holder.tvType.text = typeLabel
            holder.tvValue.text = r.display()
            holder.tvTime.text = fmt.format(Date(r.timestamp))
            holder.itemView.setOnLongClickListener {
                MeasurementStore.delete(this@HistoryActivity, r.id)
                refresh()
                Toast.makeText(this@HistoryActivity, "已删除", Toast.LENGTH_SHORT).show()
                true
            }
        }

        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvType: TextView = v.findViewById(R.id.tvType)
            val tvValue: TextView = v.findViewById(R.id.tvValue)
            val tvTime: TextView = v.findViewById(R.id.tvTime)
        }
    }
}
