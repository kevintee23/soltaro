#!/usr/bin/env python3
"""
Soltaro/Qendercore -> Hubitat bridge

- Authenticates against Qendercore cloud (refresh-token first, login fallback)
- Polls active inverter metrics
- Pushes normalized values to a Hubitat virtual device via Maker API commands
"""

from __future__ import annotations

import argparse
import json
import os
import ssl
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib import parse, request
from urllib.error import HTTPError

AUTH_BASE = "https://auth.qendercore.com:8000"
API_BASE = "https://api.qendercore.com:8000"
CLIENT_SEQ = "A.2.3"
USER_AGENT = "Qendercore/98 CFNetwork/3860.300.31 Darwin/25.2.0"


@dataclass
class Config:
    username: str
    password: str
    hwid: str
    poll_seconds: int
    state_file: Path
    insecure_tls: bool
    hubitat_base_url: Optional[str]
    hubitat_token: Optional[str]
    hubitat_device_id: Optional[str]


def load_env(path: Path) -> Dict[str, str]:
    out: Dict[str, str] = {}
    if not path.exists():
        return out
    for line in path.read_text().splitlines():
        t = line.strip()
        if not t or t.startswith("#") or "=" not in t:
            continue
        k, v = t.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def build_config(env: Dict[str, str]) -> Config:
    def req(key: str, default: Optional[str] = None) -> str:
        val = env.get(key, default)
        if not val:
            raise ValueError(f"Missing required setting: {key}")
        return val

    state_file = Path(env.get("SOLTARO_STATE_FILE", str(Path.home() / ".soltaro-qendercore-state.json"))).expanduser()
    poll_seconds = int(env.get("SOLTARO_POLL_SECONDS", "300"))

    return Config(
        username=req("SOLTARO_USERNAME"),
        password=req("SOLTARO_PASSWORD"),
        hwid=req("SOLTARO_HWID", "644fc8f0-2a4b-4867-9ce4-b6af0b778fb4"),
        poll_seconds=poll_seconds,
        state_file=state_file,
        insecure_tls=env.get("SOLTARO_INSECURE_TLS", "false").lower() in ("1", "true", "yes", "on"),
        hubitat_base_url=env.get("HUBITAT_MAKER_BASE_URL"),
        hubitat_token=env.get("HUBITAT_MAKER_TOKEN"),
        hubitat_device_id=env.get("HUBITAT_DEVICE_ID"),
    )


