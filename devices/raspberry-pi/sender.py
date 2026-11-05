#!/usr/bin/env python3
"""Corrosion Watch device client for a Raspberry Pi with an SHT31 sensor (I2C address 0x44).

Reads temperature and humidity every INTERVAL seconds, signs each batch exactly like the simulator
(HMAC-SHA256 over "<unix seconds>.<raw body>", lowercase hex) and posts it to the ingestion API.
Readings that cannot be sent are kept in a local queue and sent later in order, up to 500 per batch.

    pip install smbus2
    export ECP_INGEST_URL=https://corrosion-watch-platform.onrender.com/api/v1/ingest/readings
    export ECP_DEVICE_ID=PI-001 ECP_DEVICE_SECRET='shown once when an admin registers the device'
    python3 sender.py            # or install the systemd unit in this folder

Keep the Pi's clock correct (NTP is on by default in Raspberry Pi OS): the server rejects
timestamps more than 5 minutes off and sensor health flags clock skew.
"""
import hashlib
import hmac
import json
import os
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

URL = os.environ.get("ECP_INGEST_URL", "http://localhost:8081/api/v1/ingest/readings")
DEVICE = os.environ["ECP_DEVICE_ID"] if "ECP_DEVICE_ID" in os.environ else "PI-001"
SECRET = os.environ.get("ECP_DEVICE_SECRET", "")
INTERVAL = int(os.environ.get("ECP_INTERVAL_S", "15"))
STATE = Path(os.environ.get("ECP_STATE_DIR", Path.home() / ".corrosion-watch"))
STATE.mkdir(parents=True, exist_ok=True)
SEQ_FILE, QUEUE_FILE = STATE / "seq", STATE / "queue.jsonl"


def sign(secret: str, epoch_seconds: int, body: str) -> str:
    return hmac.new(secret.encode(), f"{epoch_seconds}.{body}".encode(), hashlib.sha256).hexdigest()


def crc8(data: bytes) -> int:
    crc = 0xFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x31) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def read_sht31(bus, address=0x44):
    """Single-shot, high repeatability measurement. Returns (temp °C, RH %)."""
    from smbus2 import i2c_msg
    bus.i2c_rdwr(i2c_msg.write(address, [0x24, 0x00]))
    time.sleep(0.02)
    read = i2c_msg.read(address, 6)
    bus.i2c_rdwr(read)
    d = bytes(read)
    if crc8(d[0:2]) != d[2] or crc8(d[3:5]) != d[5]:
        raise IOError("SHT31 CRC mismatch")
    t = -45 + 175 * ((d[0] << 8) | d[1]) / 65535
    rh = 100 * ((d[3] << 8) | d[4]) / 65535
    return round(t, 2), round(rh, 2)


def next_seq() -> int:
    seq = int(SEQ_FILE.read_text()) + 1 if SEQ_FILE.exists() else 1
    SEQ_FILE.write_text(str(seq))
    return seq


def send(readings) -> bool:
    body = json.dumps({"readings": readings}, separators=(",", ":"))
    ts = int(time.time())
    req = urllib.request.Request(URL, data=body.encode(), method="POST", headers={
        "Content-Type": "application/json", "X-Device-Id": DEVICE, "X-Timestamp": str(ts), "X-Signature": sign(SECRET, ts, body)})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return 200 <= r.status < 300
    except urllib.error.HTTPError as e:
        print(f"server said {e.code}: {e.read()[:200]!r}")
        # 400 = the batch itself is invalid: drop it so it cannot block the queue forever.
        # 401/403 = wrong key, clock or deactivated device: keep the readings and fix the configuration.
        return e.code == 400
    except OSError as e:
        print(f"network error: {e}")
        return False


def main():
    from smbus2 import SMBus
    if not SECRET:
        raise SystemExit("Set ECP_DEVICE_SECRET (shown once when the device is registered)")
    print(f"{DEVICE} -> {URL} every {INTERVAL}s")
    with SMBus(1) as bus:
        while True:
            started = time.time()
            try:
                t, rh = read_sht31(bus)
                reading = {"seq": next_seq(), "measuredAt": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                           "temperatureC": t, "humidityPct": rh}
                with QUEUE_FILE.open("a") as q:
                    q.write(json.dumps(reading) + "\n")
            except IOError as e:
                print(f"sensor read failed: {e}")
            pending = [json.loads(l) for l in QUEUE_FILE.read_text().splitlines()] if QUEUE_FILE.exists() else []
            while pending and send(pending[:500]):
                pending = pending[500:]
                QUEUE_FILE.write_text("".join(json.dumps(r) + "\n" for r in pending))
            time.sleep(max(1, INTERVAL - (time.time() - started)))


if __name__ == "__main__":
    main()
