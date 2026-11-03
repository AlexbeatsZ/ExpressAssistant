import json

from express_server.channels import jd, pdd, taobao, xiaomi
from express_server.parse import eta_from, pickup_code_from, pickup_code_from_points, section_of


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


def test_eta():
    assert eta_from("预计11月5日送达") == "11月5日送达"
    assert eta_from("预计明天送达") == "明天送达"


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
    assert list(items) == ["111"] and items["111"]["state"] == 5
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
