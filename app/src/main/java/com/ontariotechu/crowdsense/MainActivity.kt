package com.ontariotechu.crowdsense

//Firebase Realtime Database

// For Firebase Database listeners
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ontariotechu.crowdsense.sensors.CongestionEstimator
import com.ontariotechu.crowdsense.data.CongestionResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import android.view.Menu
import android.view.MenuItem
import android.content.Intent
import com.ontariotechu.crowdsense.ReportDashboardActivity


class MainActivity : AppCompatActivity(), SensorEventListener {


    enum class PostureState {
        UNKNOWN,
        SITTING,
        STANDING,
        WALKING,
        IDLE
    }


    private val REQUEST_CODE_PERMISSIONS = 1001
    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()


    private lateinit var sensorManager: SensorManager
    private lateinit var congestionText: TextView
    private lateinit var reportButton: Button
    private lateinit var congestionSpinner: Spinner
    private lateinit var ssidText: TextView
    private lateinit var locationText: TextView

    private var stepCount = 0
    private var lastStepValue = -1f
    private var accelSpeedList = mutableListOf<Float>()
    private var stopCount = 0
    private var lastAccelTimestamp = 0L
    private var stationaryThreshold = 0.15f

    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval: Long = 5000 // 5 seconds

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var nearbyDevices = mutableSetOf<String>()
    private val pitchValues = mutableListOf<Float>()

    private var logFile: File? = null

    private var isSitting = false

    private var currentPosture = PostureState.UNKNOWN
    private var lastPosture = PostureState.UNKNOWN
    private lateinit var postureText: TextView
    private var lastReportTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setupLogFile()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ssidText = findViewById(R.id.ssidText)
        locationText = findViewById(R.id.locationText)

        congestionText = findViewById(R.id.congestionText)
        postureText = findViewById(R.id.postureText)

        congestionSpinner = findViewById(R.id.userCongestionSpinner)
        reportButton = findViewById(R.id.reportButton)

        reportButton.setOnClickListener {
            val selectedLevel = congestionSpinner.selectedItem.toString()
            reportCongestionToFirebase(selectedLevel)
        }

        checkAndRequestPermissions()

        REQUIRED_PERMISSIONS.forEach {
            val granted = ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            Log.d("CrowdSense", "$it granted: $granted")
        }

        setupSensors()

        Toast.makeText(
            this,
            "Step Counter: ${hasSensor(Sensor.TYPE_STEP_COUNTER)} | Accelerometer: ${hasSensor(Sensor.TYPE_ACCELEROMETER)}",
            Toast.LENGTH_LONG
        ).show()

