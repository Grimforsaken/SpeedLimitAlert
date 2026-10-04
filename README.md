# Speed Limit Alert (Android) — v0.1

Standalone Android speed warning app designed for a tablet connected to a Bluetooth OBD-II adapter.

## What v0.1 does

- Reads **vehicle speed from a classic Bluetooth ELM327-compatible OBD-II adapter** using PID `01 0D`.
- Uses the tablet's **GPS only to identify the road and direction of travel**.
- Uses a **processed offline OpenStreetMap road/speed database**; no map/navigation app is required while driving.
- Warns by voice when vehicle speed is **more than 5 mph above** the matched road limit (adjustable).
- Rearms after speed falls back to about 3 mph over the limit, preventing constant repeated warnings.
- Optional GPS-speed fallback if the OBD adapter disconnects.
- Imports `.osm.pbf` files and keeps only motor-road geometry, road names, direction and explicit numeric speed limits.
- Builds a compact `.rsdb` database from the PBF, then can automatically delete the large source PBF.
- Storage screen shows processed database size, retained PBF downloads and processing temp files.
- Storage screen can delete retained app downloads without deleting the processed database.
- A manually selected external PBF is remembered so the user can explicitly delete that original source after import.
- Automatic Geofabrik updates are constrained to **unmetered networking and verified Wi-Fi**. The default region is `oklahoma`.
- The currently working database is not replaced until a new database passes validation.

## First use

1. Pair the OBD-II adapter in Android Bluetooth settings.
2. Open the app and grant Location, Bluetooth and Notification permissions.
3. Open **Road Data & Storage**.
4. Either import an `.osm.pbf` file or set the Geofabrik state slug and choose **Check for update on Wi-Fi**.
5. Wait for processing to reach 100%.
6. Return to the main screen, select the paired OBD adapter and press **Start Monitoring**.

The automatic Oklahoma source is:
`https://download.geofabrik.de/north-america/us/oklahoma-latest.osm.pbf`

The large preprocessed Oklahoma `.rsdb` test database is not stored in GitHub. The app can download the current PBF over Wi-Fi, extract the needed road/speed-limit information on-device, validate the compact database, and delete the source PBF automatically.

## Data handling

The full PBF is processed in three stages. First the app selects motor roads carrying explicit numeric `maxspeed` information and records only the node IDs those roads need. A second pass extracts only those node coordinates. A third stage packs road segments into a custom spatial database. Temporary processing files are removed after a successful database swap.

The final segment record stores coordinates as compact E7 integers plus forward/backward mph limits, road-name ID, one-way direction and road-class rank. It does **not** retain buildings, businesses, addresses, waterways, land use or unrelated OSM features.

## Important v0.1 limits

- Bluetooth support in v0.1 is **Classic Bluetooth SPP / ELM327-style**. Some newer OBD adapters are BLE-only and use different services; those will need a BLE driver added for the specific adapter.
- Only explicit numeric static speed-limit tags are used. Roads with conditional limits are deliberately skipped rather than guessing. Temporary construction limits, active school-zone limits and variable electronic signs may therefore be unavailable.
- OpenStreetMap coverage is not complete on every road. When the app cannot confidently match a static limit, it shows `--` and does not issue a speed-limit warning.
- Posted road signs and applicable law remain authoritative.

Road data attribution: **© OpenStreetMap contributors**. State PBF extracts are downloaded from Geofabrik.

## Building the APK

The project uses Android Gradle Plugin 8.7.3, Java 17, compile SDK 35, and has no runtime third-party libraries.

A GitHub Actions workflow is included at `.github/workflows/android.yml`. On GitHub it installs Android SDK 35 and Gradle 8.9 and produces `app-debug.apk` as the `SpeedLimitAlert-debug-apk` workflow artifact.
