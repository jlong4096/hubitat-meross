/**
 * Meross Smart WiFi Garage Door Opener (MSG100 / MSG200)
 *
 * Maintained by James Long
 * https://github.com/jlong4096/hubitat-meross
 *
 * Originally written by Daniel Tijerina (ithinkdancan/hubitat-meross),
 * with contributions from Todd Pike and the Hubitat community.
 *
 * Licensed under the Apache License, Version 2.0 (the 'License'); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import java.security.MessageDigest

metadata {
    definition(
        name: 'Meross Smart WiFi Garage Door Opener',
        namespace: 'jlong4096',
        author: 'James Long',
        importUrl: 'https://raw.githubusercontent.com/jlong4096/hubitat-meross/main/drivers/meross-smart-wifi-garage-door-opener.groovy'
    ) {
        capability 'DoorControl'
        capability 'GarageDoorControl'
        capability 'Actuator'
        capability 'ContactSensor'
        capability 'Refresh'

        attribute 'model', 'string'
        attribute 'version', 'string'
    }
    preferences {
        section('Device Selection') {
            input('deviceIp', 'text', title: 'Device IP Address', description: '', required: true, defaultValue: '')
            input('key', 'text', title: 'Key', description: 'Meross account key (set automatically by the Meross Garage Door Manager app). Required for firmware 2.0.0 and greater.', required: false, defaultValue: '')
            input('messageId', 'text', title: 'Message ID', description: '', required: true, defaultValue: '')
            input('timestamp', 'number', title: 'Timestamp', description: '', required: true, defaultValue: '')
            input('sign', 'text', title: 'Sign', description: '', required: true, defaultValue: '')
            input('uuid', 'text', title: 'UUID', description: '', required: true, defaultValue: '')
            input('channel', 'number', title: 'Garage Door Port', description: '', required: true, defaultValue: 1)
            input('garageOpenCloseTime','number',title: 'Garage Open/Close time (in seconds)', description:'Delay before confirming state after a command. Set a couple seconds longer than your door\'s full travel time.', required: true, defaultValue: 15)
            input('DebugLogging', 'bool', title: 'Enable debug logging', defaultValue: true)
        }
    }
}

def getDriverVersion() {
    1
}

def initialize() {
    log 'Initializing Device'
    refresh()

    unschedule('refresh')
    runEvery1Minute('refresh')
}

def sendCommand(int open) {

    def currentVersion = device.currentState('version')?.value ? device.currentState('version')?.value.replace(".","").toInteger() : 200

    // Firmware version 2.0.0 and greater sign each request with the account key;
    // older firmware reuses a captured messageId/sign/timestamp.
    if (!settings.deviceIp || !settings.uuid || (currentVersion >= 200 && !settings.key) || (currentVersion < 200 && (!settings.messageId || !settings.sign || !settings.timestamp))) {
        sendEvent(name: 'door', value: 'unknown', isStateChange: false)
        log.warn('missing setting configuration')
        return
    }
    sendEvent(name: 'door', value: open ? 'opening' : 'closing', isStateChange: true)

    try {
        def payloadData = currentVersion >= 200 ? getSign() : [MessageId: settings.messageId, Sign: settings.sign, CurrentTime: settings.timestamp]

        def hubAction = new hubitat.device.HubAction([
            method: 'POST',
            path: '/config',
            headers: [
                'HOST': settings.deviceIp,
                'Content-Type': 'application/json',
            ],
            body: '{"payload":{"state":{"open":' + open + ',"channel":' + settings.channel + ',"uuid":"' + settings.uuid + '"}},"header":{"messageId":"'+payloadData.get('MessageId')+'","method":"SET","from":"http://'+settings.deviceIp+'/config","sign":"'+payloadData.get('Sign')+'","namespace":"Appliance.GarageDoor.State","triggerSrc":"AndroidLocal","timestamp":' + payloadData.get('CurrentTime') + ',"payloadVersion":1' + ',"uuid":"' + settings.uuid + '"}}'
        ])
        sendHubCommand(hubAction)
        runIn(settings.garageOpenCloseTime.toInteger(), 'refresh')
    } catch (e) {
        log.error("sendCommand hit exception ${e}")
    }
}


def refresh() {
    def currentVersion = device.currentState('version')?.value ? device.currentState('version')?.value.replace(".","").toInteger() : 200

    if (!settings.deviceIp || !settings.uuid || (currentVersion >= 200 && !settings.key) || (currentVersion < 200 && (!settings.messageId || !settings.sign || !settings.timestamp))) {
        sendEvent(name: 'door', value: 'unknown', isStateChange: false)
        log.warn('missing setting configuration')
        return
    }
    try {
        def payloadData = currentVersion >= 200 ? getSign() : [MessageId: settings.messageId, Sign: settings.sign, CurrentTime: settings.timestamp]

        log.info('Refreshing')

        def hubAction = new hubitat.device.HubAction([
            method: 'POST',
            path: '/config',
            headers: [
                'HOST': settings.deviceIp,
                'Content-Type': 'application/json',
            ],
            body: '{"payload":{},"header":{"messageId":"'+payloadData.get('MessageId')+'","method":"GET","from":"http://'+settings.deviceIp+'/subscribe","sign":"'+ payloadData.get('Sign') +'","namespace": "Appliance.System.All","triggerSrc":"AndroidLocal","timestamp":' + payloadData.get('CurrentTime') + ',"payloadVersion":1}}'
        ])
        log hubAction
        sendHubCommand(hubAction)
    } catch (Exception e) {
        log.debug "refresh hit exception ${e}"
    }
}

def open() {
    log.info('Opening Garage')
    sendCommand(1)
}

def close() {
    log.info('Closing Garage')
    sendCommand(0)
}

def updated() {
    log.info('Updated')
    initialize()
}

def parse(String description) {
    def msg = parseLanMessage(description)
    def body = parseJson(msg.body)

    if(msg.status != 200) {
         log.error("Request failed")
         return
    }

    // Close/Open request was sent
    if(body.header.method == "SETACK") return

    if (body.payload.all) {
        def channel = settings.channel.intValue()
        def doors = body.payload.all.digest.garageDoor
        // Match on the reported channel (MSG100 uses channel 0, MSG200 uses 1..n).
        // Fall back to positional indexing for configs created before channels
        // were reported, where MSG100 doors were stored as channel 1.
        def door = doors.find { it.channel == channel } ?: (channel > 0 && doors.size() >= channel ? doors[channel - 1] : null)
        if (door == null) {
            log.error("No garage door found for channel ${channel}")
            return
        }
        sendEvent(name: 'door', value: door.open ? 'open' : 'closed')
        sendEvent(name: 'contact', value: door.open ? 'open' : 'closed')
        sendEvent(name: 'version', value: body.payload.all.system.firmware.version, isStateChange: false)
        sendEvent(name: 'model', value: body.payload.all.system.hardware.type, isStateChange: false)
    } else {
        log.error ("Request failed")
    }
}

def getSign(int stringLength = 16){

    // Generate a random string
    def chars = 'abcdefghijklmnopqrstuvwxyz0123456789'
    def randomString = new Random().with { (0..stringLength).collect { chars[ nextInt(chars.length() ) ] }.join()}

    int currentTime = new Date().getTime() / 1000
    messageId = MessageDigest.getInstance("MD5").digest((randomString + currentTime.toString()).bytes).encodeHex().toString()
    sign = MessageDigest.getInstance("MD5").digest((messageId + settings.key + currentTime.toString()).bytes).encodeHex().toString()

    def requestData = [
         CurrentTime: currentTime,
         MessageId: messageId,
         Sign: sign
    ]

    return requestData
}

def log(msg) {
    if (DebugLogging) {
        log.debug(msg)
    }
}
