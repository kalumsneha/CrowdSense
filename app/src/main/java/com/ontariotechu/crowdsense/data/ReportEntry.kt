package com.ontariotechu.crowdsense.data

data class ReportEntry(
    val deviceId: String? = null,
    val level: String? = null,
    val ssid: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timestamp: Long = 0
)