class QenderClient:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.state = self._load_state()

    def _load_state(self) -> Dict[str, Any]:
        if self.cfg.state_file.exists():
            try:
                return json.loads(self.cfg.state_file.read_text())
            except Exception:
                pass
        return {}

    def _save_state(self) -> None:
        self.cfg.state_file.parent.mkdir(parents=True, exist_ok=True)
        self.cfg.state_file.write_text(json.dumps(self.state, indent=2))

    def _http(self, method: str, url: str, *, headers: Optional[Dict[str, str]] = None, data: Optional[bytes] = None):
        req = request.Request(url=url, method=method, headers=headers or {}, data=data)
        ctx = ssl._create_unverified_context() if self.cfg.insecure_tls else None
        try:
            with request.urlopen(req, timeout=30, context=ctx) as resp:
                body = resp.read().decode("utf-8")
                return resp.status, dict(resp.headers.items()), body
        except HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            return e.code, dict(e.headers.items()), body

    @staticmethod
    def _extract_rtok(set_cookie: Optional[str]) -> Optional[str]:
        if not set_cookie:
            return None
        # Handles a single Set-Cookie header payload for rtok
        for part in set_cookie.split(","):
            p = part.strip()
            if p.startswith("rtok="):
                return p.split(";", 1)[0].split("=", 1)[1]
            if " rtok=" in p:
                idx = p.index(" rtok=") + 1
                return p[idx:].split(";", 1)[0].split("=", 1)[1]
        return None

    def login(self) -> str:
        boundary = "----OpenClawSoltaroBoundary"
        fields = {
            "username": self.cfg.username,
            "password": self.cfg.password,
        }
        parts: List[bytes] = []
        for key, val in fields.items():
            parts.append(f"--{boundary}\r\n".encode())
            parts.append(f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode())
            parts.append(f"{val}\r\n".encode())
        parts.append(f"--{boundary}--\r\n".encode())
        body = b"".join(parts)

        status, hdrs, text = self._http(
            "POST",
            f"{AUTH_BASE}/v1/auth/login",
            headers={
                "content-type": f"multipart/form-data; boundary={boundary}",
                "accept": "application/json",
                "x-qc-client-seq": CLIENT_SEQ,
                "user-agent": USER_AGENT,
            },
            data=body,
        )
        if status != 200:
            raise RuntimeError(f"Login failed ({status}): {text[:300]}")

        payload = json.loads(text)
        token = payload["access_token"]
        rtok = self._extract_rtok(hdrs.get("Set-Cookie") or hdrs.get("set-cookie"))
        if rtok:
            self.state["refresh_token"] = rtok
            self._save_state()
        return token

    def refresh(self) -> Optional[str]:
        rtok = self.state.get("refresh_token")
        if not rtok:
            return None

        status, hdrs, text = self._http(
            "GET",
            f"{AUTH_BASE}/v1/auth/tokens",
            headers={
                "accept": "application/json",
                "cookie": f"rtok={rtok}",
                "x-qc-client-seq": CLIENT_SEQ,
                "user-agent": USER_AGENT,
            },
        )
        if status != 200:
            return None

        payload = json.loads(text)
        token = payload["access_token"]
        new_rtok = self._extract_rtok(hdrs.get("Set-Cookie") or hdrs.get("set-cookie"))
        if new_rtok:
            self.state["refresh_token"] = new_rtok
            self._save_state()
        return token

    def access_token(self) -> str:
        return self.refresh() or self.login()

    def ds(self, token: str, body: Dict[str, Any]) -> Dict[str, Any]:
        status, _hdrs, text = self._http(
            "POST",
            f"{API_BASE}/v1/h/ds",
            headers={
                "accept": "application/json",
                "authorization": f"bearer {token}",
                "content-type": "application/json",
                "x-qc-client-seq": CLIENT_SEQ,
                "user-agent": USER_AGENT,
            },
            data=json.dumps(body).encode("utf-8"),
        )
        if status != 200:
            raise RuntimeError(f"API ds failed ({status}): {text[:400]}")
        return json.loads(text)

    def devicetree(self, token: str) -> Dict[str, Any]:
        status, _hdrs, text = self._http(
            "GET",
            f"{API_BASE}/v1/h/devicetree?hf=soltinv",
            headers={
                "accept": "application/json",
                "authorization": f"bearer {token}",
                "x-qc-client-seq": CLIENT_SEQ,
                "user-agent": USER_AGENT,
            },
        )
        if status != 200:
            raise RuntimeError(f"devicetree failed ({status}): {text[:300]}")
        return json.loads(text)


def cols_rows_to_dict(payload: Dict[str, Any]) -> Dict[str, Any]:
    cols = payload.get("cols") or []
    rows = payload.get("rows") or []
    if not cols or not rows:
        return {}
    row = rows[0]
    out: Dict[str, Any] = {}
    for i, col in enumerate(cols):
        col_id = col.get("id", f"col{i}")
        out[col_id] = row[i] if i < len(row) else None
    return out


def battery_state_from_power(power_w: Optional[float]) -> str:
    if power_w is None:
        return "unknown"
    if power_w > 0:
        return "charging"
    if power_w < 0:
        return "discharging"
    return "idle"


def hubitat_cmd(cfg: Config, command: str, arg: Any) -> None:
    if not cfg.hubitat_base_url or not cfg.hubitat_token or not cfg.hubitat_device_id:
        raise RuntimeError("Hubitat settings missing (HUBITAT_MAKER_BASE_URL, HUBITAT_MAKER_TOKEN, HUBITAT_DEVICE_ID)")
    base = cfg.hubitat_base_url.rstrip("/")
    encoded_arg = parse.quote(str(arg), safe="")
    path = f"/devices/{cfg.hubitat_device_id}/{command}/{encoded_arg}"
    url = f"{base}{path}?access_token={parse.quote(cfg.hubitat_token, safe='')}"
    req = request.Request(url=url, method="GET")
    with request.urlopen(req, timeout=20) as resp:
        _ = resp.read()