        startCongestionUpdateLoop()
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions += Manifest.permission.ACTIVITY_RECOGNITION
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        } else {
            startBluetoothScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val denied = permissions.zip(grantResults.toTypedArray()).filter {
                it.second != PackageManager.PERMISSION_GRANTED
            }

            if (denied.isEmpty()) {
                Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
                startBluetoothScan()
            } else {
                Toast.makeText(this, "Some permissions denied: ${denied.map { it.first }}", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun hasSensor(type: Int): Boolean {
        return sensorManager.getDefaultSensor(type) != null
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                if (lastStepValue == -1f) {
                    lastStepValue = event.values[0]
                } else {
                    stepCount = (event.values[0] - lastStepValue).toInt()
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val now = System.currentTimeMillis()
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // Calculate movement
                val accel = sqrt(x.pow2() + y.pow2() + z.pow2()) - SensorManager.GRAVITY_EARTH
                val speed = abs(accel)
                accelSpeedList.add(speed)

                if (now - lastAccelTimestamp > 1000) {
                    if (speed < stationaryThreshold) stopCount++
                    lastAccelTimestamp = now
                }

                // ---------- Posture detection using rolling pitch ----------
                val pitch = Math.toDegrees(atan2(y.toDouble(), sqrt(x.pow2() + z.pow2()).toDouble())).toFloat()

                pitchValues.add(pitch)
                if (pitchValues.size > 20) pitchValues.removeAt(0)

                val avgPitch = pitchValues.average()
                //isSitting = avgPitch in 45.0..120.0  // Adjust range based on pocket/orientation

                val accelVariance = accelSpeedList.let {
                    val mean = it.average()
                    it.map { v -> (v - mean) * (v - mean) }.average()
                }

                isSitting = accelVariance < 0.01 && avgPitch in 70.0..100.0


                Log.d("CrowdSense", "Pitch: $pitch, Avg: $avgPitch, isSitting: $isSitting")
            }

        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startBluetoothScan() {
        if (bluetoothAdapter?.isEnabled == true) {
            bluetoothAdapter.bluetoothLeScanner?.startScan(object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.device?.address?.let { nearbyDevices.add(it) }
                }
            })
        }
    }

    private fun startCongestionUpdateLoop() {
        handler.post(object : Runnable {
            override fun run() {
                updateCongestionLevel()
                handler.postDelayed(this, updateInterval)
            }
        })
    }

    private fun updateCongestionLevel() {
        val avgSpeed = if (accelSpeedList.isNotEmpty()) accelSpeedList.average().toFloat() else 0f

        val accelVariance = accelSpeedList.let {
            val mean = it.average()
            it.map { v -> (v - mean) * (v - mean) }.average()
        }

        val fallbackStepRate = if (accelSpeedList.isNotEmpty()) {
            val variance = accelSpeedList.let {
                val mean = it.average()
                it.map { v -> (v - mean) * (v - mean) }.average()
            }
            if (variance in 0.005..0.02 && avgSpeed > 0.05f) 0.3f else 0f
        } else 0f


        val stepRate = if (stepCount > 0) {
            stepCount / (updateInterval / 1000f)
        } else {
            fallbackStepRate
        }

        val isIdle = isPhoneIdle()
        val result = if (isIdle) {
            CongestionResult("Idle", 0, 0, 0, 0)
        } else {
            CongestionEstimator.estimateCongestion(
                stepRate = stepRate,
                stopFrequency = stopCount,
                speed = avgSpeed,
                nearbyDevices = nearbyDevices.size,
                accelVariance = accelVariance
            )

        }

        val congestion = result.level

        //congestionText.text = "Congestion: $congestion\nSteps: $stepCount\nNearby: ${nearbyDevices.size}\n"
        fetchAndAggregateReports { crowdReportedLevel, voteCounts ->
            val blendedLevel = combineSensorAndCrowd(result.level, crowdReportedLevel)

            updateUIWithCongestionLevel(blendedLevel, voteCounts)

            val timestamp = System.currentTimeMillis()
            val logLine = "$timestamp,$stepRate,$avgSpeed,$stopCount,${nearbyDevices.size},Sensor:${result.level},User:$crowdReportedLevel,Blended:$blendedLevel,$isSitting,$currentPosture\n"
            logFile?.appendText(logLine)

            Log.d("CrowdSense", "Blended: $blendedLevel | Sensor: ${result.level} | Crowd: $crowdReportedLevel")
        }




        val pitchVariance = pitchValues.let {
            if (it.isEmpty()) 0.0 else {
                val mean = it.average()
                it.map { v -> (v - mean) * (v - mean) }.average()
            }
        }

        Log.d("MotionDebug", "stepRate=$stepRate, variance=$pitchVariance, avgSpeed=$avgSpeed")

        val posture = updatePosture(stepRate, pitchValues.lastOrNull() ?: 0f, pitchVariance)
        if (posture != currentPosture) {
            lastPosture = currentPosture
            currentPosture = posture
            Log.d("Posture", "Changed from $lastPosture to $currentPosture")
        }

        postureText.text = "Posture: $currentPosture"
        postureText.setTextColor(android.graphics.Color.BLACK)

        // Show SSID
        val ssid = getCurrentWifiSSID()
        ssidText.text = "SSID: $ssid"

        // Show Location
        getCurrentLocation { location ->
            if (location != null) {
                val lat = String.format("%.5f", location.latitude)
                val lon = String.format("%.5f", location.longitude)
                locationText.text = "Location: $lat, $lon"
            } else {
                locationText.text = "Location: Unknown"
            }
        }


        //Log entry
        val timestamp = System.currentTimeMillis()
        val line = "$timestamp,$stepRate,$avgSpeed,$stopCount,${nearbyDevices.size},$isSitting,$currentPosture\n"
        logFile?.appendText(line)

        Log.d(
            "CrowdSense",
            "Scores - Step: ${result.stepScore}, Stop: ${result.stopScore}, Speed: ${result.speedScore}, Crowd: ${result.crowdFactor}"
        )
        // Reset metrics
        accelSpeedList.clear()
        stopCount = 0
        stepCount = 0
        nearbyDevices.clear()
    }

    /*private fun updatePosture(stepRate: Float, pitch: Float, variance: Double): PostureState {
        return when {
            pitch > 60 && variance < 0.01 && stepRate == 0f -> PostureState.SITTING
            stepRate > 1.0f || variance > 0.02 -> PostureState.WALKING
            stepRate == 0f && variance < 0.005 && pitch in -20.0..30.0 -> PostureState.IDLE
            stepRate == 0f && variance < 0.01 -> PostureState.STANDING
            else -> PostureState.UNKNOWN
        }
    }*/

    /*private fun updatePosture(stepRate: Float, pitch: Float, variance: Double): PostureState {
        return when {
            pitch in 40f..120f && variance < 0.015 && stepRate == 0f -> PostureState.SITTING
            pitch in -10f..50f && variance < 0.02 && stepRate == 0f -> PostureState.STANDING
            stepRate > 1.0f || variance > 0.02 -> PostureState.WALKING
            stepRate == 0f && variance < 0.005 -> PostureState.IDLE
            else -> PostureState.UNKNOWN
        }
    }*/

    /*private fun updatePosture(stepRate: Float, pitch: Float, variance: Double): PostureState {
        return when {
            // Sitting: very low motion, typical pitch angle for sitting
            pitch in 70.0..110.0 && variance < 0.01 && stepRate == 0f -> PostureState.SITTING

            // Walking: active steps or lots of motion
            stepRate > 1.0f || variance > 0.02 -> PostureState.WALKING

            // Standing: still, upright-ish, but not sitting
            stepRate == 0f && variance in 0.005..0.02 && pitch in -20.0..60.0 -> PostureState.STANDING

            // Idle: likely on table or not carried
            stepRate == 0f && variance < 0.005 -> PostureState.IDLE

            else -> PostureState.UNKNOWN
        }
    }*/

    private fun updatePosture(stepRate: Float, pitch: Float, variance: Double): PostureState {
        return when {
            // 🚶 WALKING: high movement or clear step rate
            stepRate > 1.0f || variance > 0.02 -> PostureState.WALKING

            // 🪑 SITTING: phone tilted slightly (lap/table), very low motion
            pitch in -30.0..30.0 && variance < 0.01 && stepRate == 0f -> PostureState.SITTING

            // 🧍 STANDING: upright pitch, minimal motion
            pitch in 60.0..120.0 && variance in 0.005..0.02 && stepRate == 0f -> PostureState.STANDING

            // 💤 IDLE: flat, no movement, likely left unattended
            variance < 0.005 && stepRate == 0f -> PostureState.IDLE

            else -> PostureState.UNKNOWN
        }
    }







    private fun setupLogFile() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        logFile = File(getExternalFilesDir(null), "congestion_log_$timestamp.csv")
        logFile?.appendText("timestamp,step_rate,avg_speed,stop_count,nearby_devices,congestion,is_sitting, posture\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    private fun Float.pow2() = this * this

    private fun checkAndRequestPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            startBluetoothScan()
        }
    }

    private fun isPhoneIdle(): Boolean {
        val accelVariance = accelSpeedList.let {
            if (it.isEmpty()) return true
            val mean = it.average()
            val variance = it.map { v -> (v - mean) * (v - mean) }.average()
            return variance < 0.01
        }
        return accelVariance && stepCount == 0
    }

    private fun logUserReportedCongestion(level: String) {
        val timestamp = System.currentTimeMillis()
        val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val reportEntry = "USER_REPORT,$timestamp,$formattedTime,Reported:$level,Steps:$stepCount,Devices:${nearbyDevices.size},Posture:$currentPosture\n"

        logFile?.appendText(reportEntry)
        Log.d("UserReport", reportEntry)
    }


    fun reportCongestionToFirebase(level: String) {

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastReportTime < 60_000) {
            Toast.makeText(this, "Please wait before reporting again", Toast.LENGTH_SHORT).show()
            return
        }
        lastReportTime = currentTime

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)


        val ssid = getCurrentWifiSSID()
        logUserReportedCongestion(level)

        getCurrentLocation { location ->

            val database = FirebaseDatabase.getInstance().getReference("reports")
            val report = mutableMapOf<String, Any>(
                "timestamp" to currentTime,
                "level" to level,
                "ssid" to ssid,
                "deviceId" to deviceId
            )

            location?.let {
                report["latitude"] = it.latitude
                report["longitude"] = it.longitude
            }

            database.push().setValue(report)

            // Show success toast here
            runOnUiThread {
                Toast.makeText(this, "Reported $level congestion. Thank you!", Toast.LENGTH_SHORT).show()
            }
        }



    }

    /*fun fetchAndAggregateReports(callback: (String, Map<String, Int>) -> Unit) {
        val reportsRef = FirebaseDatabase.getInstance().getReference("reports")
        val oneMinuteAgo = System.currentTimeMillis() - 60_000

        reportsRef.orderByChild("timestamp").startAt(oneMinuteAgo.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val uniqueVotes = mutableMapOf<String, String>()

                    snapshot.children.forEach {
                        val deviceId = it.child("deviceId").value?.toString()
                        val level = it.child("level").value?.toString()
                        if (deviceId != null && level != null) {
                            uniqueVotes[deviceId] = level
                        }
                    }

                    val counts = mutableMapOf("Low" to 0, "Medium" to 0, "High" to 0)
                    uniqueVotes.values.forEach { level ->
                        if (level in counts) counts[level] = counts[level]!! + 1
                    }

                    val maxLevel = counts.maxByOrNull { it.value }?.key ?: "Low"
                    callback(maxLevel, counts)
                }

                override fun onCancelled(error: DatabaseError) {
                    callback("Low", mapOf("Low" to 0, "Medium" to 0, "High" to 0))
                }
            })
    }*/

    fun fetchAndAggregateReports(callback: (String, Map<String, Int>) -> Unit) {
        val reportsRef = FirebaseDatabase.getInstance().getReference("reports")
        val oneMinuteAgo = System.currentTimeMillis() - 60_000
        val currentSsid = getCurrentWifiSSID()

        getCurrentLocation { currentLocation ->
            reportsRef.orderByChild("timestamp").startAt(oneMinuteAgo.toDouble())
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val uniqueVotes = mutableMapOf<String, String>()

                        for (child in snapshot.children) {
                            val deviceId = child.child("deviceId").value?.toString() ?: continue
                            val level = child.child("level").value?.toString() ?: continue

                            val ssid = child.child("ssid").getValue(String::class.java)
                            val lat = child.child("latitude").getValue(Double::class.java)
                            val lon = child.child("longitude").getValue(Double::class.java)

                            val isSameSsid = ssid != null && ssid == currentSsid
                            val isNearby = if (lat != null && lon != null && currentLocation != null) {
                                val result = FloatArray(1)
                                Location.distanceBetween(
                                    currentLocation.latitude, currentLocation.longitude,
                                    lat, lon, result
                                )
                                result[0] < 50
                            } else false

                            if (isSameSsid || isNearby) {
                                uniqueVotes[deviceId] = level
                            }
                        }

                        val counts = mutableMapOf("Low" to 0, "Medium" to 0, "High" to 0)
                        uniqueVotes.values.forEach { level ->
                            if (level in counts) counts[level] = counts[level]!! + 1
                        }

                        val maxLevel = counts.maxByOrNull { it.value }?.key ?: "Low"
                        callback(maxLevel, counts)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        callback("Low", mapOf("Low" to 0, "Medium" to 0, "High" to 0))
                    }
                })
        }
    }




    private fun combineSensorAndCrowd(sensor: String, crowd: String): String {
        return when {
            // Prioritize High if either source reports High
            sensor == "High" || crowd == "High" -> "High"

            // If both are Medium or one is Medium and other is Low → Medium
            sensor == "Medium" || crowd == "Medium" -> "Medium"

            // Default case: both are Low or unknown
            else -> "Low"
        }
    }


    private fun updateUIWithCongestionLevel(level: String, voteCounts: Map<String, Int>) {
        val lowVotes = voteCounts["Low"] ?: 0
        val medVotes = voteCounts["Medium"] ?: 0
        val highVotes = voteCounts["High"] ?: 0
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        val summaryText = """
        Congestion: $level
        Steps: $stepCount
        Nearby Devices: ${nearbyDevices.size}
        Last Updated: $time
        Votes: Low=$lowVotes | Medium=$medVotes | High=$highVotes
    """.trimIndent()

        congestionText.text = summaryText

        when (level) {
            "High" -> {
                congestionText.setBackgroundColor(android.graphics.Color.RED)
                congestionText.setTextColor(android.graphics.Color.WHITE)
            }
            "Medium" -> {
                congestionText.setBackgroundColor(android.graphics.Color.YELLOW)
                congestionText.setTextColor(android.graphics.Color.BLACK)
            }
            else -> {
                congestionText.setBackgroundColor(android.graphics.Color.GREEN)
                congestionText.setTextColor(android.graphics.Color.WHITE)
            }
        }
    }


    private fun getCurrentWifiSSID(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo: WifiInfo? = wifiManager.connectionInfo
        return wifiInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: "Unknown"
    }

    private fun getCurrentLocation(callback: (Location?) -> Unit) {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            callback(null)
            return
        }

        val provider = LocationManager.GPS_PROVIDER
        locationManager.requestSingleUpdate(provider, object : LocationListener {
            override fun onLocationChanged(location: Location) {
                callback(location)
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }, null)
    }


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_view_reports -> {
                val intent = Intent(this, ReportDashboardActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }





}
