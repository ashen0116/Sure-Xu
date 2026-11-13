package com.surexu.sesame.util;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 各功能自己的「今日 / 本月 / 今年 / 累计」统计（小鸡收蛋、海洋能量、新村木兰币……）。
 * <p>
 * 与 {@link Statistics} 分开：那边只记森林能量且字段固定，这里按「功能名 + 指标名」记任意数值，
 * 供首页「各功能」卡片按同一套时间维度展示。文件与 statistics.json 同目录，UI 进程直接读盘。
 * <p>
 * 结构：
 * {"day":"2026-11-07","month":"2026-11","year":"2026",
 * "features":{"庄园":{"last":1699...,"m":{"egg":{"day":12,"month":120,"year":500,"all":1200}}}}}
 * 跨天只清 day，跨月清 day+month，跨年三者全清，累计 all 永不清零。
 */
public final class FeatureStats {

    private static final String TAG = FeatureStats.class.getSimpleName();
    private static final String FILE_NAME = "feature_stats.json";

    private static final String KEY_DAY = "day";
    private static final String KEY_MONTH = "month";
    private static final String KEY_YEAR = "year";
    private static final String KEY_ALL = "all";
    private static final String KEY_FEATURES = "features";
    private static final String KEY_METRICS = "m";
    private static final String KEY_LAST = "last";

    /** 上限：功能数、每功能指标数，防止文件被写爆 */
    private static final int MAX_FEATURES = 64;
    private static final int MAX_KEYS = 32;

    /** 时间维度，与首页四档切换一一对应 */
    public enum Period {
        DAY(KEY_DAY), MONTH(KEY_MONTH), YEAR(KEY_YEAR), ALL(KEY_ALL);

        final String key;

        Period(String key) {
            this.key = key;
        }
    }

    private static JSONObject root;

    private FeatureStats() {
    }

    private static File file() {
        return FileUtil.getFeatureStatsFile();
    }

    private static String fmt(String pattern) {
        return new SimpleDateFormat(pattern, Locale.ROOT).format(new Date());
    }

    /** UI 进程读盘前调用：丢掉缓存快照，重新读文件 */
    public static synchronized void reload() {
        root = null;
    }

