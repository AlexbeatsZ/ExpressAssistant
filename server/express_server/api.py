"""HTTP 接口：REST（网页/脚本）、MCP（AI 读取，Streamable HTTP 的 JSON 响应模式）与静态网页。

除 /api/health 与静态页面外，所有接口都要求 `Authorization: Bearer <token>`。
"""

from __future__ import annotations

import asyncio
import hmac
import json
import logging
from pathlib import Path
from typing import Any

from aiohttp import web

from . import __version__
from .login import LoginManager
from .parse import SECTION_LABELS, is_done, section_of, state_label
from .store import CHANNEL_LABELS, CHANNELS, Store
from .sync import SyncEngine

log = logging.getLogger("api")
WEB_DIR = Path(__file__).parent / "web"
SECTION_ORDER = ("pickup", "delivering", "shipped", "notshipped", "abnormal", "done")


def _json(data: Any, status: int = 200) -> web.Response:
    return web.json_response(data, status=status, dumps=lambda v: json.dumps(v, ensure_ascii=False))


class Api:
    def __init__(self, store: Store, engine: SyncEngine, logins: LoginManager):
        self.store = store
        self.engine = engine
        self.logins = logins

    # ───────── 视图 ─────────

    def view(self, it: dict, with_trace: bool = False) -> dict:
        g = self.store.goods(it["mailNo"]) or {}
        out = {
            "mailNo": it["mailNo"],
            "companyName": it.get("companyName", ""),
            "source": it.get("source", ""),
            "sourceLabel": CHANNEL_LABELS.get(it.get("source", ""), ""),
            "account": it.get("accountLabel", ""),
            "section": section_of(it),
            "sectionLabel": SECTION_LABELS[section_of(it)],
            "status": state_label(it),
            "done": is_done(it),
            "pickupCode": "" if is_done(it) else it.get("pickupCode", ""),
            "latestText": it.get("latestText", ""),
            "latestTime": it.get("latestTime", ""),
            "eta": it.get("eta", ""),
            "goodsName": g.get("name", ""),
            "goodsImage": g.get("imageUrl", ""),
            "goodsCount": g.get("count", ""),
            "phone": it.get("phone", ""),
        }
        if with_trace:
            tr = self.store.trace(it["mailNo"]) or {}
            out["trace"] = list(reversed(tr.get("points") or []))  # 最新在前
            out["traceFetchedAt"] = tr.get("fetchedAt")
        return out

    def listing(self, section: str = "", query: str = "") -> list[dict]:
        rows = [self.view(i) for i in self.store.items]
        if section and section != "all":
            if section == "active":
                rows = [r for r in rows if r["section"] not in ("done",)]
            else:
                rows = [r for r in rows if r["section"] == section]
        if query:
            q = query.lower()
            rows = [r for r in rows if any(q in str(r[k]).lower() for k in
                                           ("mailNo", "companyName", "goodsName", "latestText", "pickupCode", "account"))]
        # 按分区排序，同分区内最新在前
        out: list[dict] = []
        for s in SECTION_ORDER:
            out += sorted((r for r in rows if r["section"] == s), key=lambda r: r["latestTime"] or "", reverse=True)
        return out

    def summary_text(self) -> str:
        rows = self.listing()
        counts = {s: sum(1 for r in rows if r["section"] == s) for s in SECTION_ORDER}
        lines = [f"共 {len(rows)} 件：" + "，".join(f"{SECTION_LABELS[s]} {n}" for s, n in counts.items() if n)]
        pick = [r for r in rows if r["section"] == "pickup"]
        if pick:
            lines.append("待取件：")
            lines += [f"- {r['goodsName'] or r['companyName']}｜取件码 {r['pickupCode'] or '未识别'}｜{r['latestText'][:80]}" for r in pick]
        moving = [r for r in rows if r["section"] in ("delivering", "shipped", "notshipped")]
        if moving:
            lines.append("在途：")
            lines += [f"- {r['goodsName'] or r['companyName']}｜{r['status']}"
                      + (f"｜预计 {r['eta']}" if r["eta"] else "") + f"｜{r['latestText'][:60]}" for r in moving[:20]]
        ab = [r for r in rows if r["section"] == "abnormal"]
        if ab:
            lines.append("异常：")
            lines += [f"- {r['goodsName'] or r['companyName']} {r['mailNo']}｜{r['latestText'][:60]}" for r in ab]
        last = self.store.data.get("lastSync") or {}
        if last.get("message"):
            lines.append(f"上次同步：{last['message']}")
        return "\n".join(lines)

    # ───────── 中间件 ─────────

    @web.middleware
    async def auth(self, request: web.Request, handler):
        path = request.path
        if path.startswith("/api/") and path != "/api/health" or path == "/mcp":
            token = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
            if not hmac.compare_digest(token, self.store.config["token"]):
                return _json({"ok": False, "error": "unauthorized"}, 401)
        return await handler(request)

    # ───────── REST ─────────

    async def health(self, _r):
        last = self.store.data.get("lastSync") or {}
        return _json({"ok": True, "app": "express-assistant-server", "version": __version__,
                      "items": len(self.store.items), "lastSync": last.get("finishedAt")})

    async def express(self, r: web.Request):
        return _json(self.listing(r.query.get("section", ""), r.query.get("q", "")))

    async def express_one(self, r: web.Request):
        mail = r.match_info["mailNo"]
        it = self.store.item(mail)
        if not it:
            return _json({"ok": False, "error": "not found"}, 404)
        if r.query.get("refresh") == "1":
            it = await self.engine.refresh_one(mail) or it
        return _json(self.view(it, with_trace=True))

    async def hide(self, r: web.Request):
        self.store.hide(r.match_info["mailNo"])
        return _json({"ok": True})

    async def pickup(self, _r):
        return _json(self.listing("pickup"))

    async def summary(self, _r):
        return _json({"text": self.summary_text()})

    async def sync_get(self, _r):
        return _json(self.engine.status)

    async def sync_post(self, r: web.Request):
        body = await r.json() if r.can_read_body else {}
        chans = tuple(c for c in (body.get("channels") or CHANNELS) if c in CHANNELS)
        if self.engine.lock.locked():
            return _json({"ok": False, "message": "已有同步在进行", **self.engine.status}, 409)
        task = asyncio.create_task(self.engine.sync(chans))
        if body.get("wait"):
            return _json(await task)
        return _json({"ok": True, "message": "已开始同步"})

    async def accounts(self, _r):
        return _json({c: [self.store.public_account(c, a) for a in self.store.accounts(c)] for c in CHANNELS})

    async def account_patch(self, r: web.Request):
        c, aid = r.match_info["channel"], r.match_info["id"]
        body = await r.json()
        fields: dict[str, Any] = {}
        if "label" in body:
            fields["label"] = str(body["label"])[:40]
        if "enabled" in body:
            fields["enabled"] = bool(body["enabled"])
        if c == "xiaomi" and "phones" in body:
            phones = [p.strip() for p in body["phones"] if str(p).strip()] if isinstance(body["phones"], list) else \
                [p.strip() for p in str(body["phones"]).replace("，", ",").split(",") if p.strip()]
            fields["payload"] = {"phones": phones}
        acc = self.store.update_account(c, aid, **fields)
        return _json(self.store.public_account(c, acc) if acc else {"ok": False}, 200 if acc else 404)

    async def account_delete(self, r: web.Request):
        ok = self.store.remove_account(r.match_info["channel"], r.match_info["id"])
        return _json({"ok": ok}, 200 if ok else 404)

    async def config_get(self, _r):
        return _json({"syncIntervalMin": self.store.config.get("syncIntervalMin", 30)})

    async def config_patch(self, r: web.Request):
        body = await r.json()
        if "syncIntervalMin" in body:
            self.store.config["syncIntervalMin"] = max(0, int(body["syncIntervalMin"]))
            self.store.save_config()
        return await self.config_get(r)

    # ───────── 远程登录 ─────────

    async def login_start(self, r: web.Request):
        c = r.match_info["channel"]
        if c not in CHANNELS:
            return _json({"ok": False, "error": "unknown channel"}, 400)
        s = await self.logins.start(c)
        return _json(s.public())

    def _session(self, r: web.Request):
        s = self.logins.get(r.match_info["sid"])
        if not s:
            raise web.HTTPNotFound(text="login session expired")
        return s

    async def login_state(self, r: web.Request):
        return _json(self._session(r).public())

    async def login_screen(self, r: web.Request):
        img = await self.logins.screenshot(self._session(r))
        return web.Response(body=img, content_type="image/jpeg", headers={"Cache-Control": "no-store"})

    async def login_input(self, r: web.Request):
        s = self._session(r)
        await self.logins.input(s, await r.json())
        return _json(s.public())

    async def login_close(self, r: web.Request):
        await self.logins.close(r.match_info["sid"])
        return _json({"ok": True})

    # ───────── MCP ─────────

    TOOLS = [
        {"name": "pickup_codes", "description": "列出所有待取件的快递及取件码（最常用）：商品、快递公司、取件码、驿站/快递柜位置文字",
         "inputSchema": {"type": "object", "properties": {}}},
        {"name": "list_packages", "description": "列出快递，可按分区或关键字过滤。section: pickup 待取件 / delivering 派送中 / shipped 运输中 / "
                                                 "notshipped 未发货 / abnormal 异常 / done 已完成 / active 全部未完成 / all",
         "inputSchema": {"type": "object", "properties": {
             "section": {"type": "string", "description": "分区，默认 active"},
             "query": {"type": "string", "description": "关键字：商品名、单号、快递公司、取件码"}}}},
        {"name": "package_detail", "description": "查询某个快递的完整物流轨迹（最新在前）",
         "inputSchema": {"type": "object", "properties": {
             "mailNo": {"type": "string"},
             "refresh": {"type": "boolean", "description": "是否先在线刷新轨迹（较慢）"}}, "required": ["mailNo"]}},
        {"name": "summary", "description": "一段文字概括当前所有快递：待取件、在途、异常",
         "inputSchema": {"type": "object", "properties": {}}},
        {"name": "sync", "description": "立即从小米/京东/淘宝/拼多多同步一次（可能需要一两分钟）",
         "inputSchema": {"type": "object", "properties": {}}},
    ]

    async def _tool(self, name: str, args: dict) -> Any:
        if name == "pickup_codes":
            return [{k: r[k] for k in ("pickupCode", "goodsName", "companyName", "mailNo", "latestText", "latestTime", "source")}
                    for r in self.listing("pickup")]
        if name == "list_packages":
            return self.listing(args.get("section") or "active", args.get("query") or "")
        if name == "package_detail":
            mail = args.get("mailNo", "")
            it = self.store.item(mail)
            if not it:
                raise ValueError(f"找不到快递 {mail}")
            if args.get("refresh"):
                it = await self.engine.refresh_one(mail) or it
            return self.view(it, with_trace=True)
        if name == "summary":
            return self.summary_text()
        if name == "sync":
            res = await self.engine.sync()
            return res.get("message", "")
        raise ValueError(f"unknown tool: {name}")

    async def mcp(self, r: web.Request):
        try:
            req = await r.json()
        except ValueError:
            return _json({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}})
        if isinstance(req, list):
            out = [x for x in [await self._rpc(q) for q in req] if x is not None]
            return _json(out) if out else web.Response(status=202)
        res = await self._rpc(req)
        return _json(res) if res is not None else web.Response(status=202)

    async def _rpc(self, req: dict) -> dict | None:
        mid, method, params = req.get("id"), req.get("method", ""), req.get("params") or {}
        if mid is None:  # 通知（如 notifications/initialized）
            return None

        def ok(result):
            return {"jsonrpc": "2.0", "id": mid, "result": result}

        try:
            if method == "initialize":
                return ok({"protocolVersion": params.get("protocolVersion") or "2025-06-18",
                           "capabilities": {"tools": {}},
                           "serverInfo": {"name": "express-assistant", "version": __version__},
                           "instructions": "快递助手：用 pickup_codes 查取件码，list_packages / package_detail 查快递与轨迹。"})
            if method == "ping":
                return ok({})
            if method == "tools/list":
                return ok({"tools": self.TOOLS})
            if method == "tools/call":
                value = await self._tool(params.get("name", ""), params.get("arguments") or {})
                text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, indent=1)
                return ok({"content": [{"type": "text", "text": text}]})
            return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"method not found: {method}"}}
        except Exception as e:  # noqa: BLE001 - 以工具错误返回给调用方
            log.warning("mcp %s fail: %s", method, e)
            return ok({"content": [{"type": "text", "text": f"出错：{e}"}], "isError": True})

    # ───────── 静态 ─────────

    async def index(self, _r):
        return web.FileResponse(WEB_DIR / "index.html", headers={"Cache-Control": "no-cache"})

    def app(self) -> web.Application:
        a = web.Application(middlewares=[self.auth])
        a.add_routes([
            web.get("/", self.index),
            web.get("/api/health", self.health),
            web.get("/api/express", self.express),
            web.get("/api/express/{mailNo}", self.express_one),
            web.post("/api/express/{mailNo}/hide", self.hide),
            web.get("/api/pickup", self.pickup),
            web.get("/api/summary", self.summary),
            web.get("/api/sync", self.sync_get),
            web.post("/api/sync", self.sync_post),
            web.get("/api/accounts", self.accounts),
            web.patch("/api/accounts/{channel}/{id}", self.account_patch),
            web.delete("/api/accounts/{channel}/{id}", self.account_delete),
            web.get("/api/config", self.config_get),
            web.patch("/api/config", self.config_patch),
            web.post("/api/login/{channel}", self.login_start),
            web.get("/api/login/s/{sid}", self.login_state),
            web.get("/api/login/s/{sid}/screen", self.login_screen),
            web.post("/api/login/s/{sid}/input", self.login_input),
            web.delete("/api/login/s/{sid}", self.login_close),
            web.post("/mcp", self.mcp),
        ])
        a.router.add_static("/static/", WEB_DIR, show_index=False)
        return a
