"""远程登录：服务器在无头浏览器里打开平台登录页，网页端看截图、把点击/输入转发过来。

适用于没有显示器的服务器（如 Windows 服务会话）；手机浏览器也能完成扫码、短信验证码和滑块。
登录态以 Playwright storage_state 保存为 profiles/<channel>-<账号id>.json。
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from typing import Any

from playwright.async_api import BrowserContext, Page

from .browser import BrowserManager
from .channels import jd, pdd, taobao, xiaomi
from .parse import cookie_map, now_ms
from .store import Store

log = logging.getLogger("login")

IDLE_TIMEOUT = 15 * 60


class LoginSession:
    def __init__(self, channel: str, ctx: BrowserContext, page: Page, mobile: bool):
        self.id = uuid.uuid4().hex[:12]
        self.channel = channel
        self.ctx = ctx
        self.page = page
        self.mobile = mobile
        self.status = "waiting"
        self.message = "请在画面里完成登录"
        self.account: dict | None = None
        self.touched = time.time()
        self.urls: list[str] = []
        self.task: asyncio.Task | None = None

    def public(self) -> dict:
        return {
            "id": self.id, "channel": self.channel, "status": self.status, "message": self.message,
            "url": self.page.url if not self.page.is_closed() else "",
            "viewport": self.page.viewport_size, "account": self.account,
        }


class LoginManager:
    def __init__(self, bm: BrowserManager, store: Store):
        self.bm = bm
        self.store = store
        self.sessions: dict[str, LoginSession] = {}

    async def start(self, channel: str) -> LoginSession:
        await self.gc()
        mobile = channel != "xiaomi"
        ctx = await self.bm.context(None, mobile=mobile)
        page = await ctx.new_page()
        s = LoginSession(channel, ctx, page, mobile)
        page.on("framenavigated", lambda f: s.urls.append(f.url) if f == page.main_frame else None)
        url = {
            "jd": jd.LOGIN_URL,
            "taobao": "https://login.m.taobao.com/login.htm",
            "pdd": pdd.LOGIN_URL,
        }.get(channel) or await xiaomi.login_url()
        await page.goto(url, wait_until="domcontentloaded", timeout=45000)
        self.sessions[s.id] = s
        s.task = asyncio.create_task(self._watch(s))
        return s

    def get(self, sid: str) -> LoginSession | None:
        s = self.sessions.get(sid)
        if s:
            s.touched = time.time()
        return s

    async def close(self, sid: str) -> None:
        s = self.sessions.pop(sid, None)
        if s:
            if s.task and not s.task.done():
                s.task.cancel()
            await s.ctx.close()

    async def gc(self) -> None:
        for sid, s in list(self.sessions.items()):
            if time.time() - s.touched > IDLE_TIMEOUT or s.status in ("done", "error") and time.time() - s.touched > 120:
                await self.close(sid)

    async def screenshot(self, s: LoginSession) -> bytes:
        return await s.page.screenshot(type="jpeg", quality=70, scale="css")

    async def input(self, s: LoginSession, ev: dict[str, Any]) -> None:
        p = s.page
        kind = ev.get("type")
        if kind == "click":
            x, y = float(ev["x"]), float(ev["y"])
            if s.mobile:
                await p.touchscreen.tap(x, y)
            else:
                await p.mouse.click(x, y)
        elif kind == "text":
            await p.keyboard.type(str(ev.get("text", "")), delay=40)
        elif kind == "key":
            await p.keyboard.press(str(ev.get("key", "Enter")))
        elif kind == "scroll":
            await p.mouse.wheel(0, float(ev.get("dy", 300)))
        elif kind == "drag":
            pts = ev.get("points") or []
            if len(pts) >= 2:
                await p.mouse.move(pts[0][0], pts[0][1])
                await p.mouse.down()
                for x, y in pts[1:]:
                    await p.mouse.move(x, y, steps=2)
                await p.mouse.up()
        elif kind == "back":
            await p.go_back()
        elif kind == "reload":
            await p.reload()

    # ───────── 登录成功检测 ─────────

    async def _watch(self, s: LoginSession) -> None:
        try:
            while s.status == "waiting":
                await asyncio.sleep(2)
                cookies = await s.ctx.cookies()
                done = await getattr(self, f"_check_{s.channel}")(s, cookies)
                if done:
                    s.status = "done"
                    if not s.message.startswith("登录成功"):
                        s.message = f"登录成功：{s.account['label']}"
                    log.info("login ok channel=%s label=%s", s.channel, s.account["label"])
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001 - 失败原因展示给网页
            log.exception("login watch fail")
            s.status, s.message = "error", f"登录失败：{e}"

    async def _save_profile(self, s: LoginSession, key: str, label: str, extra: dict | None = None) -> None:
        acc = self.store.upsert_account(s.channel, key, label, {"loggedInAt": now_ms(), **(extra or {})})
        name = f"{s.channel}-{acc['id']}.json"
        await s.ctx.storage_state(path=str(self.store.profile_path(name)))
        self.store.update_account(s.channel, acc["id"], payload={"profile": name}, lastError="")
        s.account = self.store.public_account(s.channel, acc)

    async def _check_jd(self, s: LoginSession, cookies: list[dict]) -> bool:
        m = cookie_map(cookies, "jd.com")
        if not (m.get("pt_key") and m.get("pt_pin")):
            return False
        # 进一次订单中心，让 trade/api 域会话落地
        await s.page.goto(jd.ORDER_CENTER, wait_until="domcontentloaded", timeout=45000)
        await asyncio.sleep(3)
        key, label = jd.label_from_cookies(m)
        await self._save_profile(s, key, label)
        return True

    async def _check_taobao(self, s: LoginSession, cookies: list[dict]) -> bool:
        m = cookie_map(cookies, *taobao.DOMAINS)
        if not (m.get("cookie2") and (m.get("unb") or m.get("cookie1"))):
            return False
        await s.page.goto("https://h5.m.taobao.com/mlapp/olist.html", wait_until="domcontentloaded", timeout=45000)
        await asyncio.sleep(3)
        key, label = taobao.label_from_cookies(cookie_map(await s.ctx.cookies(), *taobao.DOMAINS))
        await self._save_profile(s, key, label)
        return True

    async def _check_pdd(self, s: LoginSession, cookies: list[dict]) -> bool:
        m = cookie_map(cookies, "yangkeduo.com", "pinduoduo.com")
        if not pdd.logged_in(m):
            return False
        await s.page.goto(pdd.ORDER_PAGE, wait_until="domcontentloaded", timeout=45000)
        await asyncio.sleep(3)
        key, label = pdd.label_from_cookies(cookie_map(await s.ctx.cookies(), "yangkeduo.com", "pinduoduo.com"))
        await self._save_profile(s, key, label)
        return True

    async def _check_xiaomi(self, s: LoginSession, cookies: list[dict]) -> bool:
        m = cookie_map(cookies, "xiaomi.com")
        if not (m.get("passToken") and m.get("userId") and m.get("deviceId")):
            return False
        cred = await xiaomi.mint(m["passToken"], m["userId"], m["deviceId"])
        phone = next((p for p in map(xiaomi.phone_from_url, s.urls) if p), "")
        existing = next((a for a in self.store.accounts("xiaomi") if a.get("key") == m["userId"]), None)
        ids = {} if existing and existing["payload"].get("oaid") else xiaomi.new_device_ids()
        phones = (existing or {}).get("payload", {}).get("phones") or ([phone] if phone else [])
        await self._save_profile(s, m["userId"], f"小米 {m['userId'][-4:]}", {
            **cred, **ids, "phones": phones,
            "passToken": m["passToken"], "userId": m["userId"], "deviceId": m["deviceId"],
        })
        if not phones:
            s.message = "登录成功：请在账号设置里填写收件手机号（小米只按手机号查件）"
        return True
