package com.ontariotechu.crowdsense.data

data class CongestionResult(
    val level: String,
    val stepScore: Int,
    val stopScore: Int,
    val speedScore: Int,
    val crowdFactor: Int
)