
# CrowdSense

**CrowdSense** is a real-time indoor congestion detection Android app that leverages mobile sensors and crowd-sourced feedback to estimate human congestion levels in indoor environments.  
It uses the device’s accelerometer, step counter, Bluetooth, GPS, and Wi-Fi information to determine user posture, detect crowd proximity, and blend sensor-based and user-reported congestion data.

---

## 📱 Features

- ✅ **Sensor-based Congestion Estimation**
  - Step rate, accelerometer variance, and nearby Bluetooth device count
- 🧍 **Posture Detection**
  - Detects **SITTING**, **STANDING**, **WALKING**, or **IDLE** using pitch angle and motion
- 📶 **Bluetooth Scanning**
  - Scans nearby BLE devices as a proxy for crowd density
- 📡 **Wi-Fi + GPS Context**
  - Associates reports with current **Wi-Fi SSID** and **GPS location**
- 🗳️ **User Voting System**
  - Users can report congestion level as **Low**, **Medium**, or **High**
- 🔄 **Hybrid Congestion Estimation**
  - Combines local sensor output with recent user votes to derive final level
- 📊 **UI Dashboard**
  - Shows real-time congestion, steps, posture, nearby devices, and crowd vote counts
- 🧪 **CSV Logging**
  - Logs all runtime metrics for offline analysis

---

## 🔧 Tech Stack

| Component | Details |
|----------|---------|
| Platform | Android (minSdk 23, targetSdk 34) |
| Language | Kotlin |
| Firebase | Realtime Database (for user voting and aggregation) |
| Sensors Used | Accelerometer, Step Counter, Bluetooth, Location |
| Libraries | AndroidX, Firebase SDK, Material Components |

---

## 🚀 Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/your-username/CrowdSense.git
cd CrowdSense
```

### 2. Open in Android Studio

Ensure Android Studio has:
- Kotlin support
- Firebase plugin
- Google services JSON setup

### 3. Add Firebase configuration

Place your `google-services.json` file inside:

```
app/google-services.json
```

This file is **excluded from Git** and must be generated from your [Firebase Console](https://console.firebase.google.com/).

### 4. Run the app

- Use a physical Android device (recommended for accurate sensor readings)
- Grant location and activity permissions when prompted

---

## 📂 Folder Structure

```
CrowdSense/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/ontariotechu/crowdsense/
│   │       │   ├── MainActivity.kt
│   │       │   └── sensors/
│   │       │       └── CongestionEstimator.kt
│   │       └── res/layout/activity_main.xml
├── .gitignore
└── README.md
```

---

## 🔒 Privacy Notes

- No personally identifiable data is collected.
- Device IDs are hashed and only used to deduplicate votes.
- Location and Wi-Fi SSID are used only to filter relevant nearby reports.

---

## 🧪 Future Improvements

- Integrate Wi-Fi scanning APIs for non-Bluetooth devices
- Visual heatmap for real-time congestion view
- Historical trends using Firestore or local Room DB
- Integration with wearables (e.g., smartwatches)

---

## 📜 License

This project is licensed under the MIT License.

---

## 🙌 Acknowledgements

- Ontario Tech University – Research Support
- Firebase – Realtime Data Infrastructure
- Android Developers – Sensor APIs and Documentation
