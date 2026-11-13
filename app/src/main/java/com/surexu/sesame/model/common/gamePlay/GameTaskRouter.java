package com.surexu.sesame.model.common.gamePlay;

import com.surexu.sesame.model.task.antGame.GameTask;
import com.surexu.sesame.util.Log;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * 游戏类任务的统一入口：按服务端下发的字段选路子，不写死任何 appId。
 *
 * <p>各模块（新村 / 会员游戏中心 / 森林活力值）以前遇到「要玩游戏」的任务只会直接报完成，
 * 被服务端拒绝后记可疑错误、三次拉黑，奖励永远领不到。这里把三件事收口：
 * <ol>
 *   <li>把下发成字符串的 {@code bizInfo} 等字段展开成对象，字段扫描才钻得进去；</li>
 *   <li>按任务自带字段选路子：外部游戏服计数的走 {@link GameTask}，其余走
 *       {@link GamePlaySimulator} 上报游玩行为；</li>
 *   <li>找不到游戏 id 时把完整任务 JSON 打进调试日志，照着补解析路径，绝不瞎编请求。</li>
 * </ol>
 *
 * <p>返回 true 只表示「游玩行为已上报」，不代表任务完成。调用方必须接着走本模块自己的
 * 完成 / 领奖接口，最后重拉任务列表复核状态（响应会撒谎）。
 */
public final class GameTaskRouter {

    private static final String TAG = "游戏任务🛣";

    private GameTaskRouter() {
    }

    /** 下发数据里 bizInfo / taskConfig 常是 JSON 字符串，还常常嵌在 taskBaseInfo 里，递归展开才扫得到 appId。 */
    public static JSONObject expand(JSONObject task) {
        return expand(task, 0);
    }

    private static JSONObject expand(JSONObject task, int depth) {
        JSONObject probe = new JSONObject();
        if (task == null || depth > 3) {
            return probe;
        }
        try {
            Iterator<String> it = task.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = task.opt(k);
                if (v instanceof JSONObject) {
                    probe.put(k, expand((JSONObject) v, depth + 1));
                    continue;
                }
                if (v instanceof String) {
                    String s = ((String) v).trim();
                    if (s.startsWith("{")) {
                        try {
                            probe.put(k, expand(new JSONObject(s), depth + 1));
                            continue;
                        } catch (Throwable ignored) {
                            // 不是合法 JSON 就按原值放回去
                        }
                    }
                }
                probe.put(k, v);
            }
        } catch (Throwable t) {
            Log.record(TAG + "⚠️任务字段展开失败：" + t);
        }
        return probe;
    }

    private static final Object ROUND_LOCK = new Object();
    private static int roundPlayed = 0;
    private static long lastCallMs = 0L;

    /** 两轮相隔超过 10 分钟就当新一轮，把本轮计数清零；不用每个模块各自维护计数器。 */
    private static int beginRoundIfNeeded() {
        synchronized (ROUND_LOCK) {
            long now = System.currentTimeMillis();
            if (now - lastCallMs > 10 * 60 * 1000L) {
                roundPlayed = 0;
            }
            lastCallMs = now;
            return roundPlayed;
        }
    }

    private static void markPlayed() {
        synchronized (ROUND_LOCK) {
            roundPlayed++;
        }
    }

    /** 新模块接入只要这一行；本轮限速由本类自己管。 */
    public static boolean play(String module, JSONObject task) {
        boolean ok = play(module, task, beginRoundIfNeeded());
        if (ok) {
            markPlayed();
        }
        return ok;
    }

    /**
     * 识别游戏任务并上报游玩行为。
     *
     * @param module          功能名，只用于日志
     * @param task            服务端下发的原始任务 JSON
     * @param playedThisRound 本轮已假玩过几个，配合 {@link GamePlaySimulator#MAX_PLAY_PER_ROUND} 限速
     * @return true 表示已上报行为，调用方继续走完成 / 领奖
     */
    public static boolean play(String module, JSONObject task, int playedThisRound) {
        JSONObject probe = expand(task);
        if (!GamePlaySimulator.isGameTask(probe)) {
            return false;
        }
        if (playedThisRound >= GamePlaySimulator.MAX_PLAY_PER_ROUND) {
            Log.record(TAG + "⏸️[" + module + "]本轮假玩已达上限，留到下一轮");
            return false;
        }
        String appId = GamePlaySimulator.gameIdOf(probe);
        if (appId.isEmpty()) {
            // 摘要会截断长字段，完整 JSON 单独进调试日志（需开「抓包记录」开关）
            Log.debug(TAG + "⚠️[" + module + "]缺游戏id，完整任务JSON：" + probe);
            Log.record(TAG + "⚠️[" + module + "]缺游戏id，已把完整任务JSON打进抓包记录");
            return false;
        }

        // 路子二：游戏不在支付宝里、由外部游戏服记次数的（砸蛋 / 救援队 / 乐园宝箱）。
        // 命中枚举里那几个 appId 才走，并且必须带它自己的专用渠道，否则支付宝侧权益不推进。
        GameTask external = GameTask.matchAppId(appId);
        if (external != null) {
            int ok = external.reportSync(module, 1);
            Log.record(TAG + "🎯[" + module + "]外部游戏服报次数[" + external.getTitle()
                    + "]#appId=" + appId + "#成功=" + ok);
            return ok > 0;
        }

        // 路子一：支付宝游戏中心行为上报（进游戏 / 首帧 / 游玩时长），通用做法。
        boolean ok = GamePlaySimulator.playForTask(module, probe,
                GamePlaySimulator.Scene.GENERIC, playedThisRound);
        if (ok) {
            Log.record(TAG + "🎮[" + module + "]已上报游玩行为，接着走本模块完成/领奖，最后以任务列表复核为准");
        }
        return ok;
    }
}
