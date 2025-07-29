package com.ontariotechu.crowdsense.sensors

import com.ontariotechu.crowdsense.data.CongestionResult

object CongestionEstimator {

    fun estimateCongestion(
        stepRate: Float,
        stopFrequency: Int,
        speed: Float,
        nearbyDevices: Int,
        accelVariance: Double = 0.0  // new input
    ): CongestionResult {
        // 1. Crowd density contribution
        val crowdFactor = when {
            nearbyDevices >= 8 -> 6 //increased to dominate
            nearbyDevices >= 5 -> 4
            nearbyDevices >= 3 -> 2
            else -> -1
        }

        // 2. Stop frequency (higher = more congestion)
        val stopScore = when {
            stopFrequency >= 5 -> 3
            stopFrequency >= 3 -> 2
            stopFrequency >= 1 -> 1
            else -> 0
        }

        // 3. Speed score (lower speed = more congestion)
        val speedScore = when {
            speed < 0.05f -> 2
            speed < 0.15f -> 1
            else -> 0
        }

        // 4. Step rate score (less steps = more congestion)
        val stepScore = when {
            stepRate < 0.4f -> 2
            stepRate < 1.2f -> 1
            else -> 0  // Higher step rate means lower congestion
        }

        // 5. Accel variance fallback score (detects slow movement)
        val motionVarianceScore = when {
            accelVariance >= 0.03 -> 0  // Higher motion = less congestion
            accelVariance >= 0.01 -> 2
            else -> 3
        }

        // 7. NEW: Slow walking fallback suspicion score
        val slowWalkingPenalty = if (
            stepRate < 0.3f &&
            speed in 0.03f..0.2f &&
            accelVariance < 0.02 &&
            nearbyDevices >= 3
        ) 2 else 0


        // 6. Stationary but crowded
        val stationaryPenalty = if (speed < 0.05f && nearbyDevices >= 5) 2 else 0

        // Total congestion score
        val congestionScore =
            crowdFactor + stopScore + speedScore + stepScore + stationaryPenalty
            + motionVarianceScore + slowWalkingPenalty

        val level = when {
            congestionScore >= 5 -> "High"
            congestionScore >= 3 -> "Medium"
            else -> "Low"
        }

        return CongestionResult(
            level = level,
            stepScore = stepScore,
            stopScore = stopScore,
            speedScore = speedScore,
            crowdFactor = crowdFactor
        )
    }
}
