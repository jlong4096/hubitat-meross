<span align="center">

# Hubitat Meross Garage Door Opener

Unofficial Hubitat app and driver for Meross Smart WiFi Garage Door Openers

</span>

## Supported Devices

- MSG100 - Smart WiFi Garage Door Opener (single door)
- MSG200 - Smart WiFi Garage Door Opener (up to 3 doors)

> **Note:** This fork focuses exclusively on the garage door opener. The smart
> plug and dimmer drivers from the upstream repository have been removed — if
> you need those, see [ithinkdancan/hubitat-meross](https://github.com/ithinkdancan/hubitat-meross).

## Installation

1. Set up your garage door opener in the Meross mobile app first, and give it a
   static IP address (or DHCP reservation) on your network.

2. In Hubitat, go to **Drivers Code** → **New Driver** → **Import**, and use:

   ```
   https://raw.githubusercontent.com/jlong4096/hubitat-meross/main/drivers/meross-smart-wifi-garage-door-opener.groovy
   ```

3. Go to **Apps Code** → **New App** → **Import**, and use:

   ```
   https://raw.githubusercontent.com/jlong4096/hubitat-meross/main/apps/meross_app.groovy
   ```

4. Go to **Apps** → **Add User App** → **Meross Garage Door Manager**.

## Adding Garage Doors

Open the **Meross Garage Door Manager** app and choose **Add New Garage Doors**.
You will need:

- Your Meross account email and password (used once to fetch your account key
  and device list, then cleared from the app's settings)
- The local IP address of your garage door opener
- Your Meross API region domain (`iotx-us.meross.com`, `iotx-eu.meross.com`, or
  `iotx-ap.meross.com`)

The app logs in to the Meross cloud, lets you pick which doors to add, and
creates a child device per door with the IP, channel, and account key already
configured. All door control afterward is local (LAN) — the cloud is only used
during setup.

## Device Settings

- **Garage Open/Close time**: the delay (in seconds) before the driver polls
  the opener to confirm the door's state after an open/close command. Set it a
  couple of seconds longer than your door's full travel time.
- The driver polls the opener every minute to keep the door state in sync with
  changes made outside Hubitat (wall button, Meross app, etc.).

## Credits

This is a fork of community work by several authors, maintained by
[James Long](https://github.com/jlong4096):

- [ithinkdancan/hubitat-meross](https://github.com/ithinkdancan/hubitat-meross) —
  original drivers by Daniel Tijerina, with contributions from Todd Pike
- Meross Garage Door Manager app originally by Art Ardolino (ajardolino3)
- Based on prior art from [homebridge-meross](https://github.com/donavanbecker/homebridge-meross)
  and [meross-api](https://github.com/bapirex/meross-api)

## License

[Apache 2.0](./LICENSE)
