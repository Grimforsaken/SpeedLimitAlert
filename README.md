# Car Dashboard rebuild

Clean-room Android rebuild focused on the user's vehicle-dashboard workflow.

## Vehicle connection

The app supports both Bluetooth Classic ELM327-style adapters and BLE ELM327-style adapters. The **Select / Scan OBD** screen scans both transports instead of requiring the adapter to already be paired. Standard live widgets include vehicle speed, RPM, coolant temperature, voltage, engine load, throttle, fuel level, intake temperature, MAP, MAF, timing advance, and engine runtime.

## Custom widgets

Open **Customize Widgets** to choose dashboard tiles. **Speed Limit** is a normal custom widget, not a floating overlay. It is supplied by the offline road matcher instead of OBD-II.

## Offline speed-limit data

The offline road-data system is retained from Speed Limit Alert:

- import a state `.osm.pbf` file manually or download a current Geofabrik U.S. state extract on Wi-Fi
- extract only speed-limited drivable road geometry needed by the app
- build a compact SQLite road database
- use GPS position/bearing to match the current road and direction
- support `maxspeed`, `maxspeed:forward`, `maxspeed:backward`, and one-way direction
- show unknown rather than guessing when no trustworthy speed limit exists
- automatically update on Wi-Fi when enabled
- automatically delete the large source PBF after successful extraction when enabled
- retained PBF files have an **EXTRACT** button and separate delete controls

## Speed warning

The optional audio warning uses OBD vehicle speed first and GPS speed only when the user enables fallback. The default trigger is more than 5 mph over the matched posted speed limit, with hysteresis before re-arming.

## Recovery reference

The uploaded Car Scanner recovery was used only as a behavioral/platform reference. It showed that the working app supported both Bluetooth/BLE-related permissions and used a BLE stack. This project does not contain or redistribute the recovered proprietary program code or assets.
