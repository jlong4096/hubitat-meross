/**
 * Meross Garage Door Manager (Hubitat App)
 *
 * Maintained by James Long
 * https://github.com/jlong4096/hubitat-meross
 *
 * Originally written by Art Ardolino (ajardolino3).
 */

import groovy.json.*
import java.security.MessageDigest

def appVersion() { return "0.2.0" }

definition(
	name: "Meross Garage Door Manager",
	namespace: "jlong4096",
	author: "James Long",
	description: "Manages the addition and removal of Meross Garage Door Devices",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	singleInstance: true,
    importUrl: "https://raw.githubusercontent.com/jlong4096/hubitat-meross/main/apps/meross_app.groovy"
)

preferences() {
    page name: "mainPage"
    page name: "addGarageDoorStep1"
    page name: "addGarageDoorStep2"
    page name: "addGarageDoorStep3"
    page name: "addGarageDoorStep4"
    page name: "listGarageDoorPage"
}

def mainPage() {
    return dynamicPage(name: "mainPage", title: "Meross Garage Door Manager", uninstall: true, install: true) {
        section(){
            paragraph("The Meross Garage Door Manager assists with the configuration of Meross Garage Door Opener devices.")
			href "addGarageDoorStep1", title: "<b>Add New Garage Doors</b>", description: "Adds new garage door devices."
			href "listGarageDoorPage", title: "<b>List Garage Doors</b>", description: "Lists added garage door devices."
			input "debugLog", "bool", title: "Enable debug logging", submitOnChange: true, defaultValue: false
        }
    }
}

def listGarageDoorPage() {
    def devices = getChildDevices()
    def message = ""
    devices.each{ device ->
        message += "\t${device.label} (ID: ${device.getDeviceNetworkId()})\n"
    }
    return dynamicPage(name: "listGarageDoorPage", title: "List Garage Doors", install: false, nextPage: mainPage) {
        section() {
            paragraph "The following devices were added using the 'Add New Garage Doors' feature."
            paragraph message
        }
    }
}

def addGarageDoorStep1() {
    return dynamicPage(name: "addGarageDoorStep1", title: "Add New Garage Doors (Step 1)", install: false, nextPage: addGarageDoorStep2) {
        section(){
            input "merossUsername", "string", required: true, title: "Meross email address"
            input "merossPassword", "password", required: true, title: "Meross password"
            input "merossIP", "string", required: true, title: "IP address of your Meross device"
            input "merossDomain", "string", required: true, title: "Domain for Meross API. iotx-ap.meross.com (Asia/Pacific region), iotx-eu.meross.com (Europe), iotx-us.meross.com (US)", defaultValue: "iotx-us.meross.com"
        }
    }
}

def addGarageDoorStep2() {
    def response = loginMeross(merossUsername, merossPassword, merossDomain)

    if (response.code != 200) {
        return dynamicPage(name: "addGarageDoorStep2", title: "Login Failed", install: false, nextPage: mainPage) {
            section(){
                paragraph(response.error ? response.error.toString() : "Login failed. Please check your credentials and API domain.")
            }
        }
    }

    def data = getMerossData(response.token, merossDomain)

    // getMerossData returns a List of devices on success, or an error Map on failure.
    if (!(data instanceof List)) {
        return dynamicPage(name: "addGarageDoorStep2", title: "Unable to Load Devices", install: false, nextPage: mainPage) {
            section(){
                paragraph("Login succeeded, but the device list could not be retrieved.")
                paragraph(data?.error ? data.error.toString() : "Unknown error contacting the Meross API.")
            }
        }
    }

    state.data = data
    state.merossKey = response.key

    def devices = [:]
    state.data.each{ device ->
        devices["${device.uuid}"] = device.devName
    }

    return dynamicPage(name: "addGarageDoorStep2", title: "Add New Garage Doors (Step 2)", install: false, nextPage: addGarageDoorStep3) {
        section(){
            input ("selectedDevice", "enum",
               required: true,
               multiple: false,
               title: "Select a device to add (${devices.size() ?: 0} devices detected)",
               description: "Use the dropdown to select a device.",
               options: devices)
        }
    }
}

