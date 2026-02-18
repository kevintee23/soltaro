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

## Installation (Hubitat)

1. **Drivers Code** → **New Driver** → paste `SoltaroQendercoreBridgeDriver.groovy` → **Save**
2. **Apps Code** → **New App** → paste `SoltaroQendercoreBridgeApp.groovy` → **Save**
3. **Apps** → **Add User App** → choose **Soltaro Qendercore Bridge**
4. Enter Qendercore username/password
5. Choose poll interval
6. Tap **Select inverter** and pick active inverter
7. Save app

A child device named **Soltaro** will be created automatically.

## HomeKit/Homebridge note

Apple Home typically exposes standard characteristics only (e.g. battery %). For richer automations, create helper virtual switches/sensors in Hubitat based on the Soltaro values and expose those via Homebridge.

## Security notes

- Credentials are stored in Hubitat app settings (not hardcoded in code).
- Refresh token is maintained in app `state`.

## Disclaimer

Unofficial community integration. Use at your own risk.
