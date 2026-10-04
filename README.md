# Speed Limit Alert

Standalone Android speed warning app for a tablet connected to a Classic Bluetooth ELM327-style OBD-II adapter.

It reads vehicle speed from OBD-II PID `01 0D`, uses GPS only to identify the road/direction, looks up the posted static speed limit from a processed offline OpenStreetMap database, and gives a spoken warning when the vehicle is more than 5 mph over the matched limit. The warning offset is adjustable.

## Offline road data and storage
The app downloads a current U.S. state `.osm.pbf` from Geofabrik over Wi-Fi and processes it on the tablet. It keeps only road geometry for ways with explicit numeric speed-limit tags. Buildings, businesses, addresses, waterways, land use, POIs and unrelated map data are not retained.

Processing is three-pass: select speed-limited ways and their needed node IDs; extract coordinates only for those nodes; build a compact indexed road-segment database. The old working database stays active until the new one validates.

**Road Data & Storage** lists every retained PBF with an individual Delete button and a Delete All button. **Delete large PBF after successful processing** is on by default so automatic updates do not clutter the tablet. Manually imported external PBF files are remembered separately so Android can delete the original when requested.

Automatic updates use Android JobScheduler with an unmetered-network constraint and verify that the active network is Wi-Fi. The default Geofabrik region is `oklahoma`; change the slug in the storage screen for another U.S. state, such as `kansas`, `texas`, or `new-mexico`.

## First use
1. Pair the ELM327-compatible OBD adapter in Android Bluetooth settings.
2. Open the app and grant Location, Bluetooth and Notification permissions.
3. Open **Road Data & Storage**, choose the state/region slug, and tap **Check / Download Update on Wi-Fi**.
4. Wait for processing to finish.
5. Return to the main screen, choose the paired OBD adapter, and tap **Start Monitoring**.

GPS-speed fallback is optional. Road matching and limit lookup remain offline while driving.

Only explicit numeric static OSM limits are used. Conditional/variable limits are skipped instead of guessed. OSM coverage is incomplete on some roads; if no reliable limit is available the app shows `--` and does not warn. Posted signs and applicable law remain authoritative.

Bluetooth support targets Classic Bluetooth SPP / ELM327-style adapters. BLE-only adapters need a device-specific BLE driver.

Road data attribution: © OpenStreetMap contributors. Extracts: Geofabrik.

## Build
GitHub Actions builds a debug APK on every push. Open **Actions**, choose the newest **Build Android APK** run, and download the `SpeedLimitAlert-debug-apk` artifact.


Diagnostic build validation.