def addGarageDoorStep3() {
    def doors = [:]
    def device = state.data?.find { it.uuid == selectedDevice }

    if (device) {
        // Offer only doors that have not already been added as child devices.
        getDoorsForDevice(device).each { idx, name ->
            def dni = "${selectedDevice}:${idx}"
            if (!getChildDevice(dni)) {
                doors[idx] = name
            }
        }
        logDebug("Found device: ${device.devName} (${device.deviceType})")
    }

    return dynamicPage(name: "addGarageDoorStep3", title: "Add New Garage Doors (Step 3)", install: false, nextPage: addGarageDoorStep4) {
        section() {
            input ("selectedDoors", "enum",
                    required: true,
                    multiple: true,
                    title: "Select one or more garage doors to add (${doors.size() ?: 0} new doors detected)",
                    description: "Use the dropdown to select the door(s).",
                    options: doors)
        }
    }
}

def addGarageDoorStep4() {
    def device = state.data?.find { it.uuid == selectedDevice }
    def doors = device ? getDoorsForDevice(device) : [:]

    def message = ""
    selectedDoors?.each{ door_index ->
        def doorName = doors[door_index]
        def dni = "${selectedDevice}:${door_index}"
        if (getChildDevice(dni)) {
            message += "Door already exists (" + doorName + ").<br/>"
            return
        }
        try {
            def child = addChildDevice("jlong4096", "Meross Smart WiFi Garage Door Opener", dni, ["label": doorName])
            child.updateSetting("deviceIp", merossIP)
            child.updateSetting("channel", Integer.parseInt(door_index))
            child.updateSetting("uuid", selectedDevice)
            child.updateSetting("key", state.merossKey)
            child.updateSetting("messageId", "N/A")
            child.updateSetting("sign", "N/A")
            child.updateSetting("timestamp", 0)
            message += "New door added successfully (" + doorName + ").<br/>"
        }
        catch(exception) {
            message += "Unable to add door: " + exception + "<br/>"
        }
    }

    // Clean up transient/sensitive values now that setup is complete.
	app?.removeSetting("selectedDevice")
	app?.removeSetting("selectedDoors")
    app?.removeSetting("merossPassword")
    state.remove("merossKey")
    state.remove("data")

	return dynamicPage(name:"addGarageDoorStep4",
					   title: "Add Garage Door Status",
					   nextPage: mainPage,
					   install: false) {
	 	section() {
            paragraph message
		}
	}
}

/**
 * Returns a map of [channelIndex(String) : doorName] for a device.
 *   - MSG200 (multi-door): channel 0 is the aggregate/master; doors are 1..n.
 *   - MSG100 (single-door): the door operates on channel 0.
 * Both step 3 (options) and step 4 (add) use this so they cannot disagree.
 */
def getDoorsForDevice(device) {
    def doors = [:]
    if (device?.deviceType == "msg200") {
        for (int i = 1; i < device.channels.size(); i++) {
            doors["${i}"] = device.channels[i]?.devName ?: "Door ${i}"
        }
    } else if (device?.deviceType == "msg100") {
        doors["0"] = device.channels?.getAt(0)?.devName ?: device.devName
    } else {
        logDebug("Unsupported device type: " + device?.deviceType)
    }
    return doors
}

def installed() {
    // called when app is installed
}

def updated() {
    // called when settings are updated
}

def uninstalled() {
    // called when app is uninstalled
}

def generator(alphabet, n) {
  return new Random().with {
    (1..n).collect { alphabet[ nextInt( alphabet.length() ) ] }.join()
  }
}

