package com.ontariotechu.crowdsense

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ontariotechu.crowdsense.databinding.ItemReportBinding
import com.ontariotechu.crowdsense.data.CongestionResult
import com.ontariotechu.crowdsense.data.ReportEntry
import java.text.SimpleDateFormat
import java.util.*

class ReportAdapter(private val reports: List<ReportEntry>) : RecyclerView.Adapter<ReportAdapter.ReportViewHolder>() {

    class ReportViewHolder(val binding: ItemReportBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val binding = ItemReportBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ReportViewHolder(binding)
    }

    override fun getItemCount(): Int = reports.size

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        val report = reports[position]
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(report.timestamp))

        holder.binding.reportText.text = """
            Time: $time
            Level: ${report.level}
            SSID: ${report.ssid ?: "N/A"}
            Lat: ${report.latitude ?: "N/A"}
            Lon: ${report.longitude ?: "N/A"}
            Device ID: ${report.deviceId ?: "N/A"}
        """.trimIndent()
    }
}
