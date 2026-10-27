#!/usr/bin/env python3
"""Clients for the isolated follow fixture/controller; no production protocol or credentials."""
import argparse
import json
from pathlib import Path
import time
import urllib.request
import uuid


def fixture(directory: Path, action: str, **values):
    request_id = str(uuid.uuid4())
    pending = directory / "command.pending"
    pending.write_text(json.dumps(dict(id=request_id, action=action, **values)))
    pending.replace(directory / "command.json")
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        response = directory / "response.json"
        if response.exists():
            result = json.loads(response.read_text())
            if result.get("id") == request_id:
                if not result.get("ok"):
                    raise RuntimeError(result.get("error", "fixture failed"))
                return result
        time.sleep(0.05)
    raise TimeoutError("Fixture command was not acknowledged; inspect before resending")


def controller(port: int, nonce: str, action: str, **values):
    payload = json.dumps(dict(action=action, **values)).encode()
    request = urllib.request.Request(f"http://127.0.0.1:{port}/{nonce}", data=payload,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        result = json.load(response)
    if not result.get("ok"):
        raise RuntimeError(result.get("error", "controller failed"))
    return result


def probe(directory, port, nonce, trigger, config, session):
    """Observed recovery chronology, including concurrent source polling while driver opens."""
    import subprocess
    import threading
    def control(action, **values): return controller(port, nonce, action, **values)
    def source(action, **values): return fixture(directory, action, **values)
    def until(check, seconds=15):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = check()
            if value: return value
            time.sleep(.1)
        raise TimeoutError("Observed recovery condition did not arrive")
    control("home")
    until(lambda: control("status").get("activeWindowPackage") == "com.google.android.apps.nexuslauncher")
    before = source("stats")
    pid = control("status")["targetPid"]
    timeline = []
    epoch = time.monotonic()
    def event(name, **values): timeline.append(dict(event=name, elapsedMs=round((time.monotonic()-epoch)*1000), **values))
    event("armBlackholeStarted"); source("blackhole"); event("armBlackholeAcknowledged")
    event("freshFrameSendStarted"); source("republish"); event("freshFrameSendAcknowledged")
    done = threading.Event()
    errors = []
    def observe():
        try:
            previous = None
            deadline = time.monotonic() + 18
            while time.monotonic() < deadline:
                current = source("stats")
                signature = [(current[k]["active"], current[k]["subscriptions"], current[k]["pings"]) for k in ["a", "b"]]
                if signature != previous: event("sourceObserved", source=current); previous = signature
                if all(current[k]["active"] == 1 and current[k]["subscriptions"] > before[k]["subscriptions"] for k in ["a", "b"]):
                    event("firstSuccessorObserved"); done.set(); return
                time.sleep(.1)
            raise TimeoutError("No successor before the native20s idle budget")
        except Exception as error: errors.append(error); done.set()
    observer = threading.Thread(target=observe)
    observer.start()
    event("triggerStarted", trigger=trigger)
    if trigger == "resume":
        subprocess.run(["/home/node/.t3/userdata/device/bin/agent-device", "open", "dev.avillagran.ot3android", "--no-test-ime",
                        "--platform", "android", "--serial", "emulator-5580", "--config", config, "--session", session],
                       check=True, timeout=25, stdout=subprocess.DEVNULL)
    else:
        control("wifiOff")
        until(lambda: "Wifi is disabled" in control("status")["wifi"])
        event("wifiDisabledObserved")
        control("wifiOn")
        until(lambda: "Wifi is enabled" in control("status")["wifi"])
        event("wifiEnabledObserved")
    event("triggerReturned")
    observer.join(20)
    if errors: raise errors[0]
    assert done.is_set()
    after = control("status")
    assert after["targetPid"] == pid, "The target process must survive a wake probe"
    return dict(trigger=trigger, targetPid=pid, timeline=timeline,
                interpretation="Source reconnection observed before native20s silence timeout. Network trigger may also break TCP; not exclusive wake attribution.")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["fixture", "controller", "probe"])
    parser.add_argument("action")
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--port", type=int)
    parser.add_argument("--nonce-file", type=Path)
    parser.add_argument("--agent-config")
    parser.add_argument("--agent-session")
    parser.add_argument("--values", default="{}", help="Synthetic lab fields only")
    args = parser.parse_args()
    values = json.loads(args.values)
    if args.mode == "fixture":
        result = fixture(args.directory, args.action, **values)
    elif args.mode == "controller":
        result = controller(args.port, args.nonce_file.read_text().strip(), args.action, **values)
    else:
        assert args.action in ("resume", "network")
        result = probe(args.directory, args.port, args.nonce_file.read_text().strip(), args.action, args.agent_config, args.agent_session)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
