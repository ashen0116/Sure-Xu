package com.surexu.sesame.model.task.consumeGold;

import java.util.UUID;

import com.surexu.sesame.hook.ApplicationHook;

public class ConsumeGoldRpcCall {
    // private static final String VERSION = "20230522";
    /** 本模块所有请求只发一次：账号被限时不重试，避免短时间堆出请求风暴 */
    private static final int TRY_COUNT = 1;
    private static final int RETRY_INTERVAL = -1;

    private static String request(String method, String data) {
        return ApplicationHook.requestString(method, data, TRY_COUNT, RETRY_INTERVAL);
    }

    private static String getRequestId() {
        StringBuilder sb = new StringBuilder();
        for (String str : UUID.randomUUID().toString().split("-")) {
            sb.append(str.substring(str.length() / 2));
        }
        return sb.toString().toUpperCase();
    }

    public static String taskV2Index(String taskSceneCode) {
        return request("alipay.mobile.ipsponsorprod.consume.gold.taskV2.index",
                "[{\"alipayAppVersion\":\"10.5.36.8100\",\"appClient\":\"Android\",\"appSource\":\"consumeGold\",\"cacheMap\":{},\"clientTraceId\":\""
                        + UUID.randomUUID().toString()
                        + "\",\"clientVersion\":\"6.3.0\",\"favoriteStatus\":\"LowVersion\",\"taskSceneCode\":\""
                        + taskSceneCode + "\",\"userType\":\"new\"}]");
    }

    public static String consumeGoldIndex() {
        return request("alipay.mobile.ipsponsorprod.consume.gold.index",
                "[{\"appSource\":\"consumeGold\",\"cacheMap\":{},\"clientTraceId\":\""
                        + UUID.randomUUID().toString()
                        + "\",\"clientVersion\":\"6.3.0\",\"favoriteStatus\":\"LowVersion\"}]");
    }

    public static String signinCalendar() {
        return request("alipay.mobile.ipsponsorprod.consume.gold.task.signin.calendar",
                "[{}]");
    }

    public static String openBoxAward() {
        return request("alipay.mobile.ipsponsorprod.consume.gold.task.openBoxAward",
                "[{\"actionAwardDetails\":[{\"actionType\":\"date_sign_start\"}],\"bizType\":\"CONSUME_GOLD\",\"boxType\":\"CONSUME_GOLD_SIGN_DATE\",\"clientVersion\":\"6.3.0\",\"timeScaleType\":0,\"userType\":\"new\"}]");
    }

    public static String taskV2TriggerSignUp(String taskId) {
        return request("alipay.mobile.ipsponsorprod.consume.gold.taskV2.trigger",
                "[{\"taskId\":\"" + taskId + "\",\"triggerAction\":\"SIGN_UP\"}]");
    }

    public static String taskV2TriggerSend(String taskId) {
        return request("alipay.mobile.ipsponsorprod.consume.gold.taskV2.trigger",
                "[{\"taskId\":\"" + taskId + "\",\"triggerAction\":\"SEND\"}]");
    }

    public static String taskV2TriggerReceive(String taskId) {
        return request("alipay.mobile.ipsponsorprod.consume.gold.taskV2.trigger",
                "[{\"taskId\":\"" + taskId + "\",\"triggerAction\":\"RECEIVE\"}]");
    }

    public static String promoTrigger() {
        return request("alipay.mobile.ipsponsorprod.consume.gold.index.promo.trigger",
                "[{\"appSource\":\"consumeGold\",\"cacheMap\":{},\"clientTraceId\":\""
                        + UUID.randomUUID().toString()
                        + "\",\"clientVersion\":\"6.3.0\",\"favoriteStatus\":\"UnFavorite\",\"requestId\":\""
                        + getRequestId() + "\"}]");
    }

    public static String advertisement(String outBizNo) {
        return request("alipay.mobile.ipsponsorprod.consume.gold.send.promo.advertisement",
                "[{\"appSource\":\"consumeGold\",\"cacheMap\":{},\"clientTraceId\":\""
                        + UUID.randomUUID().toString()
                        + "\",\"clientVersion\":\"6.3.0\",\"favoriteStatus\":\"UnFavorite\",\"outBizNo\":\""
                        + outBizNo
                        + "\",\"type\":\"HOME_PROMO_ADVERTISEMENT\",\"userType\":\"new\"}]");
    }
}