import json

from express_server.channels import jd, pdd, taobao, xiaomi
from express_server.parse import eta_from, pickup_code_from, pickup_code_from_points, section_of, state_label


def test_pickup_code():
    assert pickup_code_from("您的快件已到达菜鸟驿站，请凭取件码 8-2-3017 取件") == "8-2-3017"
    assert pickup_code_from("取货码：A1234") == "A1234"
    assert pickup_code_from("快件已签收") == ""
    pts = [{"context": "已揽收"}, {"context": "已放入丰巢柜，取件码 553201"}]
    assert pickup_code_from_points(pts) == "553201"


def test_section():
    base = {"state": 0, "stateNum": 104}
    assert section_of({**base, "pickupCode": "12"}) == "pickup"
    assert section_of({**base, "latestText": "您的快件已到达妈妈驿站"}) == "pickup"
    assert section_of({"state": 3, "stateNum": 107, "pickupCode": "12"}) == "done"
    assert section_of({"state": 5, "stateNum": 105}) == "delivering"
    assert section_of({"state": 1, "stateNum": 103}) == "notshipped"
    assert section_of({"state": 4}) == "abnormal"


def test_pdd_completed_trace_overrides_marketing_status():
    base = {"source": "pdd", "state": 5, "stateNum": 105, "stateName": "拼过2次", "pickupCode": "1-2-3"}
    for text in ("已签收，签收人凭取货码签收。", "您的快件已取走【菜鸟驿站】"):
        item = {**base, "latestText": text}
        assert section_of(item) == "done"
        assert state_label(item) == "已签收"
    assert section_of({**base, "latestText": "您的快件已由菜鸟驿站代收，请凭取货码取件"}) == "pickup"
    assert section_of({**base, "latestText": "已签收，菜鸟驿站代收，请及时取件"}) == "pickup"


def test_eta():
    assert eta_from("预计11月5日送达") == "11月5日送达"
    assert eta_from("预计明天送达") == "明天送达"


def test_receipt_fields_override_marketing_and_unknown_is_not_dispatch():
    for order in ({"order_status_prompt": "拼过2次", "shipping_status": 2},
                  {"order_status_prompt": "拼过6次", "receive_time": 1780000000}):
        assert pdd.resolve_status(order) == (3, 107, "已收货", True)
    assert pdd.resolve_status({"order_status_prompt": "已提货"}) == (3, 107, "已提货", True)
    assert pdd.resolve_status({"order_status_prompt": "待收货", "shipping_status": 1})[:2] == (0, 104)
    assert pdd.resolve_status({"order_status_prompt": "拼过2次"})[:2] == (-1, 0)
    assert pdd.resolve_status({"order_status_prompt": "快递正在派送"})[:2] == (5, 105)
    assert section_of({"state": -1, "stateNum": 0}) == "unknown"
    assert section_of({"source": "pdd", "stateName": "已提货"}) == "done"
    assert section_of({"source": "jd", "stateName": "处理成功"}) == "done"
    assert section_of({"source": "pdd", "stateName": "拼过2次", "receiptConfirmed": True}) == "done"


def test_completed_receipt_evidence_survives_sync(tmp_path):
    import asyncio
    from express_server.store import Store
    from express_server.sync import SyncEngine

    st = Store(tmp_path)
    st.data["items"] = [{"mailNo": "sample", "source": "pdd", "state": 3, "stateNum": 107,
                         "receiptConfirmed": True, "pickupCode": "old"}]
    st.data["accounts"]["pdd"] = [{"id": "p", "enabled": True, "payload": {}}]
    eng = SyncEngine(st, None)

    async def fresh(_):
        return [{"mailNo": "sample", "source": "pdd", "state": -1, "stateNum": 0,
                 "stateName": "拼过2次", "latestText": "拼过2次", "pickupCode": ""}], {}, {}, ""

    eng._channel = fresh
    asyncio.run(eng.sync(("pdd",)))
    assert st.items[0]["state"] == 3 and st.items[0]["stateNum"] == 107
    assert st.items[0]["pickupCode"] == "" and section_of(st.items[0]) == "done"


