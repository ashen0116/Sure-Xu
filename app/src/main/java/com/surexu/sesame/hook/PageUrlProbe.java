package com.surexu.sesame.hook;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.XHelpers;
import com.surexu.sesame.util.compat.XC_MethodHook;

/**
 * 页面地址探针（只读）。
 *
 * <p>用途：H5/小程序页面（如 XRiverActivity、Nebula 容器）打开时，把 Intent 里的
 * uri 与关键 extras（url/pageUrl/appId/sceneCode/taskId 等）写进「抓包记录」。
 * 解决 dumpsys 只给 "(has extras)"、Nebula 不打 URL 到 logcat 的问题。
 *
 * <p>只记录，不改动任何行为；写出仍由「抓包记录」开关（Log.debug）门控。
 */
public final class PageUrlProbe {

    private static final String TAG = "PageUrlProbe";
    private static final int MAX_VALUE_LEN = 300;

    private static volatile boolean installed = false;

    private PageUrlProbe() {
    }

    public static void install(ClassLoader cl, String processName) {
        if (installed) {
            return;
        }
        installed = true;
        try {
            XHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    dump(param.thisObject, "onCreate");
                }
            });
            XHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    dump(param.thisObject, "onNewIntent");
                }
            });
            Log.debug("页面探针已装配 进程=" + processName);
        } catch (Throwable t) {
            Log.debug("页面探针装配失败: " + t);
        }
    }

    private static boolean interesting(String className) {
        if (className == null) {
            return false;
        }
        String lower = className.toLowerCase();
        return lower.contains("xriver") || lower.contains("nebula") || lower.contains("h5")
                || lower.contains("webview") || lower.contains("ariver");
    }

    private static boolean interestingKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        return lower.contains("url") || lower.contains("uri") || lower.contains("page")
                || lower.contains("appid") || lower.contains("scene") || lower.contains("task")
                || lower.contains("param") || lower.contains("query") || lower.contains("link")
                || lower.contains("scheme") || lower.contains("biz");
    }

    private static void dump(Object activity, String stage) {
        try {
            if (!(activity instanceof Activity)) {
                return;
            }
            Activity act = (Activity) activity;
            String cls = act.getClass().getName();
            if (!interesting(cls)) {
                return;
            }
            Intent intent = act.getIntent();
            if (intent == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            String data = intent.getDataString();
            if (data != null && data.length() > 0) {
                sb.append("data=").append(clip(data));
            }
            Bundle extras = intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object value = null;
                    try {
                        value = extras.get(key);
                    } catch (Throwable ignored) {
                    }
                    if (value == null) {
                        continue;
                    }
                    String text = String.valueOf(value);
                    boolean keyHit = interestingKey(key);
                    boolean valHit = text.contains("http://") || text.contains("https://") || text.contains("alipays:");
                    if (!keyHit && !valHit) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(" | ");
                    }
                    sb.append(key).append('=').append(clip(text));
                }
            }
            if (sb.length() == 0) {
                return;
            }
            Log.debug("【页面探针】" + stage + " " + cls + " " + sb);
        } catch (Throwable t) {
            Log.debug("页面探针异常: " + t);
        }
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_VALUE_LEN) {
            return text;
        }
        return text.substring(0, MAX_VALUE_LEN) + "…";
    }
}
