package com.surexu.sesame.util;

import android.content.Context;
import android.content.Intent;

import com.surexu.sesame.hook.ApplicationHook;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首页执行进度的唯一账本。
 *
 * <p>以前只靠广播通知界面：注入进程和界面是两个进程，界面没在前台、广播被系统丢掉、
 * 或者界面刚好在重建，那一两条「跑完了」就永远收不到，进度条于是卡在 1/4、8/21。
 * 现在每个事件都先落盘再发广播，界面每 2 秒读一次盘兜底，广播只负责喊一声「快去读」。
 *
 * <p>另外总数用「本轮真正会跑的功能数」：整轮开始时按开启数预估，
 * 某个功能被自身条件挡掉没跑时发 SKIP 把总数扣掉，进度条最后一定走得满。
 */
public final class RunProgress {

    public static final String FILE_NAME = "run_progress.json";

    /** 首页进度条的大类；「基础」常年 0/0，已去掉，基础功能并入「其他」 */
    public static final String[] GROUPS = {"FOREST", "FARM", "STALL", "SPORTS", "OTHER"};

    private static final class Item {
        int total;
        int done;
        int skipped;
        String current = "";
    }

    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();

    /** 本轮要跑的功能清单：功能名 → 状态（等待 / 进行中 / 完成 / 跳过 / 已停止） */
    private static final LinkedHashMap<String, String> TASK_ITEMS = new LinkedHashMap<>();
    /** 本轮状态：idle / running / done / stopped */
    private static String phase = "idle";
    /** 下一次执行的时间与方式，如 "17:53#定点" */
    private static String nextRun = "";

    static {
        for (String g : GROUPS) {
            ITEMS.put(g, new Item());
        }
    }

    private RunProgress() {
    }

    public static synchronized void publish(String event, String group, String name, String groupTotals) {
        switch (event == null ? "" : event) {
            case "START":
                for (Item it : ITEMS.values()) {
                    it.total = 0;
                    it.done = 0;
                    it.skipped = 0;
                    it.current = "";
                }
                phase = "running";
                TASK_ITEMS.clear();
                if (groupTotals != null) {
                    for (String part : groupTotals.split(",")) {
                        String[] kv = part.split(":");
                        if (kv.length == 2 && ITEMS.containsKey(kv[0])) {
                            try {
                                ITEMS.get(kv[0]).total = Math.max(0, Integer.parseInt(kv[1].trim()));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
                break;
            case "PLAN":
                // groupTotals 复用为「本轮功能清单」，用 | 分隔（功能名里可能有逗号）
                if (groupTotals != null && !groupTotals.isEmpty()) {
                    for (String n : groupTotals.split("\\|")) {
                        if (!n.trim().isEmpty()) {
                            TASK_ITEMS.put(n.trim(), "等待");
                        }
                    }
                }
                break;
            case "NEXT":
                nextRun = name == null ? "" : name;
                break;
            case "RUNNING": {
                phase = "running";
                if (name != null && !name.isEmpty()) {
                    TASK_ITEMS.put(name, "进行中");
                }
                Item it = ITEMS.get(normalize(group));
                if (it != null) {
                    it.current = name == null ? "" : name;
                    // 只有「没发过 START 的单个分组执行」才补个 1，
                    // 整轮里别把用户开启的总数越抬越高
                    if (it.total == 0 && it.done == 0) {
                        it.total = 1;
                    }
                }
                break;
            }
            case "ITEM_DONE": {
                if (name != null && !name.isEmpty()) {
                    TASK_ITEMS.put(name, "完成");
                }
                Item it = ITEMS.get(normalize(group));
                if (it != null) {
                    it.done++;
                    if (it.done > it.total) {
                        it.total = it.done;
                    }
                    // 同一大类里可能有几个功能并行，只清掉自己那条，别把别人正在跑的名字抹了
                    if (name != null && name.equals(it.current)) {
                        it.current = "";
                    }
                }
                break;
            }
            case "SKIP": {
                if (name != null && !name.isEmpty()) {
                    TASK_ITEMS.put(name, "跳过");
                }
                Item it = ITEMS.get(normalize(group));
                if (it != null) {
                    // 开了几个就显示几个：被自身条件挡住的功能也算「这一轮过了一遍」，
                    // 只加已完成数，绝不缩小分母——以前扣分母，才出现「开 5 个却显示 1/1」
                    it.skipped++;
                    if (it.done < it.total) {
                        it.done++;
                    }
                }
                break;
            }
            case "DONE":
                phase = "done";
                for (Item it : ITEMS.values()) {
                    it.current = "";
                }
                break;
            case "STOPPED":
                phase = "stopped";
                for (Item it : ITEMS.values()) {
                    it.current = "";
                }
                for (Map.Entry<String, String> e : TASK_ITEMS.entrySet()) {
                    if ("进行中".equals(e.getValue()) || "等待".equals(e.getValue())) {
                        e.setValue("已停止");
                    }
                }
                break;
            default:
                break;
        }
        save();
        broadcast(event, group, name, groupTotals);
    }

    /** 老包名 / 基础组 / 未知组都归到「其他」，避免界面出现对不上的空进度条 */
    private static String normalize(String group) {
        return (group == null || group.isEmpty() || !ITEMS.containsKey(group)) ? "OTHER" : group;
    }

    private static String json() {
        try {
            JSONObject root = new JSONObject();
            root.put("updatedAt", System.currentTimeMillis());
            JSONObject groups = new JSONObject();
            for (Map.Entry<String, Item> e : ITEMS.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("total", e.getValue().total);
                o.put("done", e.getValue().done);
                o.put("skipped", e.getValue().skipped);
                o.put("current", e.getValue().current);
                groups.put(e.getKey(), o);
            }
            root.put("groups", groups);
            root.put("phase", phase);
            root.put("nextRun", nextRun);
            org.json.JSONArray items = new org.json.JSONArray();
            for (Map.Entry<String, String> e : TASK_ITEMS.entrySet()) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", e.getKey());
                o.put("status", e.getValue());
                items.put(o);
            }
            root.put("items", items);
            return root.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void save() {
        String text = json();
        if (text.isEmpty()) {
            return;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(FileUtil.MAIN_DIRECTORY_FILE, FILE_NAME), false);
            out.write(text.getBytes(StandardCharsets.UTF_8));
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

    /** 广播只作即时提醒，真正的数据以磁盘账本为准 */
    private static void broadcast(String event, String group, String name, String groupTotals) {
        try {
            Context ctx = ApplicationHook.getContext();
            if (ctx == null) {
                return;
            }
            Intent it = new Intent("com.surexu.sesame.update");
            it.putExtra("progressEvent", event);
            it.putExtra("progressGroup", group == null ? "OTHER" : group);
            it.putExtra("progressName", name == null ? "" : name);
            if (groupTotals != null) {
                it.putExtra("groupTotals", groupTotals);
            }
            ctx.sendBroadcast(it);
        } catch (Throwable ignored) {
        }
    }
}
