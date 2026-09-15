# Condominium Energy Management System (CEMS) 

## Overview
Energy consumption in condominium units continues to rise, yet most tenants and unit owners lack the tools to properly monitor and manage their electricity usage. This often results in energy waste, unnecessarily high electricity bills, and a lack of awareness about consumption habits. 

**CEMS** is a structured, software-based solution built in **Java** to track, manage, and optimize energy use. It provides a dual-interface system for both tenants (to prevent bill shock) and property managers (to prevent localized grid overloads).

## 💡 The Solution
Built purely on **Object-Oriented Programming (OOP)** principles, CEMS is designed to be modular, scalable, and adaptable. Instead of relying on a heavyweight backend database, the system demonstrates strong foundational computer science concepts by utilizing **Java File I/O (CSV)**, data structures (`ArrayList`), and memory management to simulate a localized smart grid.

## 🔑 Core Features

### Role-Based Access
* **Tenant Level:** Users can log in to view their specific unit object, track daily power usage, and project upcoming electricity costs based on current rates.
* **Admin Level:** Property managers have full access to macro-level building data, unit CRUD operations, and infrastructure load management.

### System Modules
* **User Authentication:** Secures the platform by verifying credentials (with hashed passwords) against a local text file, routing users to either the Tenant or Admin interface.
* **Unit Management:** An OOP-driven CRUD module where every condo unit is instantiated as an object in memory and serialized to a CSV file upon saving.
* **Energy Dashboard:** Iterates through the stored unit objects to aggregate the building's total power draw in real-time.
* **Cost Estimation:** Instantly calculates projected electricity bills based on user inputs and standard utility rates.
* **Grid Overload Warning:** A hardcoded safety limit logic that intercepts any user or admin update pushing the building over maximum electrical capacity. It blocks the file write and throws an error, requiring load balancing before data persistence is allowed.

## 🏗️ Technical Architecture & Class Structure

The system handles data persistence entirely through Java File I/O. When an update occurs, the specific object is modified in memory, and the CSV file is immediately overwritten to maintain state.

| CLASS | ATTRIBUTES | METHODS |
| :--- | :--- | :--- |
| **Admin/Tenant** | `username`, `passwordHash`, `userID`, `roleType` | `register()`, `login()`, `logOut()`, `verifyCredentials()` |
| **Unit** | `unitID`, `floorLevel`, `kilowattHours` | `registerUnit()`, `updateUsage()`, `removeUnit()`, `getUsage()` |
| **BuildingGrid** | `unitList` (ArrayList), `totalBuildingLoad`, `maxBuildingCapacity`, `ratePerKWh` | `displayDashboard()`, `calculateTotalLoad()`, `estimateCosts()`, `checkOverloadWarning()` |
| **FileHandler** | `csvFilePath` | `readData()`, `writeData()`, `updateRecord()` |

## 🌍 Impact
1. **For Tenants:** Eliminates bill shocks by providing full visibility over unit energy use, helping residents identify peak hours and adjust consumption habits.
2. **For Infrastructure:** Stops peak-hour overloads and tripped breakers. By intercepting updates that exceed capacity, the system acts as a software-level failsafe for the building's physical electrical panels.
