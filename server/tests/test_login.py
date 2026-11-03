import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest
from aiohttp.test_utils import TestClient, TestServer
from playwright.async_api import Error as PlaywrightError

from express_server.api import Api
from express_server.browser import BrowserManager
from express_server.login import LoginManager, LoginSession, parse_cookie_text
from express_server.store import Store


def fake_context():
    page = MagicMock()
    page.url = "https://example.com/login"
    page.viewport_size = {"width": 400, "height": 860}
    page.is_closed.return_value = False
    page.goto = AsyncMock()
    page.bring_to_front = AsyncMock()
    ctx = MagicMock()
    ctx.new_page = AsyncMock(return_value=page)
    ctx.close = AsyncMock()
    ctx.cookies = AsyncMock(return_value=[])
    ctx.storage_state = AsyncMock(return_value={"cookies": [], "origins": []})
    return ctx, page


def test_login_defaults_depend_on_desktop_and_client(tmp_path, monkeypatch):
    manager = LoginManager(MagicMock(), Store(tmp_path))
    monkeypatch.setattr("express_server.login.desktop_available", lambda: True)
    assert manager.options(True) == {"defaultMode": "local", "localAvailable": True}
    assert manager.options(False)["defaultMode"] == "remote"
    monkeypatch.setattr("express_server.login.desktop_available", lambda: False)
    assert manager.options(True) == {"defaultMode": "remote", "localAvailable": False}


def test_browser_keeps_headed_login_separate_from_background_sync():
    async def run():
        hidden, visible = MagicMock(), MagicMock()
        for browser in (hidden, visible):
            browser.is_connected.return_value = True
            browser.close = AsyncMock()
        pw = MagicMock()
        pw.chromium.launch = AsyncMock(side_effect=[hidden, visible])
        pw.stop = AsyncMock()
        bm = BrowserManager(channel="chrome")
        bm._pw = pw
        assert await bm.browser() is hidden
        assert await bm.browser(False) is visible
        assert await bm.browser() is hidden
        assert await bm.browser(False) is visible
        assert [c.kwargs["headless"] for c in pw.chromium.launch.call_args_list] == [True, False]
        await bm.close()
        hidden.close.assert_awaited_once()
        visible.close.assert_awaited_once()

    asyncio.run(run())


@pytest.mark.parametrize("mode,headless", [("local", False), ("remote", True)])
def test_login_start_and_cancel(tmp_path, monkeypatch, mode, headless):
    async def run():
        ctx, page = fake_context()
        bm = MagicMock()
        bm.context = AsyncMock(return_value=ctx)
        manager = LoginManager(bm, Store(tmp_path))
        session = await manager.start("jd", mode)
        bm.context.assert_awaited_once_with(None, mobile=True, headless=headless)
        assert session.public()["mode"] == mode
        assert session.status == "waiting"
        assert page.bring_to_front.await_count == (1 if mode == "local" else 0)
        await manager.close(session.id)
        assert session.id not in manager.sessions
        ctx.close.assert_awaited_once()
        assert not manager.store.accounts("jd")

    monkeypatch.setattr("express_server.login.desktop_available", lambda: True)
    asyncio.run(run())


def test_login_without_desktop_rejects_local_before_launch(tmp_path, monkeypatch):
    monkeypatch.setattr("express_server.login.desktop_available", lambda: False)
    manager = LoginManager(MagicMock(), Store(tmp_path))
    with pytest.raises(ValueError, match="没有可用桌面"):
        asyncio.run(manager.start("jd", "local"))
    manager.bm.context.assert_not_called()


def test_failed_navigation_releases_context(tmp_path):
    async def run():
        ctx, page = fake_context()
        page.goto.side_effect = RuntimeError("navigation failed")
        bm = MagicMock()
        bm.context = AsyncMock(return_value=ctx)
        manager = LoginManager(bm, Store(tmp_path))
        with pytest.raises(RuntimeError, match="navigation failed"):
            await manager.start("jd")
        ctx.close.assert_awaited_once()
        assert not manager.sessions

    asyncio.run(run())


