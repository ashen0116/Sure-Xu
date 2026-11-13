package com.surexu.sesame.model.task.videoRewards;

import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.modelFieldExt.BooleanModelField;
import com.surexu.sesame.data.modelFieldExt.IntegerModelField;
import com.surexu.sesame.data.modelFieldExt.StringModelField;
import com.surexu.sesame.data.task.ModelTask;
import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.model.base.TaskCommon;
import com.surexu.sesame.model.common.gamePlay.GamePlaySimulator;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.TimeUtil;
import com.surexu.sesame.util.idMap.UserIdMap;

import org.json.JSONObject;

/**
 * 内容种草「上滑一次 必得红包」（支付宝短视频页，余额 XX 元 / 今日已打款 N/8 次）。
 *
 * <p>纯接口实现，全程不碰屏幕、不需要打开页面：
 * <ol>
 *   <li>{@code contentSeedingSwitchoverStimulate.query} 查进度，
 *       返回里自带 {@code perceivedCount} / {@code totalRequired} /
 *       {@code processTaskId} / {@code processTaskCenterId}，故任务号无需写死；</li>
 *   <li>{@code taskDirectPrize.complete} 直接发奖，参数只有那两个任务号。</li>
 * </ol>
 *
 * <p>实测：单次到账 0.01~0.02 元，8 次约 0.12 元；满 8 次后 {@code withdrawable} 变 true，
 * 但提现接口尚未抓到（需在页面上点一次「立即提现」才能抓包），故本模型只负责领满，
 * 不处理提现。
 *
 * <p>服务器只看第几次调用，不校验播放行为；但为降低风控概率，每次之间随机等 15~20 秒。
 */
public class ContentSeedingSlide extends ModelTask {

    /** 查询进度 */
    private static final String RPC_QUERY = "com.alipay.fundscenebff.needle.contentSeedingSwitchoverStimulate.query";
    /** 直接发奖 */
    private static final String RPC_COMPLETE = "com.alipay.fundscenebff.needle.taskDirectPrize.complete";

    /** 当日已跑完标记：满 8 次后当天不再重复查询，避免无意义的请求 */
    private static final String FLAG_DONE = "videoRewards::contentSeedingDone";

    /** 两次领取之间的最小/最大间隔（毫秒），按真人刷视频节奏随机 */
    private static final long MIN_INTERVAL_MS = 15000L;
    private static final long MAX_INTERVAL_MS = 20000L;

    private IntegerModelField intervalSeconds;
    private BooleanModelField stopWhenFull;
    private BooleanModelField autoGamePlay;
    private StringModelField gameAppId;

    @Override
    public String getName() {
        return "内容种草滑动红包";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.OTHER;
    }

