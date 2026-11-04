"""本地 JSON 存储：账号、快递、商品、轨迹、配置。单进程使用，写入走临时文件替换。"""

from __future__ import annotations

import json
import os
import secrets
import threading
import uuid
from pathlib import Path
from typing import Any

CHANNELS = ("xiaomi", "jd", "taobao", "pdd")
CHANNEL_LABELS = {"xiaomi": "小米", "jd": "京东", "taobao": "淘宝", "pdd": "拼多多", "cainiao": "菜鸟"}


class Store:
    def __init__(self, data_dir: Path):
        self.dir = data_dir
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / "profiles").mkdir(exist_ok=True)
        self._lock = threading.RLock()
        self.data: dict[str, Any] = self._read("store.json", {})
        self.data.setdefault("accounts", {c: [] for c in CHANNELS})
        for c in CHANNELS:
            self.data["accounts"].setdefault(c, [])
        self.data.setdefault("items", [])
        self.data.setdefault("goods", {})
        self.data.setdefault("traces", {})
        self.data.setdefault("hidden", [])
        self.data.setdefault("lastSync", {})
        self.config: dict[str, Any] = self._read("config.json", {})
        changed = False
        if not self.config.get("token"):
            self.config["token"] = secrets.token_urlsafe(24)
            changed = True
        if "syncIntervalMin" not in self.config:
            self.config["syncIntervalMin"] = 30
            changed = True
        if changed:
            self.save_config()

    # ───────── 文件 ─────────

    def _read(self, name: str, default: Any) -> Any:
        p = self.dir / name
        if not p.exists():
            return default
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return default

    def _write(self, name: str, value: Any) -> None:
        p = self.dir / name
        tmp = p.with_suffix(p.suffix + ".tmp")
        tmp.write_text(json.dumps(value, ensure_ascii=False, indent=1), encoding="utf-8")
        os.replace(tmp, p)

    def save(self) -> None:
        with self._lock:
            self._write("store.json", self.data)

    def save_config(self) -> None:
        with self._lock:
            self._write("config.json", self.config)

    # ───────── 账号 ─────────

    def accounts(self, channel: str, enabled_only: bool = False) -> list[dict]:
        lst = self.data["accounts"][channel]
        return [a for a in lst if a.get("enabled", True)] if enabled_only else list(lst)

    def account(self, channel: str, account_id: str) -> dict | None:
        return next((a for a in self.data["accounts"][channel] if a["id"] == account_id), None)

    def upsert_account(self, channel: str, key: str, label: str, payload: dict) -> dict:
        """同一账号（key 相同）重复登录 → 更新凭证，不新增。"""
        with self._lock:
            lst = self.data["accounts"][channel]
            acc = next((a for a in lst if a.get("key") == key and key), None)
            if acc is None:
                acc = {"id": uuid.uuid4().hex[:10], "key": key, "label": label, "enabled": True, "payload": {}}
                lst.append(acc)
            acc["payload"].update(payload)
            if label and not acc.get("label"):
                acc["label"] = label
            self.save()
            return acc

    def update_account(self, channel: str, account_id: str, **fields: Any) -> dict | None:
        with self._lock:
            acc = self.account(channel, account_id)
            if acc is None:
                return None
            for k, v in fields.items():
                if k == "payload":
                    acc["payload"].update(v)
                elif v is not None:
                    acc[k] = v
            self.save()
            return acc

    def remove_account(self, channel: str, account_id: str) -> bool:
        with self._lock:
            lst = self.data["accounts"][channel]
            acc = self.account(channel, account_id)
            if acc is None:
                return False
            lst.remove(acc)
            profile = acc["payload"].get("profile")
            if profile:
                (self.dir / "profiles" / profile).unlink(missing_ok=True)
            # 该账号的件随之移除（与 App「未登录渠道的数据不残留」一致）
            self.data["items"] = [i for i in self.data["items"] if i.get("accountId") != account_id]
            self.save()
            return True

    def profile_path(self, name: str) -> Path:
        return self.dir / "profiles" / name

    def public_account(self, channel: str, acc: dict) -> dict:
        p = acc.get("payload", {})
        return {
            "id": acc["id"],
            "channel": channel,
            "label": acc.get("label", ""),
            "enabled": acc.get("enabled", True),
            "phones": p.get("phones", []) if channel == "xiaomi" else None,
            "loggedInAt": p.get("loggedInAt"),
            "lastError": acc.get("lastError", ""),
        }

    # ───────── 快递 / 商品 / 轨迹 ─────────

    @property
    def items(self) -> list[dict]:
        return self.data["items"]

    def item(self, mail_no: str) -> dict | None:
        return next((i for i in self.data["items"] if i["mailNo"] == mail_no), None)

    def trace(self, mail_no: str) -> dict | None:
        return self.data["traces"].get(mail_no)

    def save_trace(self, mail_no: str, points: list[dict], done: bool, fetched_at: int) -> None:
        if not points:
            return
        with self._lock:
            self.data["traces"][mail_no] = {"fetchedAt": fetched_at, "done": done, "points": points}

    def goods(self, mail_no: str) -> dict | None:
        return self.data["goods"].get(mail_no)

    def merge_goods(self, goods: dict[str, dict]) -> None:
        with self._lock:
            for k, v in goods.items():
                if v.get("name"):
                    self.data["goods"][k] = v

    def hide(self, mail_no: str) -> None:
        with self._lock:
            if mail_no not in self.data["hidden"]:
                self.data["hidden"].append(mail_no)
            self.data["items"] = [i for i in self.data["items"] if i["mailNo"] != mail_no]
            self.save()