def push_to_hubitat(cfg: Config, data: Dict[str, Any]) -> None:
    mapping = {
        "setBatterySoc": data.get("inv.core.batt_soc_perc"),
        "setBatteryPower": data.get("inv.core.battery_pwr_w"),
        "setBatteryState": battery_state_from_power(data.get("inv.core.battery_pwr_w")),
        "setSolarPower": data.get("inv.core.solar_prod_pwr_w"),
        "setConsumptionPower": data.get("inv.core.consumption_pwr_w"),
        "setGridPower": data.get("inv.core.meter_pwr_w"),
        "setLastTimestamp": data.get("ts") or datetime.now().isoformat(timespec="seconds"),
    }

    for cmd, val in mapping.items():
        if val is None:
            continue
        hubitat_cmd(cfg, cmd, val)


def fetch_snapshot(client: QenderClient) -> Dict[str, Any]:
    token = client.access_token()

    hwv = client.ds(
        token,
        {
            "_ft": "hwv",
            "hwid": client.cfg.hwid,
            "f": ["enchwt"],
        },
    )
    hwv_row = (hwv.get("rows") or [[None]])[0]
    enchwt = hwv_row[0] if hwv_row else None
    if not enchwt:
        raise RuntimeError("No enchwt returned from hwv query")

    realtime = client.ds(
        token,
        {
            "_ft": "hwm",
            "hwid": client.cfg.hwid,
            "enchwt": enchwt,
            "props": [
                "inv.core.solar_prod_pwr_w",
                "inv.core.consumption_pwr_w",
                "inv.core.meter_pwr_w",
                "inv.core.battery_pwr_w",
                "inv.core.batt_soc_perc",
            ],
            "duration": "PT15M",
            "resolution": "last",
            "tz": "local",
        },
    )

    return cols_rows_to_dict(realtime)


def command_list_inverters(client: QenderClient) -> None:
    token = client.access_token()
    tree = client.devicetree(token)
    for node in tree.get("tree", []):
        print(f"{node.get('title')}\t{node.get('key')}")


def command_poll_once(client: QenderClient, emit_json: bool = False, no_push: bool = False) -> None:
    data = fetch_snapshot(client)
    if not no_push:
        push_to_hubitat(client.cfg, data)
    if emit_json:
        print(json.dumps(data, indent=2))
    else:
        soc = data.get("inv.core.batt_soc_perc")
        bp = data.get("inv.core.battery_pwr_w")
        state = battery_state_from_power(bp)
        prefix = "Fetched" if no_push else "Pushed to Hubitat"
        print(f"{prefix} | SOC={soc}% | battery={bp}W ({state})")


def command_daemon(client: QenderClient) -> None:
    while True:
        try:
            command_poll_once(client)
        except Exception as e:
            print(f"[{datetime.now().isoformat(timespec='seconds')}] poll error: {e}")
        time.sleep(client.cfg.poll_seconds)


def main() -> None:
    parser = argparse.ArgumentParser(description="Soltaro Qendercore -> Hubitat bridge")
    parser.add_argument("command", choices=["list-inverters", "poll-once", "daemon"])
    parser.add_argument("--env", default=str(Path(__file__).with_name("soltaro.env")))
    parser.add_argument("--json", action="store_true", help="Print JSON snapshot for poll-once")
    parser.add_argument("--no-push", action="store_true", help="Fetch only; do not send commands to Hubitat")
    args = parser.parse_args()

    env = dict(os.environ)
    env.update(load_env(Path(args.env)))
    cfg = build_config(env)
    client = QenderClient(cfg)

    if args.command == "list-inverters":
        command_list_inverters(client)
    elif args.command == "poll-once":
        command_poll_once(client, emit_json=args.json, no_push=args.no_push)
    else:
        command_daemon(client)


if __name__ == "__main__":
    main()
