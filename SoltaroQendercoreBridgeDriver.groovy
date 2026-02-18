metadata {
    definition(name: "Soltaro Qendercore Bridge", namespace: "jarvis", author: "Jarvis") {
        capability "Sensor"
        capability "Battery"
        capability "PowerMeter"
        capability "Refresh"

        attribute "batterySoc", "number"
        attribute "batteryPower", "number"
        attribute "batteryState", "string"
        attribute "solarPower", "number"
        attribute "consumptionPower", "number"
        attribute "gridPower", "number"
        attribute "lastSample", "string"

        command "setBatterySoc", [[name: "SOC", type: "NUMBER", description: "Battery state of charge %"]]
        command "setBatteryPower", [[name: "W", type: "NUMBER", description: "Battery power in watts"]]
        command "setBatteryState", [[name: "state", type: "STRING", description: "charging/discharging/idle"]]
        command "setSolarPower", [[name: "W", type: "NUMBER", description: "Solar power in watts"]]
        command "setConsumptionPower", [[name: "W", type: "NUMBER", description: "Consumption power in watts"]]
        command "setGridPower", [[name: "W", type: "NUMBER", description: "Grid power in watts (+import, -export)"]]
        command "setLastTimestamp", [[name: "ts", type: "STRING", description: "Sample timestamp"]]
        command "setSnapshot", [[name: "soc", type: "NUMBER"], [name: "batteryW", type: "NUMBER"], [name: "solarW", type: "NUMBER"], [name: "consumptionW", type: "NUMBER"], [name: "gridW", type: "NUMBER"], [name: "timestamp", type: "STRING"]]
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
    if (logEnable) log.debug "Soltaro Qendercore Bridge initialized"
}

def refresh() {
    if (logEnable) log.debug "refresh() called (updates come from Soltaro Qendercore Bridge app)"
}

def setBatterySoc(value) {
    Integer soc = safeInt(value)
    sendEvent(name: "batterySoc", value: soc, unit: "%")
    sendEvent(name: "battery", value: soc, unit: "%")
}

def setBatteryPower(value) {
    BigDecimal watts = safeDecimal(value)
    sendEvent(name: "batteryPower", value: watts, unit: "W")
    sendEvent(name: "power", value: watts, unit: "W")

    String state = "idle"
    if (watts > 0) state = "charging"
    if (watts < 0) state = "discharging"
    sendEvent(name: "batteryState", value: state)
}

def setBatteryState(String value) { sendEvent(name: "batteryState", value: value ?: "unknown") }
def setSolarPower(value) { sendEvent(name: "solarPower", value: safeDecimal(value), unit: "W") }
def setConsumptionPower(value) { sendEvent(name: "consumptionPower", value: safeDecimal(value), unit: "W") }
def setGridPower(value) { sendEvent(name: "gridPower", value: safeDecimal(value), unit: "W") }
def setLastTimestamp(String ts) { sendEvent(name: "lastSample", value: ts ?: "") }

def setSnapshot(soc, batteryW, solarW, consumptionW, gridW, String timestamp) {
    setBatterySoc(soc)
    setBatteryPower(batteryW)
    setSolarPower(solarW)
    setConsumptionPower(consumptionW)
    setGridPower(gridW)
    setLastTimestamp(timestamp)
}

private Integer safeInt(val) {
    try { return (val as BigDecimal).setScale(0, BigDecimal.ROUND_HALF_UP).intValue() }
    catch (Exception ignored) { return 0 }
}

private BigDecimal safeDecimal(val) {
    try { return (val as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP) }
    catch (Exception ignored) { return 0.0 }
}
