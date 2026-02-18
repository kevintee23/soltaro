import groovy.json.JsonSlurper
import java.net.URLEncoder

definition(
    name: "Soltaro Qendercore Bridge",
    namespace: "jarvis",
    author: "Jarvis",
    description: "Logs into Qendercore cloud, selects inverter, and updates a child driver with battery/solar/grid telemetry.",
    category: "Convenience",
    singleInstance: true,
    importUrl: "",
    iconUrl: "https://raw.githubusercontent.com/HubitatCommunity/HubitatPublic/master/resources/icons/app-Category2x.png",
    iconX2Url: "https://raw.githubusercontent.com/HubitatCommunity/HubitatPublic/master/resources/icons/app-Category2x.png"
)

preferences {
    page(name: "mainPage")
    page(name: "inverterPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Soltaro Qendercore Bridge", install: true, uninstall: true) {
        section("Qendercore Login") {
            input "qcUsername", "text", title: "Username (email)", required: true
            input "qcPassword", "password", title: "Password", required: true
        }

        section("Polling") {
            input "pollMinutes", "enum", title: "Poll every", required: true, defaultValue: "5", options: ["1":"1 minute", "2":"2 minutes", "5":"5 minutes", "10":"10 minutes", "15":"15 minutes"]
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
        }

        section("Inverter") {
            if (settings.inverterHwid) {
                paragraph "Selected inverter HWID: ${settings.inverterHwid}"
            } else {
                paragraph "No inverter selected yet."
            }
            href(name: "pickInverter", title: "Select inverter", page: "inverterPage", description: "Tap to discover and select")
        }

        section("Device") {
            paragraph "A child device named 'Soltaro' will be created/updated using driver: Soltaro Qendercore Bridge"
        }
    }
}

def inverterPage() {
    def options = [:]
    def token = getAccessTokenSafe()

    if (token) {
        def tree = qcGetDevicetree(token)
        (tree?.tree ?: []).each { n ->
            options[n.key as String] = "${n.title} (${n.key})"
        }
    }

    dynamicPage(name: "inverterPage", title: "Select Inverter", install: false, uninstall: false) {
        section("Available Inverters") {
            if (options) {
                input "inverterHwid", "enum", title: "Inverter", required: true, multiple: false, options: options
            } else {
                paragraph "Could not discover inverters. Check credentials, then reopen this page."
            }
        }
    }
}

def installed() { initialize() }
def updated() { unschedule(); initialize() }

def initialize() {
    ensureChildDevice()
    schedulePolling()
    runIn(3, "pollNow")
}

def schedulePolling() {
    Integer mins = (settings.pollMinutes ?: "5") as Integer
    switch (mins) {
        case 1: runEvery1Minute("pollNow"); break
        case 2: runEvery2Minutes("pollNow"); break
        case 5: runEvery5Minutes("pollNow"); break
        case 10: runEvery10Minutes("pollNow"); break
        case 15: runEvery15Minutes("pollNow"); break
        default: runEvery5Minutes("pollNow")
    }
}

def pollNow() {
    if (!settings.qcUsername || !settings.qcPassword || !settings.inverterHwid) {
        if (settings.logEnable) log.warn "Missing login or inverter settings; skipping poll"
        return
    }

    try {
        String token = getAccessTokenSafe()
        if (!token) throw new Exception("Failed to get access token")

        String enchwt = getEnchwt(token, settings.inverterHwid as String)
        if (!enchwt) throw new Exception("No enchwt returned for selected inverter")

        def payload = qcPostDs(token, [
            _ft: "hwm",
            hwid: settings.inverterHwid,
            enchwt: enchwt,
            props: [
                "inv.core.solar_prod_pwr_w",
                "inv.core.consumption_pwr_w",
                "inv.core.meter_pwr_w",
                "inv.core.battery_pwr_w",
                "inv.core.batt_soc_perc"
            ],
            duration: "PT15M",
            resolution: "last",
            tz: "local"
        ])

        def data = colsRowsToMap(payload)
        updateChild(data)
    } catch (e) {
        log.error "Soltaro poll failed: ${e}"
    }
}

def ensureChildDevice() {
    String dni = "soltaro-qendercore-main"
    def child = getChildDevice(dni)
    if (!child) {
        child = addChildDevice("jarvis", "Soltaro Qendercore Bridge", dni, [name: "Soltaro", label: "Soltaro", isComponent: false])
    }
    return child
}