    @Override
    public Boolean getDefaultEnable() {
        return true;
    }

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        intervalSeconds = new IntegerModelField("intervalSeconds", "领取间隔(秒)", 17, 15, 60);
        intervalSeconds.setDescription("间隔±2秒");
        modelFields.addField(intervalSeconds);
        stopWhenFull = new BooleanModelField("stopWhenFull", "满了当天不再跑", true);
        stopWhenFull.setDescription("省请求");
        modelFields.addField(stopWhenFull);
        autoGamePlay = new BooleanModelField("autoGamePlay", "自动假玩游戏任务", true);
        autoGamePlay.setDescription("任务里要玩小游戏时，自动上报游玩时长，不用真开游戏");
        modelFields.addField(autoGamePlay);
        gameAppId = new StringModelField("contentSeedingGameAppId", "游戏任务 | 游戏ID(抓包后填)", "");
        gameAppId.setDescription("留空时自动从进度返回里找；找不到会在日志里提示需要抓包");
        modelFields.addField(gameAppId);
        return modelFields;
    }

    @Override
    public Boolean check() {
        String uid = UserIdMap.getCurrentUid();
        return !isEnable().equals(Boolean.FALSE)
                && uid != null && !uid.isEmpty()
                && !TaskCommon.IS_ENERGY_TIME.equals(Boolean.TRUE)
                && !ApplicationHook.isOffline();
    }

    @Override
    public void run() {
        if (!check()) {
            return;
        }
        if (stopWhenFull != null && stopWhenFull.getValue().equals(Boolean.TRUE)
                && com.surexu.sesame.util.Status.hasFlagToday(FLAG_DONE)) {
            Log.record(getName() + "⏭️今日已领满，跳过");
            return;
        }
        try {
            JSONObject state = queryState();
            if (state == null) {
                Log.record(getName() + "⏭️进度查询失败，本轮跳过");
                return;
            }
            int done = state.optInt("perceivedCount", 0);
            int need = state.optInt("totalRequired", 0);
            String taskId = state.optString("processTaskId", "");
            String taskCenId = state.optString("processTaskCenterId", "");
            String amount = state.optJSONObject("accumulatedAmount") == null
                    ? "" : state.optJSONObject("accumulatedAmount").optString("value", "");
            if (need <= 0 || taskId.isEmpty() || taskCenId.isEmpty()) {
                Log.record(getName() + "⏭️未取到任务号或上限，本轮跳过");
                return;
            }
            if (done >= need) {
                Log.record(getName() + "✅已满 " + done + "/" + need + "，累计 " + amount + " 元");
                com.surexu.sesame.util.Status.flagToday(FLAG_DONE);
                return;
            }
            // canAccess=false 表示当日入口已关闭（满次数后服务端会置false），再做也没意义
            if (!state.optBoolean("canAccess", true)) {
                Log.record(getName() + "⏭️服务端已关闭领取入口，跳过");
                com.surexu.sesame.util.Status.flagToday(FLAG_DONE);
                return;
            }

            Log.record(getName() + "🎬今日 " + done + "/" + need + "，累计 " + amount + " 元，开始领取");
            // 这个活动里带「玩小游戏」的任务：先把游玩行为报上去，再走发奖
            prepareGamePlay(state, taskId);
            int success = 0;
            for (int i = done; i < need; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    Log.record(getName() + "⏹已中断，停止领取");
                    return;
                }
                String got = complete(taskCenId, taskId);
                if (got == null) {
                    Log.record(getName() + "❌第 " + (i + 1) + " 次领取失败，停止本轮");
                    break;
                }
                success++;
                com.surexu.sesame.util.FeatureStats.add(getName(), "point", 1);
                Log.record(getName() + "🧧第 " + (i + 1) + "/" + need + " 次 +" + got + " 元");
                if (i + 1 < need) {
                    TimeUtil.sleep(nextIntervalMs());
                }
            }
            JSONObject last = queryState();
            if (last != null) {
                int nowDone = last.optInt("perceivedCount", done + success);
                String nowAmount = last.optJSONObject("accumulatedAmount") == null
                        ? "" : last.optJSONObject("accumulatedAmount").optString("value", "");
                boolean canWithdraw = last.optBoolean("withdrawable", false);
                Log.record(getName() + "✅本轮结束 " + nowDone + "/" + need
                        + "，累计 " + nowAmount + " 元"
                        + (canWithdraw ? "，已可提现" : ""));
                if (nowDone >= need) {
                    com.surexu.sesame.util.Status.flagToday(FLAG_DONE);
                }
            }
        } catch (Exception e) {
            Log.record(getName() + "异常：" + e.getClass().getSimpleName());
            Log.printStackTrace(e);
        }
    }

    /**
     * 游戏类型任务：优先用配置里手填的游戏 id；没填就尝试从进度返回里自动找。
     * <p>组件内部按「游戏id + 任务id」做了当天去重，一天只上报一次，
     * 不会每领一次红包就重玩一遍、也不会凭空多发请求。
     */
    private void prepareGamePlay(JSONObject state, String taskId) {
        if (autoGamePlay != null && !Boolean.TRUE.equals(autoGamePlay.getValue())) {
            return;
        }
        String manual = gameAppId == null || gameAppId.getValue() == null
                ? "" : gameAppId.getValue().trim();
        if (!manual.isEmpty()) {
            GamePlaySimulator.play(new GamePlaySimulator.Request()
                    .appId(manual)
                    .taskId(taskId)
                    .durationSec(GamePlaySimulator.DEFAULT_DURATION_SEC)
                    .logName(getName() + "·游戏任务"));
            return;
        }
        if (state != null && GamePlaySimulator.isGameTask(state)) {
            GamePlaySimulator.playForTask(getName(), state,
                    GamePlaySimulator.Scene.GENERIC, 0);
        }
    }

    /** 查进度；失败返回 null */
    private JSONObject queryState() {
        try {
            String resp = ApplicationHook.requestString(RPC_QUERY, "[{}]", 1, -1);
            if (resp == null || resp.isEmpty()) {
                return null;
            }
            JSONObject jo = new JSONObject(resp);
            if (jo.optBoolean("success", false)) {
                return jo.optJSONObject("result");
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        return null;
    }

    /** 领一次；成功返回本次金额，失败返回 null */
    private String complete(String taskCenId, String taskId) {
        try {
            String data = "[{\"taskCenId\":\"" + taskCenId + "\",\"taskId\":\"" + taskId + "\"}]";
            String resp = ApplicationHook.requestString(RPC_COMPLETE, data, 1, -1);
            if (resp == null || resp.isEmpty()) {
                return null;
            }
            JSONObject jo = new JSONObject(resp);
            JSONObject result = jo.optJSONObject("result");
            if (!jo.optBoolean("success", false) || result == null) {
                return null;
            }
            if (!"SUCCESS".equals(result.optString("sendStatus", ""))) {
                return null;
            }
            return result.optString("amount", "?");
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        return null;
    }

    /** 配置间隔 ±2 秒随机，避免整点整齐请求 */
    private long nextIntervalMs() {
        long base = 17000L;
        if (intervalSeconds != null && intervalSeconds.getValue() != null) {
            base = intervalSeconds.getValue().longValue() * 1000L;
        }
        long jitter = (long) (Math.random() * 4000L) - 2000L;
        long result = base + jitter;
        if (result < MIN_INTERVAL_MS) {
            result = MIN_INTERVAL_MS;
        }
        if (result > MAX_INTERVAL_MS) {
            result = MAX_INTERVAL_MS;
        }
        return result;
    }
}
