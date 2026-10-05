#!/usr/bin/env python3
"""Asks every question of a question file in the app on a connected phone and
saves what the app answered.

Each question starts from a cold app start. The answer, its sources and its
timings are taken from the app's own metrics log, so nothing is typed by hand.
The run can be stopped and started again: questions already answered are skipped.

Usage:
  python bench/run_bench.py --questions bench/vitalik61/questions.jsonl \
      --out bench/vitalik61/answers_redmi12_low.jsonl --profile low

Options:
  --profile NAME     auto, low or high (default: low)
  --timeout S        give up on a question after S seconds (default: 1800)
  --limit N          ask only the first N unanswered questions
  --serial ID        adb device serial, if more than one device is attached

Output: one JSON object per line with "id", "q", "draft" (the answer text, the
field name the pairing script expects), "sources", "status" and "metrics" (the
app's full record). A question the app did not answer is written with
"status": "timeout" or "died" and an empty draft; it is not retried silently.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

PKG = "app.offlineresearch"
ACTIVITY = PKG + "/.MainActivity"
LOG = f"/sdcard/Android/data/{PKG}/files/logs/metrics.jsonl"
REMOTE_QUESTION = "/data/local/tmp/offline_research_question.txt"


def find_adb():
    found = shutil.which("adb")
    if found:
        return found
    for sdk in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk"),
                os.path.expanduser("~/Android/Sdk"), os.path.expanduser("~/Library/Android/sdk")):
        for exe in ("adb", "adb.exe"):
            if sdk and os.path.exists(os.path.join(sdk, "platform-tools", exe)):
                return os.path.join(sdk, "platform-tools", exe)
    sys.exit("adb not found. Install Android platform-tools or set ANDROID_HOME.")


class Phone:
    def __init__(self, serial):
        self.base = [find_adb()] + (["-s", serial] if serial else [])

    def adb(self, *args, timeout=120):
        result = subprocess.run(self.base + list(args), capture_output=True, timeout=timeout,
                                stdin=subprocess.DEVNULL)
        return result.stdout.decode("utf-8", "replace")

    def shell(self, command, timeout=120):
        return self.adb("shell", command, timeout=timeout)

    def connected(self):
        return "device" in self.adb("get-state")

    def log_lines(self):
        """Lines in the app's metrics log, or None if the phone did not answer."""
        text = self.shell(f"wc -l < {LOG} 2>/dev/null; echo ok").split()
        if not text or text[-1] != "ok":
            return None
        return int(text[0]) if text[0].isdigit() else 0

    def last_record(self):
        return json.loads(self.shell(f"tail -n 1 {LOG}"))

    def running(self):
        return bool(self.shell(f"pidof {PKG}").strip())

    def ask(self, question, profile, gps=None):
        # The question travels as a file, so quotes, accents and symbols in it
        # never pass through a command line.
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as handle:
            handle.write(question)
        try:
            self.adb("push", handle.name, REMOTE_QUESTION)
        finally:
            os.unlink(handle.name)
        self.shell(f"am force-stop {PKG}")
        time.sleep(1)
        # A question that says where the device is ("gps": [lat, lon]) has that
        # position handed to the app in place of its satellite receiver.
        position = f'--es gps "{gps[0]},{gps[1]}" ' if gps else ""
        self.shell(f'am start -n {ACTIVITY} --es profile {profile} {position}--es ask "$(cat {REMOTE_QUESTION})"')


def keep_awake():
    """Asks Windows not to sleep while the run lasts; a sleeping computer stalls it."""
    if sys.platform == "win32":
        import ctypes
        ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001 | 0x00000040)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--questions", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--profile", default="low", choices=["auto", "low", "high"])
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--serial")
    args = parser.parse_args()

    questions = [json.loads(line) for line in args.questions.read_text(encoding="utf-8").splitlines() if line.strip()]
    done = set()
    if args.out.exists():
        done = {json.loads(line)["id"] for line in args.out.read_text(encoding="utf-8").splitlines() if line.strip()}
    todo = [q for q in questions if q["id"] not in done][: args.limit]
    phone = Phone(args.serial)
    if not phone.connected():
        sys.exit("no phone connected")
    keep_awake()
    print(f"{len(done)} answered already, {len(todo)} to ask, profile {args.profile}", flush=True)

    for number, question in enumerate(todo, 1):
        before = phone.log_lines()
        while before is None:  # the phone is not answering; wait for it instead of guessing
            time.sleep(10)
            before = phone.log_lines()
        started = time.time()
        phone.ask(question["q"], args.profile, question.get("gps"))
        status, record = "timeout", None
        seen_running = False
        while time.time() - started < args.timeout:
            time.sleep(5)
            lines = phone.log_lines()
            if lines is None:
                continue
            if lines > before:
                record = phone.last_record()
                # Guard against a line from some other question.
                status = "ok" if record.get("rag", {}).get("question", "").strip() == question["q"].strip() else "mismatch"
                break
            alive = phone.running()
            seen_running = seen_running or alive
            # "Not running" counts only while the phone is answering, and the log is
            # read once more first: the app may have finished and been stopped since.
            if seen_running and not alive and phone.connected():
                lines = phone.log_lines()
                if lines is not None and lines > before:
                    continue
                if lines is not None and not phone.running():
                    status = "died"
                    break
        rag = (record or {}).get("rag", {})
        row = {
            "id": question["id"],
            "q": question["q"],
            "draft": rag.get("answer", "") if status == "ok" else "",
            "sources": rag.get("sources", []) if status == "ok" else [],
            "status": status,
            "wall_seconds": round(time.time() - started, 1),
            "metrics": record,
        }
        with args.out.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
        first = rag.get("total_ttft_ms")
        print(f"{time.strftime('%H:%M:%S')} {number}/{len(todo)} {question['id']} {status} "
              f"first word {first / 1000:.0f} s, " if first and status == "ok" else
              f"{time.strftime('%H:%M:%S')} {number}/{len(todo)} {question['id']} {status}, ",
              f"{row['wall_seconds']:.0f} s in all", flush=True)
    print("finished", flush=True)


if __name__ == "__main__":
    main()
