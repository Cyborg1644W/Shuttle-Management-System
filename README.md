# Condominium Energy Management System (CEMS) ⚡🏢

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Java](https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&logo=java&logoColor=white)
![Build](https://img.shields.io/badge/build-passing-brightgreen?style=for-the-badge)

An offline-first Android mobile application designed to manage condominium unit electricity usage, estimate projected costs, and prevent localized building power overloads using internal File I/O persistence.

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
High-density residential buildings often face the risk of localized power outages due to unmanaged, peak-hour energy consumption. **CEMS** provides a dual-interface Android solution that tracks power usage at the unit level. By aggregating this data locally on the device, the system acts as a software-level failsafe—intercepting data updates that exceed a building's maximum electrical capacity to prevent tripped breakers.

## ⚙️ Core Features
* **Role-Based Access Control:** Secure authentication routing users to either a localized Tenant UI or a macro-level Building Admin UI.
* **Unit CRUD Operations:** Full management of individual condo units treated as encapsulated objects in memory.
* **Real-Time Load Aggregation:** Iterates through active unit objects to calculate total building power draw against hardcoded grid limits.
* **Cost Estimation Engine:** Converts active unit kilowatt-hours (kWh) into projected financial costs based on current utility rates.
* **Active Overload Prevention:** Core system logic that blocks local file write operations if an update exceeds the maximum grid capacity, triggering an Android Toast/Dialog warning to force load balancing.

---

## 🏗️ System Architecture

The system utilizes strict Object-Oriented Programming principles and relies on memory management (via `ArrayLists`) mapped to local app storage for persistence, completely bypassing the need for a cloud backend or SQL database.

| CLASS | ATTRIBUTES | METHODS |
| :--- | :--- | :--- |
| **User** | `username`, `passwordHash`, `userID`, `roleType` | `register()`, `login()`, `logOut()`, `verifyCredentials()` |
| **Unit** | `unitID`, `floorLevel`, `kilowattHours` | `registerUnit()`, `updateUsage()`, `removeUnit()`, `getUsage()` |
| **BuildingGrid** | `unitList` (ArrayList), `totalLoad`, `maxCapacity`, `rate` | `displayDashboard()`, `calculateTotalLoad()`, `estimateCosts()`, `checkOverload()` |
| **FileHandler** | `internalFilePath` | `readData()`, `writeData()`, `updateRecord()` |

---

## 🗄️ Data Storage
This project utilizes **Android Internal Storage (Context.openFileOutput)** for offline state management, ensuring data is kept secure and local to the device.
* `credentials.txt` - Stores hashed user credentials and role definitions.
* `building_data.csv` - Stores serialized `Unit` object states (Unit ID, Floor, kWh).

---

## 📂 Project Structure

```text
CEMS-Android/
├── app/src/main/
│   ├── java/com/cems/app/
│   │   ├── activities/       # UI Controllers (Login, Dashboard)
│   │   ├── models/           # OOP Classes (User, Unit, BuildingGrid)
│   │   └── utils/            # FileHandler logic
│   ├── res/
│   │   ├── layout/           # XML UI designs
│   │   └── values/           # Colors, Strings, Themes
│   └── AndroidManifest.xml   # App configuration
└── README.md