def test_partial_jd_page_keeps_older_completed_orders(tmp_path):
    import asyncio
    from express_server.store import Store
    from express_server.sync import SyncEngine

    st = Store(tmp_path)
    st.data["items"] = [{"mailNo": n, "source": "jd", "state": 3, "stateNum": 107} for n in ("recent", "older")]
    st.data["accounts"]["jd"] = [{"id": "j", "enabled": True, "payload": {}}]
    eng = SyncEngine(st, None)

    async def fresh(_):
        return [{"mailNo": "recent", "source": "jd", "state": 3, "stateNum": 107}], {}, {}, ""

    eng._channel = fresh
    asyncio.run(eng.sync(("jd",)))
    assert {i["mailNo"] for i in st.items} == {"recent", "older"}


def test_xiaomi_crypto_roundtrip():
    s = json.dumps({"a": "中文"}, ensure_ascii=False)
    assert xiaomi._aes_dec(xiaomi._aes_enc(s)) == s
    assert xiaomi.phone_from_url("https://x/?_user=13800000000&a=1") == "13800000000"


def test_jd_convert_and_trace():
    body = 'cb({"body":{"totalNum":2,"orderList":[{"orderId":"111","orderStatusInfo":{"orderStatusName":"等待收货"},'\
           '"progressInfo":{"content":"快件已到达驿站，取件码 3-1-22","tip":"2026-11-03 10:00"},'\
           '"wareInfoList":[{"wareName":"小米手环","imageUrl":"http://i","num":1,"skuId":"9"}]},'\
           '{"orderId":"222","orderStatusInfo":{"orderStatusName":"已取消"}}]}})'
    items, goods = jd._convert([jd._jsonp(body)], {"id": "a"})
    assert list(items) == ["111"] and items["111"]["state"] == 0
    assert goods["111"]["name"] == "小米手环"
    text = "订单跟踪\n您的快件已签收\n2026-11-03 12:00:00\n快件到达【上海转运中心】\n2026-11-02 08:00:00"
    pts = jd.parse_trace_text(text)
    assert [p["time"] for p in pts] == ["2026-11-02 08:00:00", "2026-11-03 12:00:00"]


def test_pdd_convert_and_dom():
    body = json.dumps({"orders": [{"order_sn": "A1", "order_status_prompt": "待收货", "tracking_no": "YT123",
                                    "logistics_company_name": "圆通", "order_goods": [{"goods_name": "纸巾", "thumb_url": "//img"}]},
                                   {"order_sn": "A2", "order_status_prompt": "已取消"}]})
    orders, goods = pdd._convert([("https://mobile.yangkeduo.com/proxy/api/api/aristotle/order_list_v4", body)], {"id": "p"})
    assert list(orders) == ["A1"] and orders["A1"]["mailNo"] == "YT123"
    assert goods["YT123"]["imageUrl"] == "https://img"
    dom = "查看物流\n已签收 2026-11-03 09:00\n您的快递已由妈妈驿站代收，取件码 6-5-1001\n运输中 2026-11-02 08:00\n快件已发往上海"
    pts = pdd.parse_dom_points(dom)
    assert pts[-1]["time"] == "2026-11-03 09:00" and "6-5-1001" in pts[-1]["context"]


def test_taobao_ssr():
    data = {"result": {"data": {"newLogistics": {"fields": {
        "mailNo": "SF1", "logisticCompany": {"name": "顺丰"},
        "multiStage": [{"title": "待取件", "subtitle": "11-03 10:00", "labelDesc": {"richContent": [{"text": "已到菜鸟驿站，取件码 1-2-3"}]}},
                       {"title": "运输中", "subtitle": "11-02 10:00", "labelDesc": "已发货"}]}}}}}
    html = "<script>window['__ICE_SUSPENSE_LOADER__']['undefined'] = " + json.dumps(data, ensure_ascii=False) + ";</script>"
    parcel, pts = taobao._parse_ssr(html, "O1")
    assert parcel["mailNo"] == "SF1" and parcel["stateLabel"] == "待取件"
    assert pts[-1]["context"].endswith("1-2-3")
    item = taobao._to_item(parcel, {"id": "t"})
    assert item["queryChannel"] == "O1"
    assert taobao.label_from_cookies({"unb": "123", "_nk_": "tb%5Cu5c0f%5Cu660e"}) == ("123", "tb小明")


