package com.surexu.sesame.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.surexu.sesame.model.normal.base.BaseModel;

/**
 * 页面打开闸门。
 *
 * <p>「浏览 / 逛逛 / 去看看」这类任务原本靠真正拉起小程序或网页来推进进度，
 * 执行时会把支付宝切到前台界面。这里统一收口：静默模式（基础配置里的
 * 「禁止打开页面」）生效时只记一条日志，不拉起任何界面，调用方直接走协议上报。
 */
public class PageOpenGate {

    /** 静默模式是否生效；配置读取异常时按「生效」处理，避免误跳转 */
    public static boolean isBlocked() {
        try {
            Boolean value = BaseModel.getBlockPageOpen() == null
                    ? null : BaseModel.getBlockPageOpen().getValue();
            return value == null || value;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 打开链接（alipays:// 或 http），返回 true 表示界面真的被拉起。
     * 静默模式或参数非法时返回 false，调用方应直接走协议上报。
     */
    public static boolean open(Context ctx, String url, String tag) {
        if (ctx == null || url == null || url.isEmpty()) {
            return false;
        }
        if (isBlocked()) {
            Log.record(tag + "⏸️静默模式已拦截页面打开：" + brief(url));
            return false;
        }
        try {
            Intent intent;
            if (url.startsWith("alipays://")) {
                intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            } else {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.record(tag + "打开链接失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    private static String brief(String url) {
        return url.length() > 80 ? url.substring(0, 80) + "…" : url;
    }
}
