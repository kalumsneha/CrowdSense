package com.ontariotechu.crowdsense

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.database.*
import com.ontariotechu.crowdsense.data.ReportEntry
import com.ontariotechu.crowdsense.databinding.ActivityReportDashboardBinding

class ReportDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportDashboardBinding
    private lateinit var database: DatabaseReference
    private lateinit var adapter: ReportAdapter
    private val reportList = mutableListOf<ReportEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ReportAdapter(reportList)
        binding.reportRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.reportRecyclerView.adapter = adapter

        database = FirebaseDatabase.getInstance().getReference("reports")
        fetchReports()
    }

    private fun fetchReports() {
        val oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000

        database.orderByChild("timestamp").startAt(oneHourAgo.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    reportList.clear()
                    for (reportSnap in snapshot.children) {
                        val report = reportSnap.getValue(ReportEntry::class.java)
                        if (report != null) {
                            reportList.add(report)
                        }
                    }
                    reportList.sortByDescending { it.timestamp }
                    adapter.notifyDataSetChanged()
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@ReportDashboardActivity, "Failed to load reports", Toast.LENGTH_SHORT).show()
                    Log.e("ReportDashboard", "Database error: ${error.message}")
                }
            })
    }
}
