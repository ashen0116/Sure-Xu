package com.surexu.sesame.util;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 「打开 APP 先别急着跑任务」的共享状态。
 *
 * <p>Xu 和支付宝是两个进程，开关和静默截止时间都放同一个文件，谁都能读写；
 * 这里刻意不碰任何 Xposed 相关类，保证模块 App 的界面进程也能安全加载。
 *
 * <p>规矩：打开 Xu 或回到支付宝首页时开一个 10 秒窗口，窗口里所有「自动」触发的任务
 * 都先停一下，留时间给人点「执行」或关掉功能；用户一明确点执行就立刻放行。
 * 窗口过后定时链照常续上，不影响原来的自动执行。
 */
public final class BootSilence {

    private static final String FILE_NAME = "boot_silence.json";
    private static final long DEFAULT_SECONDS = 10L;

    private BootSilence() {
    }

    private static File file() {
        return new File(FileUtil.MAIN_DIRECTORY_FILE, FILE_NAME);
    }

    private static JSONObject state() {
        try {
            File f = file();
            if (f.isFile() && f.length() <= 4096) {
                String s = FileUtil.readFromFile(f);
                if (s != null && !s.isEmpty()) {
                    return new JSONObject(s);
                }
            }
        } catch (Throwable ignored) {
        }
        return new JSONObject();
    }

    private static void write(JSONObject s) {
        FileOutputStream out = null;
        try {
            File f = file();
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            out = new FileOutputStream(f, false);
            out.write(s.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static synchronized boolean isEnabled() {
        return state().optBoolean("enabled", true);
    }

    public static synchronized long getSeconds() {
        long s = state().optLong("seconds", DEFAULT_SECONDS);
        return s <= 0 ? DEFAULT_SECONDS : s;
    }

    public static synchronized void setEnabled(boolean on) {
        JSONObject s = state();
        try {
            s.put("enabled", on);
            if (!on) {
                s.put("until", 0L);
            }
        } catch (Throwable ignored) {
        }
        write(s);
    }

    /** 打开 Xu / 回到支付宝首页时开一个静默窗口 */
    public static synchronized void arm() {
        if (!isEnabled()) {
            return;
        }
        JSONObject s = state();
        try {
            s.put("until", System.currentTimeMillis() + getSeconds() * 1000L);
        } catch (Throwable ignored) {
        }
        write(s);
    }

    /** 用户明确点了「执行」：立刻放行，不用等静默走完 */
    public static synchronized void release() {
        JSONObject s = state();
        try {
            s.put("until", 0L);
        } catch (Throwable ignored) {
        }
        write(s);
    }

    /** 还剩多少毫秒静默；返回 0 表示当前没在静默 */
    public static synchronized long remainingMillis() {
        if (!isEnabled()) {
            return 0L;
        }
        long until = state().optLong("until", 0L);
        return Math.max(0L, until - System.currentTimeMillis());
    }

    public static synchronized boolean blockAuto() {
        return remainingMillis() > 0;
    }
}