def getMerossData(token, domain) {
    def nonce = generator( (('A'..'Z')+('0'..'9')).join(), 16 )
    def unix_time = (Integer)Math.floor(new Date().getTime() / 1000);

    def encoded_param = "e30=";   // base64 of "{}" -> empty params for devList

    def concat_sign = ["23x17ahWarFH6w29", unix_time, nonce, encoded_param].join("")
    MessageDigest digest = MessageDigest.getInstance('MD5')
    digest.update(concat_sign.bytes, 0, concat_sign.length())
    def sign = new BigInteger(1, digest.digest()).toString(16)

    def data = [:]
    data.params = encoded_param
    data.sign = sign
    data.timestamp = unix_time
    data.nonce = nonce

    def commandParams = [
		uri: "https://${domain}/v1/Device/devList",
		contentType: "application/json",
		requestContentType: 'application/json',
        headers: ['Authorization':'Basic ' + token],
		body : data
	]
	def respData
	try {
		httpPostJson(commandParams) {resp ->
            if (resp.status == 200) {
                respData = resp.data["data"]
                logDebug("meross devList returned ${respData?.size() ?: 0} device(s)")
			} else {
				respData = [code: resp.status, error: "HTTP Protocol Error"]
			}
		}
	} catch (e) {
		respData = [code: 9999, error: e]
	}

    return respData
}

def loginMeross(email, password, domain) {
    def nonce = generator( (('A'..'Z')+('0'..'9')).join(), 16 )
    def unix_time = (Integer)Math.floor(new Date().getTime() / 1000);

    def param = [:]
    param.email = email
    param.password = password
    def json = JsonOutput.toJson(param)
    def encoded_param = json.bytes.encodeBase64().toString();

    def concat_sign = ["23x17ahWarFH6w29", unix_time, nonce, encoded_param].join("")
    MessageDigest digest = MessageDigest.getInstance('MD5')
    digest.update(concat_sign.bytes, 0, concat_sign.length())
    def sign = new BigInteger(1, digest.digest()).toString(16)

    def formBody = "params=${encoded_param}&sign=${sign}&timestamp=${unix_time}&nonce=${nonce}"

	def commandParams = [
		uri: "https://${domain}/v1/Auth/signIn",
		contentType: 'application/x-www-form-urlencoded',
		body : formBody
	]
	def respData
	try {
		httpPost(commandParams) {resp ->
            if (resp.status == 200) {
                def retobj = [code: 200, token: "", key: ""]
                def raw = resp.data?.toString() ?: ""

                // Prefer robust JSON parsing; fall back to string extraction so
                // an unexpected envelope shape does not break a working login.
                try {
                    def parsed = new JsonSlurper().parseText(raw)
                    def d = parsed?.data ?: parsed
                    retobj.token = d?.token ?: ""
                    retobj.key = d?.key ?: ""
                } catch (parseEx) {
                    logDebug("login response not JSON-parseable; using fallback extraction")
                    retobj.token = extractField(raw, "token")
                    retobj.key = extractField(raw, "key")
                }

                if (!retobj.token || !retobj.key) {
                    retobj.code = 9999
                    retobj.error = "Invalid username/password"
                }
                respData = retobj
			} else {
				respData = [code: resp.status, error: "HTTP Protocol Error"]
			}
		}
	} catch (e) {
		respData = [code: 9999, error: e]
	}

    return respData
}

// Fallback extractor: pull the first string value of "field" from raw JSON text.
// Only used if JsonSlurper fails, to preserve resilience against API changes.
private extractField(String src, String field) {
    def marker = "\"${field}\""
    def i = src.indexOf(marker)
    if (i < 0) return ""
    def rest = src.substring(i + marker.length())
    def start = rest.indexOf('"')
    if (start < 0) return ""
    rest = rest.substring(start + 1)
    def end = rest.indexOf('"')
    return end < 0 ? "" : rest.substring(0, end)
}

def logDebug(msg) {
    if(debugLog) log.debug(msg)
}