    private static synchronized JSONObject load() {
        if (root != null) {
            return root;
        }
        JSONObject jo = new JSONObject();
        try {
            File f = file();
            if (f.isFile() && f.length() <= 512 * 1024) {
                String s = FileUtil.readFromFile(f);
                if (s != null && !s.isEmpty()) {
                    jo = new JSONObject(s);
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "读取功能统计失败，按空数据继续:", t);
        }
        root = jo;
        return root;
    }

    private static synchronized void save() {
        try {
            JSONObject r = load();
            r.put(KEY_DAY, fmt("yyyy-MM-dd"));
            r.put(KEY_MONTH, fmt("yyyy-MM"));
            r.put(KEY_YEAR, fmt("yyyy"));
            FileUtil.write2File(r.toString(), file());
        } catch (Throwable t) {
            Log.err(TAG, "写入功能统计失败:", t);
        }
    }

    /** 跨天/跨月/跨年结转：只清对应的短周期桶 */
    private static synchronized void rollover() {
        JSONObject r = load();
        String day = fmt("yyyy-MM-dd");
        String month = fmt("yyyy-MM");
        String year = fmt("yyyy");
        boolean dayChanged = !day.equals(r.optString(KEY_DAY, ""));
        boolean monthChanged = !month.equals(r.optString(KEY_MONTH, ""));
        boolean yearChanged = !year.equals(r.optString(KEY_YEAR, ""));
        if (!dayChanged && !monthChanged && !yearChanged) {
            return;
        }
        try {
            JSONObject features = r.optJSONObject(KEY_FEATURES);
            if (features != null) {
                Iterator<String> it = features.keys();
                while (it.hasNext()) {
                    JSONObject f = features.optJSONObject(it.next());
                    JSONObject metrics = f == null ? null : f.optJSONObject(KEY_METRICS);
                    if (metrics == null) {
                        continue;
                    }
                    Iterator<String> keys = metrics.keys();
                    while (keys.hasNext()) {
                        JSONObject m = metrics.optJSONObject(keys.next());
                        if (m == null) {
                            continue;
                        }
                        if (yearChanged) {
                            m.put(KEY_YEAR, 0d);
                        }
                        if (monthChanged) {
                            m.put(KEY_MONTH, 0d);
                        }
                        m.put(KEY_DAY, 0d);
                    }
                }
            }
            r.put(KEY_DAY, day);
            r.put(KEY_MONTH, month);
            r.put(KEY_YEAR, year);
            save();
        } catch (Throwable t) {
            Log.err(TAG, "功能统计跨期结转失败:", t);
        }
    }

    private static JSONObject featureObj(JSONObject r, String feature) {
        JSONObject features = r.optJSONObject(KEY_FEATURES);
        if (features == null) {
            features = new JSONObject();
            try {
                r.put(KEY_FEATURES, features);
            } catch (Throwable ignored) {
                return null;
            }
        }
        JSONObject f = features.optJSONObject(feature);
        if (f == null) {
            if (features.length() >= MAX_FEATURES) {
                return null;
            }
            f = new JSONObject();
            try {
                features.put(feature, f);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return f;
    }

    /**
     * 记一笔数量：四个时间维度同时累加。
     *
     * @param feature 功能名（与模型名一致，如「庄园」「海洋」「新村」）
     * @param key     指标名（如 egg / energy / coin）
     * @param n       增量，可为小数（鸡蛋颗数就是小数）
     */
    public static synchronized void add(String feature, String key, double n) {
        if (feature == null || key == null || n == 0d) {
            return;
        }
        rollover();
        try {
            JSONObject r = load();
            JSONObject f = featureObj(r, feature);
            if (f == null) {
                return;
            }
            JSONObject metrics = f.optJSONObject(KEY_METRICS);
            if (metrics == null) {
                metrics = new JSONObject();
                f.put(KEY_METRICS, metrics);
            }
            if (metrics.length() >= MAX_KEYS && !metrics.has(key)) {
                return;
            }
            JSONObject m = metrics.optJSONObject(key);
            if (m == null) {
                m = new JSONObject();
                metrics.put(key, m);
            }
            m.put(KEY_DAY, m.optDouble(KEY_DAY, 0d) + n);
            m.put(KEY_MONTH, m.optDouble(KEY_MONTH, 0d) + n);
            m.put(KEY_YEAR, m.optDouble(KEY_YEAR, 0d) + n);
            m.put(KEY_ALL, m.optDouble(KEY_ALL, 0d) + n);
            f.put(KEY_LAST, System.currentTimeMillis());
        } catch (Throwable e) {
            Log.err(TAG, "记录功能统计失败:", e);
            return;
        }
        save();
    }

    /** 记当前值（不累加）：肥料余额这类「瞬时量」用这个，累加会把余额越记越大 */
    public static synchronized void set(String feature, String key, double value) {
        if (feature == null || key == null) {
            return;
        }
        rollover();
        try {
            JSONObject r = load();
            JSONObject f = featureObj(r, feature);
            if (f == null) {
                return;
            }
            JSONObject metrics = f.optJSONObject(KEY_METRICS);
            if (metrics == null) {
                metrics = new JSONObject();
                f.put(KEY_METRICS, metrics);
            }
            JSONObject m = metrics.optJSONObject(key);
            if (m == null) {
                m = new JSONObject();
                metrics.put(key, m);
            }
            m.put(KEY_DAY, value);
            m.put(KEY_MONTH, value);
            m.put(KEY_YEAR, value);
            m.put(KEY_ALL, value);
        } catch (Throwable e) {
            Log.err(TAG, "记录瞬时统计失败:", e);
            return;
        }
        save();
    }

    /** 记一次「功能跑过」：同时记下运行状态，首页才能区分「今日已跑 / 中途断开」 */
    public static synchronized void touch(String feature) {
        if (feature == null) {
            return;
        }
        rollover();
        try {
            JSONObject f = featureObj(load(), feature);
            if (f == null) {
                return;
            }
            long now = System.currentTimeMillis();
            f.put(KEY_LAST, now);
            f.put("status", "running");
            f.put("startAt", now);
        } catch (Throwable ignored) {
            return;
        }
        // 首页是独立进程，只能靠磁盘通信：必须及时落盘，否则界面上的「上次」会一直是空的。
        // 整个文件不到 1KB，每个模型每轮起跑只写一次，这点 IO 可以忽略。
        save();
    }

    /** 功能收尾：正常跑完 done，还有没做完的任务 partial，被中断 failed */
    public static synchronized void finish(String feature, String result) {
        if (feature == null) {
            return;
        }
        try {
            JSONObject f = featureObj(load(), feature);
            if (f == null) {
                return;
            }
            f.put("status", result == null ? "done" : result);
            f.put("finishAt", System.currentTimeMillis());
        } catch (Throwable ignored) {
            return;
        }
        save();
    }

    public static synchronized String status(String feature) {
        try {
            JSONObject features = load().optJSONObject(KEY_FEATURES);
            JSONObject f = features == null ? null : features.optJSONObject(feature);
            return f == null ? "" : f.optString("status", "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 已经埋点过的功能名，首页「其他功能」据此列出所有子功能 */
    public static synchronized List<String> allFeatureNames() {
        List<String> names = new ArrayList<>();
        try {
            JSONObject features = load().optJSONObject(KEY_FEATURES);
            if (features != null) {
                Iterator<String> it = features.keys();
                while (it.hasNext()) {
                    names.add(it.next());
                }
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    /** 今日获得的点数：各模块统一往 point 指标里记 */
    public static double todayPoints(String feature) {
        return today(feature, "point");
    }

    /** 取某功能某指标在指定时间维度的值 */
    public static synchronized double get(String feature, String key, Period period) {
        try {
            JSONObject features = load().optJSONObject(KEY_FEATURES);
            if (features == null) {
                return 0d;
            }
            JSONObject f = features.optJSONObject(feature);
            if (f == null) {
                return 0d;
            }
            JSONObject metrics = f.optJSONObject(KEY_METRICS);
            if (metrics == null) {
                return 0d;
            }
            JSONObject m = metrics.optJSONObject(key);
            return m == null ? 0d : m.optDouble(period.key, 0d);
        } catch (Throwable t) {
            return 0d;
        }
    }

    /** 今日某指标（等价于 get(..., Period.DAY)） */
    public static double today(String feature, String key) {
        return get(feature, key, Period.DAY);
    }

    /** 累计某指标（等价于 get(..., Period.ALL)） */
    public static double total(String feature, String key) {
        return get(feature, key, Period.ALL);
    }

    /** 上次运行时间戳（毫秒），0 表示没记录 */
    public static synchronized long lastRun(String feature) {
        try {
            JSONObject features = load().optJSONObject(KEY_FEATURES);
            if (features == null) {
                return 0L;
            }
            JSONObject item = features.optJSONObject(feature);
            return item == null ? 0L : item.optLong(KEY_LAST, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }
}