def test_success_saves_session_before_closing_window(tmp_path, monkeypatch):
    async def run():
        ctx, page = fake_context()
        ctx.storage_state.return_value = {"cookies": [{"name": "fake", "value": "test-only"}], "origins": []}
        manager = LoginManager(MagicMock(), Store(tmp_path))
        session = LoginSession("jd", ctx, page, True, "local")

        async def check(s, cookies):
            await manager._save_profile(s, "test-account", "测试账号")
            return True

        manager._check_jd = check
        await manager._watch(session)
        assert session.status == "done"
        acc = manager.store.accounts("jd")[0]
        assert manager.store.profile_path(acc["payload"]["profile"]).exists()
        ctx.close.assert_awaited_once()
        # 重新登录同一账号保持账号 ID，不增加账号。
        await manager._save_profile(session, "test-account", "测试账号")
        assert len(manager.store.accounts("jd")) == 1
        assert manager.store.accounts("jd")[0]["id"] == acc["id"]

    monkeypatch.setattr("express_server.login.asyncio.sleep", AsyncMock())
    asyncio.run(run())


def test_user_closing_window_does_not_save_account(tmp_path, monkeypatch):
    async def run():
        ctx, page = fake_context()
        page.is_closed.return_value = True
        manager = LoginManager(MagicMock(), Store(tmp_path))
        session = LoginSession("jd", ctx, page, True, "local")
        await manager._watch(session)
        assert session.status == "cancelled"
        assert not manager.store.accounts("jd")
        ctx.cookies.assert_not_awaited()
        ctx.close.assert_awaited_once()

    monkeypatch.setattr("express_server.login.asyncio.sleep", AsyncMock())
    asyncio.run(run())


def test_login_api_auth_and_local_mode_has_no_remote_controls(tmp_path, monkeypatch):
    async def run():
        ctx, page = fake_context()
        bm = MagicMock()
        bm.context = AsyncMock(return_value=ctx)
        store = Store(tmp_path)
        manager = LoginManager(bm, store)
        app = Api(store, SimpleNamespace(status={}), manager).app()
        async with TestClient(TestServer(app)) as client:
            assert (await client.get("/api/login/options")).status == 401
            headers = {"Authorization": "Bearer " + store.config["token"]}
            opts = await (await client.get("/api/login/options", headers=headers)).json()
            assert opts["defaultMode"] == "local"
            response = await client.post("/api/login/jd", json={}, headers=headers)
            assert response.status == 200
            session = await response.json()
            assert session["mode"] == "local"
            path = "/api/login/s/" + session["id"]
            assert (await client.get(path + "/screen", headers=headers)).status == 409
            assert (await client.post(path + "/input", json={"type": "text", "text": "test"}, headers=headers)).status == 409
            assert (await client.delete(path, headers=headers)).status == 200
            invalid = await client.post("/api/login/jd", json={"mode": "invalid"}, headers=headers)
            assert invalid.status == 400
        await manager.shutdown()

    monkeypatch.setattr("express_server.login.desktop_available", lambda: True)
    asyncio.run(run())


def test_cookie_json_requires_a_list():
    with pytest.raises(ValueError, match="数组"):
        parse_cookie_text('{"cookies": "not a list"}', ("jd.com",))


@pytest.mark.parametrize("error,accepted", [("net::ERR_ABORTED", True), ("net::ERR_CONNECTION_REFUSED", False)])
def test_post_login_redirect_interruption(tmp_path, monkeypatch, error, accepted):
    async def run():
        ctx, page = fake_context()
        page.goto.side_effect = PlaywrightError(error)
        manager = LoginManager(MagicMock(), Store(tmp_path))
        session = LoginSession("jd", ctx, page, True)
        cookies = [{"name": "pt_key", "value": "test-only", "domain": ".jd.com"},
                   {"name": "pt_pin", "value": "test-account", "domain": ".jd.com"}]
        if accepted:
            assert await manager._check_jd(session, cookies)
            assert len(manager.store.accounts("jd")) == 1
        else:
            with pytest.raises(PlaywrightError):
                await manager._check_jd(session, cookies)
            assert not manager.store.accounts("jd")

    monkeypatch.setattr("express_server.login.asyncio.sleep", AsyncMock())
    asyncio.run(run())
