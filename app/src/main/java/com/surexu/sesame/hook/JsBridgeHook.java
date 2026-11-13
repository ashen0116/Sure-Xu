package com.surexu.sesame.hook;

import com.surexu.sesame.data.AppConfig;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.XHelpers;
import com.surexu.sesame.util.compat.XC_MethodHook;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * JSBridge 通道抓包。
 *
 * <p>游戏 H5 网页（如 wanyiwan.top 的 yuyan 页面）通过 AlipayJSBridge.call('httpRequest', ...)
 * 发起的请求，走 Nebula/H5 容器自己的网络栈——它既不是 ariver 小程序网络（MiniProgramHook 抓不到），
 * 也不走系统 WebView 的 shouldInterceptRequest / OkHttp（installHttpCaptureHooks 抓不到）。
 * 本项目之前对游戏请求 0 命中，根因在此。
 *
 * <p>本类从 JSBridge 注入点入手：
 * <ol>
 *   <li>hook {@code WebView.addJavascriptInterface}，当注入名为 AlipayJSBridge 的对象时，
 *       动态 hook 其 call/invoke/exec 方法，记录 action 与全部参数（含 url/method/data）；</li>
 *   <li>hook {@code WebChromeClient.onJsPrompt} 作为兜底——部分内核的 JSBridge 走 prompt 桥。</li>
 * </ol>
 * 只读记录，不修改任何请求参数与返回值。记录可见性由「抓包记录」开关门控。
 */
public final class JsBridgeHook {

    private JsBridgeHook() {
    }

    private static final Set<Class<?>> sHookedClasses = new HashSet<>();
    private static volatile boolean sInstalled = false;

    private static final int MAX_LOG_LEN = 4000;

    public static synchronized void install(ClassLoader cl, String processName) {
        if (cl == null || sInstalled) {
            return;
        }
        sInstalled = true;
        hookAddJavascriptInterface(cl);
        hookOnJsPrompt(cl);
        Log.debug("JSBridge抓包hook装配 进程=" + processName);
    }

    private static boolean isBridgeName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.contains("jsbridge") || lower.contains("alipay") || lower.contains("nebula");
    }

    private static void hookAddJavascriptInterface(ClassLoader cl) {
        try {
            XHelpers.findAndHookMethod("android.webkit.WebView", cl, "addJavascriptInterface",
                    Object.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object obj = p.args[0];
                                String name = String.valueOf(p.args[1]);
                                if (obj != null && isBridgeName(name)) {
                                    hookBridgeObject(obj.getClass());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            Log.debug("JSBridge hook addJavascriptInterface 已安装");
        } catch (Throwable t) {
            Log.debug("JSBridge hook addJavascriptInterface 失败: " + t.getMessage());
        }
    }

    private static synchronized void hookBridgeObject(Class<?> clazz) {
        if (clazz == null || sHookedClasses.contains(clazz)) {
            return;
        }
        sHookedClasses.add(clazz);
        Class<?> cur = clazz;
        int guard = 0;
        while (cur != null && cur != Object.class && guard++ < 8) {
            for (Method m : cur.getDeclaredMethods()) {
                String mn = m.getName();
                if (!("call".equals(mn) || "invoke".equals(mn) || "exec".equals(mn))) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                boolean hasString = false;
                for (Class<?> pt : pts) {
                    if (pt == String.class) {
                        hasString = true;
                        break;
                    }
                }
                if (!hasString) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    XHelpers.hookMember(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                logBridgeCall(p.args);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            cur = cur.getSuperclass();
        }
        Log.debug("JSBridge 注入对象已hook: " + clazz.getName());
    }

    private static void hookOnJsPrompt(ClassLoader cl) {
        try {
            XHelpers.findAndHookMethod("android.webkit.WebChromeClient", cl, "onJsPrompt",
                    android.webkit.WebView.class, String.class, String.class, String.class,
                    android.webkit.JsPromptResult.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Object msg = p.args[2];
                                if (msg != null) {
                                    String s = msg.toString();
                                    if (s.contains("httpRequest") || s.contains("\"api\"")
                                            || s.contains("\"handler\"") || s.contains("http")) {
                                        if (captureEnabled()) {
                                            Log.debug("[JSBridge:prompt] "
                                                    + clip(s.replaceAll("\\r?\\n", " ")) + "\n");
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            Log.debug("JSBridge hook onJsPrompt 已安装");
        } catch (Throwable t) {
            Log.debug("JSBridge hook onJsPrompt 失败: " + t.getMessage());
        }
    }

    private static void logBridgeCall(Object[] args) {
        if (!captureEnabled() || args == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            if (a == null) {
                sb.append("null | ");
                continue;
            }
            sb.append(a.toString().replaceAll("\\r?\\n", " ")).append(" | ");
        }
        String line = sb.toString();
        if (line.contains("httpRequest") || line.contains("http")) {
            Log.debug("[JSBridge:call] " + clip(line) + "\n");
        }
    }

    private static String clip(String s) {
        if (s != null && s.length() > MAX_LOG_LEN) {
            return s.substring(0, MAX_LOG_LEN) + "...(truncated)";
        }
        return s;
    }

    private static boolean captureEnabled() {
        try {
            return AppConfig.isDebugLogEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
