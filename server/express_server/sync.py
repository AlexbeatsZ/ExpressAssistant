"""四源同步与合并：移植自 App SyncEngine，并在服务器端补抓在途件轨迹以提取取件码。"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any

from .browser import BrowserManager
from .channels import cainiao, jd, pdd, taobao, xiaomi
from .parse import eta_from, is_done, now_ms, pickup_code_from, pickup_code_from_points
from .store import CHANNEL_LABELS, CHANNELS, Store

log = logging.getLogger("sync")

PRIORITY = ("xiaomi", "jd", "taobao", "pdd")
TRACE_TTL_MS = 30 * 60 * 1000
TRACE_LIMIT = 25  # 每个账号每次同步最多补抓多少个在途件轨迹（浏览器渠道较慢）


class SyncEngine:
    def __init__(self, store: Store, bm: BrowserManager):
        self.store = store
        self.bm = bm
        self.lock = asyncio.Lock()
        self.status: dict[str, Any] = {"running": False, **store.data.get("lastSync", {})}
        self._cainiao: list[dict] | None = None
        self._cainiao_error = ""

    def _cookies(self, acc: dict) -> list[dict]:
        name = acc["payload"].get("profile")
        if not name:
            return []
        p = self.store.profile_path(name)
        try:
            return json.loads(p.read_text(encoding="utf-8")).get("cookies", [])
        except (OSError, ValueError):
            return []

    # ───────── 单渠道（逐账号；一个账号失败不影响其它账号） ─────────

    async def _channel(self, channel: str) -> tuple[list[dict] | None, dict, dict, str]:
        items: list[dict] = []
        goods: dict = {}
        traces: dict = {}
        errors: list[str] = []
        for acc in self.store.accounts(channel, enabled_only=True):
            try:
                if channel == "xiaomi":
                    try:
                        got, tr = await xiaomi.fetch(acc)
                    except RuntimeError:
                        p = acc["payload"]
                        if not p.get("passToken"):
                            raise
                        # serviceToken 过期：用 passToken 重新换一次再试
                        cred = await xiaomi.mint(p["passToken"], p["userId"], p["deviceId"])
                        acc = self.store.update_account(channel, acc["id"], payload=cred) or acc
                        got, tr = await xiaomi.fetch(acc)
                    items += got
                    traces.update(tr)
                elif channel == "taobao":
                    got, g, tr = await taobao.fetch(acc, self._cookies(acc))
                    items += got
                    goods.update(g)
                    traces.update(tr)
                    # 菜鸟「我的包裹」：按收件手机号覆盖所有进驿站的件，是驿站取件码的来源；失败不影响淘宝订单
                    try:
                        self._cainiao = (self._cainiao or []) + await cainiao.fetch(acc, self._cookies(acc))
                    except Exception as e:  # noqa: BLE001 - 记录后保留旧的菜鸟数据
                        log.warning("cainiao %s fail: %s", acc.get("label"), e)
                        self._cainiao_error = str(e)[:200]
                else:
                    profile = self.store.profile_path(acc["payload"].get("profile") or f"{channel}-{acc['id']}.json")
                    mod = jd if channel == "jd" else pdd
                    got, g = await mod.fetch(self.bm, acc, profile)
                    items += got
                    goods.update(g)
                self.store.update_account(channel, acc["id"], lastError="")
            except Exception as e:  # noqa: BLE001 - 记录到账号，继续下一个
                log.warning("%s account %s fail: %s", channel, acc.get("label"), e)
                self.store.update_account(channel, acc["id"], lastError=str(e)[:200])
                errors.append(f"{acc.get('label')}: {e}")
        err = "；".join(errors)
        return (None if not items and errors else items), goods, traces, err

    # ───────── 全量同步 ─────────

    async def sync(self, channels: tuple[str, ...] = CHANNELS) -> dict:
        if self.lock.locked():
            return {"ok": False, "message": "已有同步在进行"}
        async with self.lock:
            started = now_ms()
            self.status.update(running=True, startedAt=started, message="同步中…")
            try:
                return await self._sync(channels, started)
            finally:
                self.status["running"] = False

    async def _sync(self, channels: tuple[str, ...], started: int) -> dict:
        st = self.store
        old = {i["mailNo"]: i for i in st.items}
        self._cainiao, self._cainiao_error = None, ""
        active = [c for c in channels if st.accounts(c, enabled_only=True)]
        results = dict(zip(active, await asyncio.gather(*(self._channel(c) for c in active))))
        statuses = []
        for c in CHANNELS:
            if c in results:
                got, _, _, err = results[c]
                statuses.append({"channel": c, "label": CHANNEL_LABELS[c], "count": len(got or []), "error": err})

        def fresh_or_old(c: str) -> list[dict]:
            # 防误清：渠道失败或成功但 0 件时保留旧数据；本次未同步的渠道原样保留；未启用的渠道不残留
            if c not in channels:
                return [i for i in old.values() if i.get("source") == c]
            if c not in results:
                return []
            got = results[c][0]
            if got:
                return got + [i for i in old.values() if i.get("source") == c]
            return [i for i in old.values() if i.get("source") == c]

        merged: dict[str, dict] = {}
        for c in PRIORITY:
            lst = fresh_or_old(c)
            if c == "pdd":
                # 拼多多增量合并：本次与历史取并集
                lst = lst + [i for i in old.values() if i.get("source") == "pdd" and c in results]
            for it in lst:
                merged.setdefault(it["mailNo"], it)
        self._merge_cainiao(merged, old, channels, "taobao" in results)
        if self._cainiao is not None or self._cainiao_error:
            codes = sum(1 for i in self._cainiao or [] if i.get("pickupCode"))
            statuses.append({"channel": "cainiao", "label": "菜鸟驿站", "count": len(self._cainiao or []),
                             "error": self._cainiao_error, "codes": codes})
        for c, (_, goods, traces, _) in results.items():
            st.merge_goods(goods)
            for mail, pts in traces.items():
                if mail in merged:
                    st.save_trace(mail, pts, is_done(merged[mail]), now_ms())

        hidden = set(st.data["hidden"])
        final = []
        for mail, it in merged.items():
            if mail in hidden:
                continue
            prev = old.get(mail)
            if prev:
                if it.get("source") == "pdd" and pdd.prompt_only(it):
                    it["latestText"] = prev.get("latestText") or it["latestText"]
                    it["latestTime"] = prev.get("latestTime") or it.get("latestTime", "")
                it["pickupCode"] = it.get("pickupCode") or prev.get("pickupCode", "")
                it["receiptConfirmed"] = bool(it.get("receiptConfirmed") or prev.get("receiptConfirmed") or is_done(prev))
                it["firstSeen"] = prev.get("firstSeen") or started
            else:
                it["firstSeen"] = started
            if is_done(it):
                it["state"], it["stateNum"], it["pickupCode"] = 3, 107, ""
            final.append(it)
        st.data["items"] = final
        await self._refresh_traces(final)
        for it in final:
            self._apply_trace(it)
            if is_done(it):
                it["state"], it["stateNum"], it["pickupCode"] = 3, 107, ""
        msg = "；".join(f"{s['label']} {s['count']} 件" + (f"（{s['error']}）" if s["error"] else "") for s in statuses) or "没有启用的账号"
        st.data["lastSync"] = {"startedAt": started, "finishedAt": now_ms(), "statuses": statuses, "message": msg, "total": len(final)}
        st.save()
        self.status.update(st.data["lastSync"])
        log.info("sync done total=%d %s", len(final), msg)
        return {"ok": True, **st.data["lastSync"]}

    def _merge_cainiao(self, merged: dict, old: dict, channels: tuple[str, ...], taobao_ran: bool) -> None:
        """菜鸟包裹：已有的件补取件码与驿站；其它渠道没有的未完成件单独加入（来源「菜鸟」）。"""
        old_cn = [i for i in old.values() if i.get("source") == "cainiao"]
        if taobao_ran:
            pkgs = self._cainiao if self._cainiao is not None else old_cn
        else:
            pkgs = old_cn if "taobao" not in channels else []
        for pkg in pkgs:
            it = merged.get(pkg["mailNo"])
            if it is not None and it.get("source") != "cainiao":
                if pkg.get("station"):
                    it["station"] = pkg["station"]
                if pkg.get("pickupCode") and not is_done(it):
                    it["pickupCode"] = pkg["pickupCode"]
            elif not is_done(pkg) or pkg["mailNo"] in old:
                merged.setdefault(pkg["mailNo"], pkg)

    # ───────── 轨迹：在途件补抓（取件码多在最后一条轨迹里） ─────────

    def _apply_trace(self, it: dict) -> None:
        tr = self.store.trace(it["mailNo"])
        pts = (tr or {}).get("points") or []
        if pts:
            last = pts[-1]
            if it.get("source") in ("pdd", "jd") and (pdd.prompt_only(it) or not it.get("latestTime")):
                it["latestText"] = last["context"][:220]
                it["latestTime"] = last.get("time") or it.get("latestTime", "")
            if not it.get("eta"):
                it["eta"] = eta_from(last["context"])
        if not is_done(it):
            code = pickup_code_from(it.get("latestText")) or pickup_code_from_points(pts)
            if code:
                it["pickupCode"] = code

    def _trace_stale(self, it: dict) -> bool:
        tr = self.store.trace(it["mailNo"])
        if not tr:
            return True
        if tr.get("done"):
            return False
        return now_ms() - tr.get("fetchedAt", 0) > TRACE_TTL_MS

    async def _refresh_traces(self, items: list[dict]) -> None:
        jobs: dict[tuple[str, str], list[dict]] = {}
        for it in items:
            if it.get("source") in ("jd", "pdd") and not is_done(it) and self._trace_stale(it):
                jobs.setdefault((it["source"], it.get("accountId", "")), []).append(it)
        await asyncio.gather(*(self._traces_for(c, aid, lst[:TRACE_LIMIT]) for (c, aid), lst in jobs.items()))

    async def _traces_for(self, channel: str, account_id: str, items: list[dict]) -> None:
        acc = self.store.account(channel, account_id)
        if not acc or not acc["payload"].get("profile"):
            return
        ctx = await self.bm.context(self.store.profile_path(acc["payload"]["profile"]))
        try:
            page = await ctx.new_page()
            mod = jd if channel == "jd" else pdd
            for it in items:
                try:
                    pts = await mod.fetch_trace(page, it)
                    if pts:
                        self.store.save_trace(it["mailNo"], pts, is_done(it), now_ms())
                except Exception as e:  # noqa: BLE001 - 单件失败继续
                    log.warning("trace %s %s fail: %s", channel, it["mailNo"], e)
                    if "登录已失效" in str(e):
                        break
        finally:
            await ctx.close()

    async def refresh_one(self, mail_no: str) -> dict | None:
        """详情页手动刷新单件轨迹（已完成件也可以抓）。"""
        it = self.store.item(mail_no)
        if not it:
            return None
        if it.get("source") in ("jd", "pdd"):
            await self._traces_for(it["source"], it.get("accountId", ""), [it])
        elif it.get("source") in ("xiaomi", "taobao"):
            await self.sync((it["source"],))
            it = self.store.item(mail_no) or it
        self._apply_trace(it)
        self.store.save()
        return it


async def scheduler(engine: SyncEngine) -> None:
    """按配置间隔定时同步；间隔 0 = 关闭。"""
    await asyncio.sleep(20)
    while True:
        interval = int(engine.store.config.get("syncIntervalMin") or 0)
        if interval > 0:
            last = engine.status.get("finishedAt") or 0
            if (time.time() * 1000 - last) >= interval * 60 * 1000:
                try:
                    await engine.sync()
                except Exception:  # noqa: BLE001 - 定时任务不能挂掉
                    log.exception("scheduled sync fail")
        await asyncio.sleep(60)