def updateChild(Map data) {
    def child = ensureChildDevice()
    if (!child) return

    def batteryW = toDecimal(data["inv.core.battery_pwr_w"])
    def state = batteryW > 0 ? "charging" : (batteryW < 0 ? "discharging" : "idle")

    child.setBatterySoc(toDecimal(data["inv.core.batt_soc_perc"]))
    child.setBatteryPower(batteryW)
    child.setBatteryState(state)
    child.setSolarPower(toDecimal(data["inv.core.solar_prod_pwr_w"]))
    child.setConsumptionPower(toDecimal(data["inv.core.consumption_pwr_w"]))
    child.setGridPower(toDecimal(data["inv.core.meter_pwr_w"]))
    child.setLastTimestamp((data["ts"] ?: new Date().format("yyyy-MM-dd HH:mm:ss", location?.timeZone ?: TimeZone.getTimeZone("Australia/Melbourne"))) as String)
}

private BigDecimal toDecimal(v) {
    try { return (v as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP) }
    catch (ignored) { return 0.0 }
}

private Map colsRowsToMap(Map payload) {
    def cols = payload?.cols ?: []
    def rows = payload?.rows ?: []
    if (!cols || !rows) return [:]
    def row = rows[0]
    Map out = [:]
    cols.eachWithIndex { c, i -> out[(c.id ?: "col${i}").toString()] = i < row.size() ? row[i] : null }
    return out
}

private String getAccessTokenSafe() {
    String token = qcRefresh()
    if (!token) token = qcLogin()
    return token
}

private String qcLogin() {
    // Hubitat HTTP client cannot encode multipart/form-data bodies directly.
    // Qendercore login accepts URL-encoded form payload as well.
    String formBody = "username=${URLEncoder.encode(settings.qcUsername as String, 'UTF-8')}&password=${URLEncoder.encode(settings.qcPassword as String, 'UTF-8')}"

    def params = [
        uri: "https://auth.qendercore.com:8000/v1/auth/login",
        requestContentType: "application/x-www-form-urlencoded",
        contentType: "application/json",
        headers: qcHeaders([accept: "application/json", "Content-Type": "application/x-www-form-urlencoded"]),
        body: formBody
    ]

    String token = null
    httpPost(params) { resp ->
        token = resp?.data?.access_token
        saveRefreshTokenFromHeaders(resp?.headers)
    }
    return token
}

private String qcRefresh() {
    String rtok = state.refreshToken
    if (!rtok) return null

    String token = null
    def params = [
        uri: "https://auth.qendercore.com:8000/v1/auth/tokens",
        headers: qcHeaders([accept: "application/json", Cookie: "rtok=${rtok}"])
    ]

    try {
        httpGet(params) { resp ->
            token = resp?.data?.access_token
            saveRefreshTokenFromHeaders(resp?.headers)
        }
    } catch (ignored) {
        return null
    }
    return token
}

private Map qcGetDevicetree(String token) {
    def out = null
    def params = [
        uri: "https://api.qendercore.com:8000/v1/h/devicetree",
        query: [hf: "soltinv"],
        headers: qcHeaders([accept: "application/json", authorization: "bearer ${token}"])
    ]

    httpGet(params) { resp -> out = resp?.data }
    return out
}

private String getEnchwt(String token, String hwid) {
    def payload = qcPostDs(token, [_ft: "hwv", hwid: hwid, f: ["enchwt"]])
    def row = payload?.rows ? payload.rows[0] : null
    return row ? row[0] as String : null
}

private Map qcPostDs(String token, Map body) {
    def out = null
    def params = [
        uri: "https://api.qendercore.com:8000/v1/h/ds",
        headers: qcHeaders([accept: "application/json", authorization: "bearer ${token}"]),
        requestContentType: "application/json",
        contentType: "application/json",
        body: body
    ]

    httpPost(params) { resp -> out = resp?.data }
    return out ?: [:]
}

private Map qcHeaders(Map extra = [:]) {
    return [
        "x-qc-client-seq": "A.2.3",
        "User-Agent": "Qendercore/98 CFNetwork/3860.300.31 Darwin/25.2.0"
    ] + extra
}

private void saveRefreshTokenFromHeaders(headers) {
    try {
        def all = headers?.findAll { it?.name?.equalsIgnoreCase("set-cookie") }?.collect { it.value } ?: []
        String rt = null
        all.each { line ->
            def m = (line =~ /rtok=([^;]+)/)
            if (m.find()) rt = m.group(1)
        }
        if (rt) state.refreshToken = rt
    } catch (ignored) {
        // no-op
    }
}
