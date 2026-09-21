# Shuttle Management System (SMS) 🚌🛣️

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Java](https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&logo=java&logoColor=white)
![Build](https://img.shields.io/badge/build-passing-brightgreen?style=for-the-badge)

An offline-first Android mobile application designed to manage campus/company shuttle routes, track passenger loads, estimate fares, and prevent shuttle overcapacity using internal File I/O persistence.

---

## 📌 Table of Contents
- [Overview](#-overview)
- [Core Features](#-core-features)
- [System Architecture](#-system-architecture)
- [Data Storage](#-data-storage)
- [Project Structure](#-project-structure)
- [Getting Started](#-getting-started)
- [Contributors](#-contributors)

---

## 📖 Overview
Shuttle services on campuses and corporate compounds often face the risk of overcrowded or overbooked trips due to unmanaged, peak-hour boarding. **SMS** provides a dual-interface Android solution that tracks passenger load at the shuttle level. By aggregating this data locally on the device, the system acts as a software-level failsafe—intercepting boarding updates that exceed a shuttle's maximum seating capacity to prevent unsafe overcrowding.

## ⚙️ Core Features
* **Role-Based Access Control:** Secure authentication routing users to either a localized Passenger/Booking UI or a macro-level Dispatcher/Admin UI.
* **Shuttle CRUD Operations:** Full management of individual shuttle units treated as encapsulated objects in memory.
* **Real-Time Load Aggregation:** Iterates through active shuttle objects to calculate total fleet passenger load against hardcoded seating limits.
* **Fare Estimation Engine:** Converts route distance and passenger count into projected fare costs based on current fare rates.
* **Active Overcapacity Prevention:** Core system logic that blocks local file write operations if a boarding update exceeds a shuttle's maximum seat capacity, triggering an Android Toast/Dialog warning to force rerouting or the next available shuttle.

---

## 🏗️ System Architecture

The system utilizes strict Object-Oriented Programming principles and relies on memory management (via `ArrayLists`) mapped to local app storage for persistence, completely bypassing the need for a cloud backend or SQL database.

| CLASS | ATTRIBUTES | METHODS |
| :--- | :--- | :--- |
| **User** | `username`, `passwordHash`, `userID`, `roleType` | `register()`, `login()`, `logOut()`, `verifyCredentials()` |
| **Shuttle** | `shuttleID`, `routeName`, `seatCapacity`, `currentPassengers` | `registerShuttle()`, `updatePassengerCount()`, `removeShuttle()`, `getLoad()` |
| **FleetManager** | `shuttleList` (ArrayList), `totalPassengers`, `maxFleetCapacity`, `fareRate` | `displayDashboard()`, `calculateTotalLoad()`, `estimateFares()`, `checkOvercapacity()` |
| **FileHandler** | `internalFilePath` | `readData()`, `writeData()`, `updateRecord()` |

---

## 🗄️ Data Storage
This project utilizes **Android Internal Storage (Context.openFileOutput)** for offline state management, ensuring data is kept secure and local to the device.
* `credentials.txt` - Stores hashed user credentials and role definitions.
* `shuttle_data.csv` - Stores serialized `Shuttle` object states (Shuttle ID, Route, Passenger Count).

---

## 📂 Project Structure

```text
SMS-Android/
├── app/src/main/
│   ├── java/com/sms/app/
│   │   ├── activities/       # UI Controllers (Login, Dashboard)
│   │   ├── models/           # OOP Classes (User, Shuttle, FleetManager)
│   │   └── utils/            # FileHandler logic
│   ├── res/
│   │   ├── layout/           # XML UI designs
│   │   └── values/           # Colors, Strings, Themes
│   └── AndroidManifest.xml   # App configuration
└── README.md
```  
