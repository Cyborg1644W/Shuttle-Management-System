# TXT Database File Formats

The backend stores each table in a UTF-8 `.txt` file using the existing CSV format. The first row contains the column names. Each subsequent line contains one record with comma-separated fields.

## workers.txt

Stores drivers and operators.

```text
employeeID,fullName,email,passwordHash,role,employmentStatus,shiftStatus,phone,licenseNumber,licenseExpirationDate,assignedShuttleId,assignedTerminal,activeShiftId,terminalZone,shiftTime,permissionsMask,managedShuttleIds
```

Required fields:

- `employeeID` - unique worker ID, such as `EMP-0001`
- `fullName` - worker full name
- `email` - valid and unique email address
- `passwordHash` - hashed password; never store a plain-text password
- `role` - `DRIVER` or `OPERATOR`
- `employmentStatus` - `ACTIVE`, `INACTIVE`, or `SUSPENDED`
- `shiftStatus` - `ON_SHIFT`, `ON_BREAK`, or `OFF_SHIFT`

Driver-only fields are `licenseNumber`, `licenseExpirationDate` (`yyyy-MM-dd`), `assignedShuttleId`, `assignedTerminal`, and `activeShiftId`.

Operator-only fields are `terminalZone`, `shiftTime`, `permissionsMask` (whole number), and `managedShuttleIds` (shuttle IDs separated by `;`).

Example:

```text
EMP-0001,Alex Reyes,alex.reyes@example.test,password-hash,DRIVER,ACTIVE,OFF_SHIFT,09170000001,D1234567,2028-12-31,SH-001,Main Terminal,,,,0,
```

## shuttles.txt

Stores shuttle vehicle information.

```text
shuttleId,plateNumber,capacity,currentStatus,vehicleModel
```

- `shuttleId` must be unique.
- `plateNumber` must be unique.
- Older rows keep their seating capacity. New units with a vehicle model may use `0` for an unspecified capacity.
- `vehicleModel` is optional for older rows and required by the current Add Fleet Unit form.
- `currentStatus` must be `AVAILABLE`, `IN_SERVICE`, `MAINTENANCE`, or `OFFLINE`.

Example:

```text
SH-001,ABC-1234,0,AVAILABLE,Toyota Coaster
```

## shifts.txt

Stores active, completed, and timed-out driver shifts.

```text
shiftId,driverId,shuttleId,startTime,endTime,status,distanceKm,stops,terminal,route
```

- `shiftId`, `driverId`, and `shuttleId` are required.
- `startTime` and `endTime` use epoch milliseconds.
- `endTime` must not be earlier than `startTime`.
- `status` must be `ACTIVE`, `COMPLETED`, or `TIMED_OUT`.
- An `ACTIVE` shift has an empty `endTime`.
- A `COMPLETED` or `TIMED_OUT` shift requires an `endTime`.
- `distanceKm` is a number and `stops` is a whole number.

Example:

```text
SHF-000001,EMP-0001,SH-001,1735689600000,1735722000000,COMPLETED,18.5,6,Main Terminal,Campus Loop
```

## schedule.txt

Stores weekly work schedules.

```text
employeeID,shift,days
```

- `employeeID` must match a worker ID.
- `shift` values are `0` for morning, `1` for afternoon, and `2` for night.
- `days` contains seven `0` or `1` characters, ordered Monday through Sunday.
- `1` means working and `0` means off.

Example:

```text
EMP-0001,0,1111100
```

This means `EMP-0001` works the morning shift from Monday through Friday.

## Import behavior

The backend validates every imported row. Invalid rows are reported as errors, existing workers and shuttles are skipped, and duplicate IDs, emails, or plate numbers are rejected. The server writes a complete updated CSV only after validation succeeds.

## Process: How the CSV Files Are Used

1. The backend starts and is given the location of its CSV files.
2. When the Android app sends an HTTP request, the Java server receives it.
3. The appropriate repository reads the needed data from its CSV file, such as `workers.txt` or `shuttles.txt`.
4. The server checks the data and sends a response back to the Android app.
5. If the request adds, edits, or removes data, the repository validates the change first.
6. `AtomicFileWriter` safely writes the complete updated CSV file. A temporary file is used first so the original data is less likely to be damaged if saving is interrupted.
7. The server then returns the result of the operation to the Android app.

In short: the Android app does not directly open these CSV files. It communicates with the Java server through HTTP, and the server manages the CSV data using its configured storage.

## Important note

Do not edit `.lock` files. The backend creates them automatically while it safely saves CSV data.

## Dated shift assignments

`dated-shifts.txt` is created when the first dated assignment is saved. It lives beside `schedule.txt` in local mode and joins the same private snapshot in R2 mode.

```csv
id,terminalId,employeeId,shuttleId,shiftDate,startTime,endTime,notesBase64,notifiedAt,readAt,removed
```

- `id` is a UUID. Repeating the same save with the same ID returns the saved assignment rather than creating a duplicate.
- `terminalId`, `employeeId`, and `shuttleId` refer to the terminal and its assigned driver and vehicle.
- Dates use `YYYY-MM-DD` and times use 24-hour `HH:mm`, interpreted in Philippine time. The end time must be later than the start time on the same date.
- `notesBase64` contains the UTF-8 notes encoded as Base64 so line breaks, commas, and quotes survive the CSV format. An empty field means the operator left no notes. Base64 does not encrypt the notes.

The Android client uses authenticated `GET /api/scheduled-shifts?from=YYYY-MM-DD&to=YYYY-MM-DD` to load a calendar period and operator-only `POST /api/scheduled-shifts` to save one assignment. Operators can read assignments for their terminal. Drivers can read their own assignments in their current terminal.

`schedule.txt` continues to hold existing weekly rules, and `shifts.txt` continues to hold actual trip history. The server checks dated assignments for worker and shuttle overlap, worker overlap with weekly rules, and ineligible vehicles.
