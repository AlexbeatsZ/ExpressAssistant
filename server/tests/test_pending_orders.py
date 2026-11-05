import asyncio
import json

import pytest

from express_server.channels import pdd, taobao
from express_server.sync import SyncEngine, merge_order_history, order_key
from express_server.store import Store


def paid_order():
    return {"orderId": "order-1", "tradeStatus": "WAIT_SELLER_SEND_GOODS", "statusText": "买家已付款", "goodsName": "商品"}


def test_taobao_paid_unshipped_does_not_require_logistics():
    order = paid_order()
    item = taobao._pending_item(order, {"id": "buyer"})
    assert taobao._paid_unshipped(order)
    assert item["orderOnly"] and item["stateNum"] == 101 and item["queryChannel"] == "order-1"
    for state in ("WAIT_BUYER_PAY", "TRADE_CLOSED", "WAIT_BUYER_CONFIRM_GOODS"):
        assert not taobao._paid_unshipped({**order, "tradeStatus": state})


def test_pending_transitions_keep_history_without_duplicates():
    pending = taobao._pending_item(paid_order(), {"id": "buyer"})
    other = {**pending, "mailNo": "other", "queryChannel": "other"}
    shipped = {**pending, "mailNo": "YT123", "orderOnly": False, "state": 0, "stateNum": 104}
    assert {i["mailNo"] for i in merge_order_history([shipped], [pending, other])} == {"YT123", "other"}
    assert merge_order_history([], [pending]) == [pending]
    assert not merge_order_history([], [pending], {order_key(pending)})


def test_taobao_json_and_jsonp_and_session_errors():
    raw = json.dumps({"ret": ["SUCCESS::调用成功"], "data": {"result": json.dumps({"mainOrders": [
        {"id": "order-1", "extra": {"tradeStatus": "WAIT_SELLER_SEND_GOODS"}, "statusInfo": {"text": "买家已付款"}}
    ]})}})
    assert taobao._paid_unshipped(taobao._parse_bought(raw)[0])
    assert taobao._paid_unshipped(taobao._parse_bought(f"cb({raw})")[0])
    with pytest.raises(RuntimeError, match="登录已失效"):
        taobao._parse_bought('{"ret":["FAIL_SYS_SESSION_EXPIRED::登录过期"],"data":{}}')


def test_pdd_initial_html_orders_include_paid_unshipped_goods():
    raw = json.dumps({"orders": {"0": [{"order_sn": "pending-1", "order_status_prompt": "拼单成功，待发货",
        "shipping_status": 0, "pay_status": 2, "receive_time": 0,
        "order_goods": [{"goods_name": "手机壳", "thumb_url": "//img"}]}]}})
    rows, goods = pdd._convert([(pdd.INITIAL_ORDERS, raw)], {"id": "buyer"})
    assert rows["pending-1"]["state"] == 1 and rows["pending-1"]["orderOnly"]
    assert not rows["pending-1"]["receiptConfirmed"]
    assert goods["pending-1"]["name"] == "手机壳"


def test_sync_replaces_pending_order_and_keeps_first_seen(tmp_path):
    store = Store(tmp_path)
    pending = {**taobao._pending_item(paid_order(), {"id": "buyer"}), "firstSeen": 123}
    store.data["items"] = [pending]
    store.data["accounts"]["taobao"] = [{"id": "buyer", "enabled": True, "payload": {}}]
    engine = SyncEngine(store, None)

    async def fresh(_):
        return [{**pending, "mailNo": "YT123", "orderOnly": False, "state": 0, "stateNum": 104}], {}, {}, ""

    engine._channel = fresh
    asyncio.run(engine.sync(("taobao",)))
    assert len(store.items) == 1 and store.items[0]["mailNo"] == "YT123"
    assert store.items[0]["firstSeen"] == 123
