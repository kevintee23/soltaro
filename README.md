# Soltaro Qendercore Bridge for Hubitat

Community Hubitat integration for Soltaro/Qendercore systems.

## What this includes

- **App:** `SoltaroQendercoreBridgeApp.groovy`
  - Handles login to Qendercore cloud
  - Discovers/selects inverter
  - Refresh token handling
  - Scheduled polling (1/2/5/10/15 minutes)
  - Updates child device

- **Driver:** `SoltaroQendercoreBridgeDriver.groovy`
  - Displays battery/solar/grid states in Hubitat

## Exposed data points

- Battery SOC (%)
- Battery Power (W)
- Battery State (`charging` / `discharging` / `idle`)
- Solar Power (W)
- Consumption Power (W)
- Grid Power (W) (`+` import / `-` export)
- Last Sample timestamp

## Installation (Hubitat-only, recommended)

1. **Drivers Code** → **New Driver** → paste `SoltaroQendercoreBridgeDriver.groovy` → **Save**
2. **Apps Code** → **New App** → paste `SoltaroQendercoreBridgeApp.groovy` → **Save**
3. **Apps** → **Add User App** → choose **Soltaro Qendercore Bridge**
4. Enter Qendercore username/password
5. Choose poll interval
6. Tap **Select inverter** and pick active inverter
7. Save app

A child device named **Soltaro** will be created automatically.

## Optional: Raspberry Pi bridge (alternative)

If you prefer to run the cloud polling off-hub (or forward data elsewhere), use the Pi bridge.

Files:
- `pi/soltaro_sync.py`
- `pi/soltaro.env.example`
- `pi/systemd/soltaro-qendercore.service`

High-level steps:
1. On your Pi, create a folder:
   - `mkdir -p ~/soltaro-qendercore`
2. Copy `pi/soltaro_sync.py` to: `~/soltaro-qendercore/soltaro_sync.py` and make it executable:
   - `chmod +x ~/soltaro-qendercore/soltaro_sync.py`
3. Create `~/soltaro-qendercore/soltaro.env` from `pi/soltaro.env.example`.
   - Fill in `SOLTARO_USERNAME` + `SOLTARO_PASSWORD`
   - Then discover your inverter HWID:
     - `python3 ~/soltaro-qendercore/soltaro_sync.py list-inverters --env ~/soltaro-qendercore/soltaro.env`
   - Copy the HWID into `SOLTARO_HWID=...`
   - Fill in Maker API (`HUBITAT_MAKER_BASE_URL`, `HUBITAT_MAKER_TOKEN`, `HUBITAT_DEVICE_ID`)
   - Then lock it down: `chmod 600 ~/soltaro-qendercore/soltaro.env`
4. Install the Hubitat **Driver** in this repo and create a **Virtual Device** using it.
5. Add that virtual device to Hubitat **Maker API** and set `HUBITAT_DEVICE_ID` in the env file.
6. Install the systemd unit from `pi/systemd/soltaro-qendercore.service`:
   - copy to `/etc/systemd/system/soltaro-qendercore.service`
   - run `sudo systemctl daemon-reload`
   - run `sudo systemctl enable --now soltaro-qendercore.service`

## HomeKit/Homebridge note

Apple Home typically exposes standard characteristics only (e.g. battery %). For richer automations, create helper virtual switches/sensors in Hubitat based on the Soltaro values and expose those via Homebridge.

## Security notes

- Credentials are stored in Hubitat app settings (not hardcoded in code).
- Refresh token is maintained in app `state`.

## Disclaimer

Unofficial community integration. Use at your own risk.
