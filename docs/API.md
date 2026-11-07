# Shuttle server API

Base URL: `http://<server>:8080/api` (emulator: `http://10.0.2.2:8080/api`). JSON in, JSON out.
Errors are always `{"error": "message"}` with a status: 400 bad input, 401 not signed in, 403 not allowed,
404 not found, 409 conflict (e.g. "You already have a shift running").

Everything except `health`, `auth/login` and `invite/register` needs `Authorization: Bearer <token>`.
Roles: `DRIVER`, `OPERATOR`. Shift statuses: `ON_SHIFT`, `ON_BREAK`, `OFF_SHIFT`.
Vehicle statuses: `AVAILABLE`, `IN_SERVICE` (set automatically during a shift), `MAINTENANCE`, `OFFLINE`.

## Signing in and registering
| Call | Who | Body -> Result |
|---|---|---|
| `POST /auth/login` | anyone | `{email, password}` -> `{token, user}` |
| `POST /auth/logout` | signed in | ends the token |
| `GET /auth/me` | signed in | `{user}` |
| `POST /invite/generate` | operator | `{role: "DRIVER"\|"OPERATOR"}` -> `{invite_code}` |
| `POST /invite/register` | anyone with a code | `{inviteCode, name, email, phone, password, licenseNumber?}` -> `{employeeId, role}` |

The first operator: start the server with `--bootstrap-admin you@example.com`, it prints an invite code.

## Workers (operator unless noted)
| Call | Body -> Result |
|---|---|
| `GET /workers` | `{workers: [...]}` |
| `POST /workers` | `{fullName, phone, role \| operator, employeeId?}` -> `{worker, inviteCode}` (give the code to the person so they can register and claim this record) |
| `GET /workers/{id}` | operator or yourself |
| `PUT /workers/{id}` | operator or yourself: `{fullName, phone, email}`; operator also `employeeId` (shifts, schedule and login follow the new ID) |
| `PUT /workers/{id}/status` | operator or yourself: `{status}` (drivers: only while a shift is running) |
| `PUT /workers/{id}/assign` | driver: `{shuttleId, terminal}`; operator: `{zone, shiftTime}` (`value1`/`value2` also accepted) |
| `POST /workers/{id}/invite` | new invite for someone who hasn't registered yet |
| `DELETE /workers/{id}` | removes the worker and their login |

A worker looks like: `{employeeId, fullName, email, phone, phoneDisplay, role, employmentStatus, shiftStatus, pending, shuttleId, terminal, activeShiftId, zone, shiftTime, value1, value2}`
(`pending: true` = added by an operator but not registered yet).

## Shifts
| Call | Who | Result |
|---|---|---|
| `POST /shift/start` | driver | starts a shift on the vehicle the operator assigned |
| `POST /shift/end` | driver | `{distanceKm, stops, route}` (route = `"lat,lng;lat,lng"`) |
| `GET /shift/active` | signed in | `{active, shift?}` |
| `GET /shift` | signed in | `{shifts: [...]}` newest first (drivers: own; operators: all, or `?driverId=`) |
| `GET /shift/{shiftId}` | owner or operator | one shift including `route` |

A shift: `{shiftId, driverId, driverName, shuttleId, terminal, startTime, endTime, status, distanceKm, stops, durationMs, avgSpeedKmh, route}`.

## Vehicles and live tracking
| Call | Who | Notes |
|---|---|---|
| `GET /shuttles` | signed in | `{shuttles: [{shuttleId, plateNumber, capacity, status, driverId, driverName, position}]}` |
| `POST /shuttles` | operator | `{shuttleId, plateNumber, capacity}` |
| `GET /shuttles/{id}` | signed in | |
| `PUT /shuttles/{id}/status` | operator | `{status: AVAILABLE\|MAINTENANCE\|OFFLINE}` |
| `DELETE /shuttles/{id}` | operator | |
| `POST /shuttles/{id}/location` | driver (own vehicle) / operator | `{latitude, longitude, speed (m/s), timestamp (phone time, ms)}` |
| `POST /location` | driver | same body; uses the driver's assigned vehicle |
| `GET /live` | signed in | vehicles out on a shift with their last position (`?all=true` for every reported position) |

Positions use the phone's `timestamp`, so points sent late from the offline queue are accepted without
being mistaken for teleporting; points older than the latest one are ignored. Jumps faster than 160 km/h are rejected (400).

## Dashboard and schedule
| Call | Who | Result |
|---|---|---|
| `GET /dashboard` | operator | `{totalShifts, activeShifts, totalUnits, activeUnits, inactiveUnits, maintenanceUnits, staff{...}}` |
| `GET /schedule` | signed in | `{entries: [{employeeId, name, role, place, shift, days}]}` shift 0/1/2 = morning/afternoon/night, days = 7 chars Monday first |
| `PUT /schedule` | operator | `{entries: [{employeeId, shift, days}]}` replaces the whole schedule |