def test_parse_cookie_text():
    from express_server.login import parse_cookie_text

    hdr = parse_cookie_text("Cookie: pt_key=AAA; pt_pin=jd_x;  other=1", ("jd.com",))
    assert {c["name"] for c in hdr} == {"pt_key", "pt_pin", "other"} and hdr[0]["domain"] == ".jd.com"
    js = parse_cookie_text(json.dumps([
        {"domain": ".taobao.com", "name": "cookie2", "value": "c", "expirationDate": 1893456000, "httpOnly": True, "sameSite": "no_restriction"},
        {"domain": ".alipay.com", "name": "x", "value": "y"},
    ]), ("taobao.com", "tmall.com"))
    assert len(js) == 1 and js[0]["sameSite"] == "None" and js[0]["httpOnly"] and js[0]["expires"] == 1893456000


def test_taobao_pickup_scan():
    data = {"stationCard": {"fields": {"stationName": "复旦南区驿站", "pickUpCode": "8-3-2201"}}}
    assert taobao._scan_pickup(data) == "8-3-2201"
    assert taobao._scan_pickup({"facilityCode": "769901", "orderCode": "LP0084"}) == ""
    assert taobao._scan_pickup({"pickUpCode": "needSms", "station": {"pickUpCode": "8-3-2201"}}) == "8-3-2201"
    assert taobao._scan_pickup({"pickUpCode": "null"}) == ""


def test_cainiao_pickup_rules():
    from express_server.channels import cainiao

    pkg = {"mailNo": "YT1", "logisticsStatus": "STA_INBOUND", "logisticsStatusDesc": "待取件", "partnerName": "圆通",
           "packageStation": {"authCode": "8-2-3017", "stationName": "复旦南区驿站"}}
    assert cainiao.pickup_code(pkg) == "8-2-3017"
    sms = {"packageStation": {"authCode": "needSms"}, "feature": '{"stationOrderPickUpCode":"5-1-22"}'}
    assert cainiao.pickup_code(sms) == "5-1-22"
    assert cainiao.pickup_code({"packageStation": {"authCode": "needSms"}}) == ""
    assert cainiao.pickup_code({"packageStation": {"authCode": "A1"}, "feature": '{"stationOrderPickUpCode":"B2"}'}) == "A1"
    it = cainiao.to_item(pkg, {"id": "t", "label": "tb"})
    assert it["pickupCode"] == "8-2-3017" and it["station"] == "复旦南区驿站" and it["state"] == 5
    signed = cainiao.to_item({**pkg, "logisticsStatus": "SIGN"}, {"id": "t"})
    assert signed["pickupCode"] == "" and signed["state"] == 3


def test_merge_cainiao_enriches_and_adds():
    from express_server.sync import SyncEngine

    eng = SyncEngine.__new__(SyncEngine)
    eng._cainiao = [
        {"mailNo": "SF1", "pickupCode": "6-1", "station": "驿站A", "state": 5, "stateNum": 105, "source": "cainiao"},
        {"mailNo": "JT9", "pickupCode": "7-2", "station": "驿站A", "state": 5, "stateNum": 105, "source": "cainiao"},
        {"mailNo": "OLD", "pickupCode": "", "state": 3, "stateNum": 107, "source": "cainiao"},
    ]
    merged = {"SF1": {"mailNo": "SF1", "state": 0, "stateNum": 104, "source": "taobao"}}
    eng._merge_cainiao(merged, {}, ("taobao",), True)
    assert merged["SF1"]["pickupCode"] == "6-1" and merged["SF1"]["station"] == "驿站A"
    assert merged["JT9"]["source"] == "cainiao" and "OLD" not in merged
