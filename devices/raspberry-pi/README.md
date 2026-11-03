# Raspberry Pi + SHT31 (optional hardware)

The proposal treats hardware as optional: the simulator produces the same signed readings. Use this when you
want one real device in the demo.

**Wiring (I2C):** SHT31 VIN → Pi pin 1 (3.3 V), GND → pin 6, SDA → pin 3, SCL → pin 5. Enable I2C with
`sudo raspi-config` → Interface Options → I2C, then `i2cdetect -y 1` should show `44`.

1. In the app: Administration → Sensors → Register device (kind Physical). Copy the key; it is shown once.
2. On the Pi: copy `sender.py` and `corrosion-watch.service` to `/home/pi/corrosion-watch/`, `pip install smbus2`.
3. Create `/home/pi/corrosion-watch/device.env`:
   ```
   ECP_INGEST_URL=https://corrosion-watch-platform.onrender.com/api/ingest/readings
   ECP_DEVICE_ID=PI-001
   ECP_DEVICE_SECRET=the-key-you-copied
   ```
4. `sudo cp corrosion-watch.service /etc/systemd/system/ && sudo systemctl enable --now corrosion-watch`

Readings that cannot be sent (Wi-Fi down) queue on the Pi and are sent in order when the network returns;
sequence numbers survive restarts, so sensor health can tell lost readings from a reboot.
