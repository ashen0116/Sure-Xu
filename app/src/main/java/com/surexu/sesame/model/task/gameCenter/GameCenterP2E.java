package com.surexu.sesame.model.task.gameCenter;

import android.content.Context;
import android.content.Intent;

import com.surexu.sesame.data.ConfigV2;
import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.modelFieldExt.BooleanModelField;
import com.surexu.sesame.data.modelFieldExt.IntegerModelField;
import com.surexu.sesame.data.modelFieldExt.SelectModelField;
import com.surexu.sesame.data.task.ModelTask;
import com.surexu.sesame.entity.CustomOption;
import com.surexu.sesame.entity.IdAndName;
import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.model.base.TaskCommon;
import com.surexu.sesame.model.common.gamePlay.GamePlaySimulator;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.MessageUtil;
import com.surexu.sesame.util.Status;
import com.surexu.sesame.util.TimeUtil;
import com.surexu.sesame.util.idMap.UserIdMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 游戏中心「赚现金」（P2E，边玩边赚）：签到 + 抽金币。
 *
 * <p>接口走 {@code com.alipay.gamecenteruprod.biz.rpc.p2e.*}：
 * <ul>
 *   <li>签到：先 {@code queryHomePage} 拿 {@code signUpModuleVO} 里的
 *       signSequenceId/date/index，再 {@code signIn} 执行；</li>
 *   <li>抽金币：直接 {@code drawGold}，抽中的金币次日发放；</li>
 * </ul>
 *
 *   <li>浏览任务：报名 → 打开停留 → 完成，完成后再 {@code gameP2eTaskReceive}
 *       领取金币（2026-11-09 真机点击「领取」抓包确认）；做不了的任务类型自动进黑名单；</li>
 * </ul>
 *
 * <p>游戏类型任务由通用「假玩游戏」组件上报游玩行为，不再一律拉黑；
 * 服务端是否放行一律以任务列表状态复核为准，能领的奖励由 {@code claimRewards()} 统一领。
 */
public class GameCenterP2E extends ModelTask {

    private static final String PREFIX = "com.alipay.gamecenteruprod.biz.rpc.p2e.";
    private static final String RPC_HOME = PREFIX + "queryHomePage";
    private static final String RPC_SIGN = PREFIX + "signIn";
    private static final String RPC_DRAW = PREFIX + "drawGold";
    private static final String RPC_TASKLIST = PREFIX + "queryTaskList";
    /** 领取已完成任务奖励：2026-11-09 真机点击「领取」抓到，成功后金币即时到账、任务从列表消失 */
    private static final String RPC_TASK_RECEIVE = PREFIX + "gameP2eTaskReceive";
    private static final String RPC_PLATFORM_SIGNUP = "com.alipay.gamecenteruprod.biz.rpc.platformTaskSignUp";
    private static final String RPC_PLATFORM_COMPLETE = "com.alipay.gamecenteruprod.biz.rpc.platformTaskComplete";

    private static final String APP_ID = "2021003125685383";
    private static final String GIT = "9e159d58cce04c13a";
    private static final String SOURCE = "ch_appcenter__chsub_9patch";
    private static final String FRONT_END_VERSION = "20260820";

    /** 黑名单在配置里的模型键 / 字段键 / 展示名 */
    private static final String BLACKLIST_MODEL = "GameCenterP2E";
    private static final String BLACKLIST_FIELD = "GameCenterP2ETaskList";
    private static final String BLACKLIST_TITLE = "游戏中心浏览任务";
    /** 只有 viewTask_ 开头的浏览任务是脚本能模拟的，其它类型（加桌面/订阅/真玩游戏等）做不了 */
    private static final String BROWSE_TASK_PREFIX = "viewTask_";
    /** 最近见过的任务标题，供黑名单候选列表在设置页里显示、可手动勾选 */
    private static final Set<String> KNOWN_TASK_TITLES = new LinkedHashSet<>();

    private BooleanModelField signIn;
    private BooleanModelField drawGold;
    private BooleanModelField autoBrowse;
    private IntegerModelField browseStay;
    private BooleanModelField autoReceive;
    private BooleanModelField autoGamePlay;
    private BooleanModelField autoBlackList;
    private SelectModelField taskBlackList;

    @Override
    public String getName() {
        return "游戏中心";
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
        signIn = new BooleanModelField("signIn", "签到领金币", true);
        modelFields.addField(signIn);
        drawGold = new BooleanModelField("drawGold", "抽金币", true);
        drawGold.setDescription("抽中的金币次日发放");
        modelFields.addField(drawGold);
        autoBrowse = new BooleanModelField("autoBrowse", "自动做浏览任务", true);
        autoBrowse.setDescription("浏览得金币");
        modelFields.addField(autoBrowse);
        browseStay = new IntegerModelField("browseStay", "浏览停留(秒)", 15, 10, 60);
        modelFields.addField(browseStay);
        autoReceive = new BooleanModelField("autoReceive", "自动领取任务奖励", true);
        autoReceive.setDescription("任务完成后自动领金币，不用手动点「领取」");
        modelFields.addField(autoReceive);
        autoGamePlay = new BooleanModelField("autoGamePlay", "自动假玩游戏任务", true);
        autoGamePlay.setDescription("任务里要玩小游戏时，自动上报游玩时长，不用真开游戏");
        modelFields.addField(autoGamePlay);
        autoBlackList = new BooleanModelField("AutoGameCenterP2ETaskList", "浏览任务 | 自动黑名单", true);
        autoBlackList.setDependsOn("autoBrowse");
        autoBlackList.setDescription("做不了的任务类型自动加进黑名单，不再反复尝试");
        modelFields.addField(autoBlackList);
        // 候选列表动态读当前已拉黑项 + 最近见过的任务，设置页里能直接看到和取消
        // ModelField 加载时会替换 value 引用，必须用 holder 动态读 getValue()
        final SelectModelField[] holder = new SelectModelField[1];
        holder[0] = new SelectModelField(BLACKLIST_FIELD, "浏览任务 | 黑名单列表",
                new LinkedHashSet<>(),
                (SelectModelField.SelectListFunc) () -> {
                    Set<String> vals = holder[0] == null ? null : holder[0].getValue();
                    LinkedHashSet<String> all = new LinkedHashSet<>();
                    if (vals != null) {
                        all.addAll(vals);
                    }
                    synchronized (KNOWN_TASK_TITLES) {
                        all.addAll(KNOWN_TASK_TITLES);
                    }
                    List<IdAndName> list = new ArrayList<>();
                    for (String t : all) {
                        list.add(new CustomOption(t, t));
                    }
                    return list;
                });
        holder[0].setDependsOn("AutoGameCenterP2ETaskList");
        taskBlackList = holder[0];
        modelFields.addField(taskBlackList);
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
        try {
            if (signIn != null && signIn.getValue().equals(Boolean.TRUE)) {
                doSignIn();
            }
            if (drawGold != null && drawGold.getValue().equals(Boolean.TRUE)) {
                doDrawGold();
            }
            if (autoBrowse != null && autoBrowse.getValue().equals(Boolean.TRUE)) {
                browseTasks();
            }
            // 领取独立成一步：重拉一次列表，刚完成的任务也能立刻领，不受上面开关影响
            if (autoReceive != null && autoReceive.getValue().equals(Boolean.TRUE)) {
                claimRewards();
            }
        } catch (Exception e) {
            Log.record(getName() + "异常：" + e.getClass().getSimpleName());
            Log.printStackTrace(e);
        }
    }

    /** 签到：先拉首页拿签到序列号，再签到 */
    private void doSignIn() {
        try {
            if (Status.hasFlagToday("gameCenterSignIn")) {
                Log.record(getName() + "⏭️今日已签到");
                return;
            }
            JSONObject home = request(RPC_HOME, homeParams());
            if (home == null) {
                Log.record(getName() + "⏭️首页查询失败，跳过签到");
                return;
            }
            JSONObject homeData = home.optJSONObject("data");
            JSONObject signUp = homeData == null ? null : homeData.optJSONObject("signUpModuleVO");
            if (signUp == null) {
                Log.record(getName() + "⏭️未拿到签到信息");
                return;
            }
            String seqId = signUp.optString("signSequenceId", "");
            if (seqId.isEmpty()) {
                Log.record(getName() + "⏭️签到序列号为空");
                return;
            }
            String date = signUp.optString("date", today());
            int index = signUp.optInt("index", 1);
            JSONObject result = request(RPC_SIGN, signParams(seqId, date, index));
            if (result != null) {
                Status.flagToday("gameCenterSignIn");
                Log.record(getName() + "✅签到成功");
            } else {
                Log.record(getName() + "❌签到失败");
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    /** 抽金币：直接抽，金币次日发放 */
    private void doDrawGold() {
        try {
            if (Status.hasFlagToday("gameCenterDrawGold")) {
                Log.record(getName() + "⏭️今日已抽金币");
                return;
            }
            JSONObject result = request(RPC_DRAW, drawParams());
            if (result != null) {
                JSONObject data = result.optJSONObject("data");
                JSONObject popup = data == null ? null : data.optJSONObject("popupInfoVO");
                String title = popup == null ? "" : popup.optString("mainTitle", "");
                Status.flagToday("gameCenterDrawGold");
                Log.record(getName() + "✅抽金币" + (title.isEmpty() ? "" : "：" + title));
            } else {
                Log.record(getName() + "❌抽金币失败");
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    private String homeParams() {
        return "[{\"__git\":\"" + GIT + "\",\"appId\":\"" + APP_ID + "\",\"canAddHome\":false,"
                + "\"deviceLevel\":\"high\",\"frontEndVersion\":\"" + FRONT_END_VERSION + "\","
                + "\"p2eVersion\":\"\",\"screenType\":10,\"source\":\"" + SOURCE + "\","
                + "\"subscribePanelCheck\":true,\"unityDeviceLevel\":\"high\"}]";
    }

    private String signParams(String seqId, String date, int index) {
        return "[{\"__git\":\"" + GIT + "\",\"appId\":\"" + APP_ID + "\",\"date\":\"" + date + "\","
                + "\"index\":" + index + ",\"p2eVersion\":\"\",\"signSequenceId\":\"" + seqId + "\","
                + "\"source\":\"" + SOURCE + "\"}]";
    }

    private String drawParams() {
        return "[{\"__git\":\"" + GIT + "\",\"appId\":\"" + APP_ID + "\",\"p2eVersion\":\"\"}]";
    }

    /** 做任务：浏览任务走报名 → 打开 → 完成；游戏任务走通用假玩 → 由任务列表复核状态 */
    private void browseTasks() {
        try {
            JSONObject taskList = request(RPC_TASKLIST, taskListParams());
            if (taskList == null) {
                Log.record(getName() + "⏭️任务列表查询失败");
                return;
            }
            JSONArray tasks = collectTasks(taskList);
            if (tasks.length() == 0) {
                return;
            }
            int done = 0;
            int gamePlayed = 0;
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String taskId = task.optString("taskId", "");
                String status = task.optString("taskStatus", "");
                String title = task.optString("title", taskId);
                if (taskId.isEmpty() || title.isEmpty()) {
                    continue;
                }
                rememberTaskTitle(title);

                // 已完成待领取的：交给 claimRewards 统一领，这里不做完成动作
                if ("COMPLETED".equals(status)) {
                    continue;
                }
                // 黑名单里的任务不再尝试；但开着「自动假玩游戏」时，
                // 像游戏的任务从黑名单里放出来重新验证一次，看假玩能不能做完并领到奖
                boolean retryGame = isAutoGamePlay() && GamePlaySimulator.isGameTask(task);
                if (isBlackListed(title) && !retryGame) {
                    continue;
                }
                if (retryGame && isBlackListed(title)) {
                    Log.record(getName() + "🔁黑名单游戏任务放出来重新验证[" + title + "]");
                }
                // 非浏览类型里，游戏任务交给通用假玩；其它（加桌面 / 订阅等）才拉黑
                if (!taskId.startsWith(BROWSE_TASK_PREFIX)) {
                    if (isAutoGamePlay() && GamePlaySimulator.isGameTask(task)) {
                        if (GamePlaySimulator.playForTask(getName(), task,
                                GamePlaySimulator.Scene.GENERIC, gamePlayed)) {
                            gamePlayed++;
                            Log.record(getName() + "🎮游戏任务已上报游玩[" + title + "]，等任务列表复核");
                        }
                        // 游戏任务不调用 platformTaskComplete 申报完成：
                        // 2026-11-13 实测按真实字段(activityId/gameAppId/chInfo/channel/gameId)
                        // 组合的 5 种形状一律回 CONFIGURATION_ERROR；真机玩到「领取」时手机端
                        // 完成类 RPC 也是 0 个。完成状态由服务端按真实游戏进度标记，客户端无申报口子。
                        // 保留假玩上报，能否领奖一律以任务列表复核为准。
                        // 游戏任务不进黑名单
                        continue;
                    }
                    if (isAutoBlackList()) {
                        addToBlackList(title);
                        Log.record(getName() + "🏴做不了的任务类型[" + title + "]#" + taskId + "，已自动拉黑");
                    }
                    continue;
                }
                // 浏览任务也可能指向小游戏：标题里带游戏 / 试玩的，先补一次游玩上报
                if (isAutoGamePlay() && isGameTitle(title)
                        && GamePlaySimulator.isGameTask(task)
                        && GamePlaySimulator.playForTask(getName(), task,
                                GamePlaySimulator.Scene.GENERIC, gamePlayed)) {
                    gamePlayed++;
                    Log.record(getName() + "🎮浏览任务含小游戏，已补上报游玩[" + title + "]");
                }
                // 当天已试过的不再重复报名 / 打开
                String todayFlag = "gameCenter::task::" + taskId;
                if (Status.hasFlagToday(todayFlag)) {
                    continue;
                }
                String taskToken = task.optString("taskToken", "");
                if (taskToken.isEmpty()) {
                    continue;
                }
                String jumpLink = task.optString("jumpLink", "");
                JSONObject ball = task.optJSONObject("floatingBall");
                int timeSeconds = ball == null ? 15 : ball.optInt("timeSeconds", 15);
                Log.record(getName() + "👀浏览任务[" + title + "]");
                // 未报名先报名
                if ("UN_SIGNUP".equals(status)) {
                    JSONObject sign = request(RPC_PLATFORM_SIGNUP, platformTaskParams(task));
                    if (sign != null) {
                        Log.record(getName() + "📋报名成功[" + title + "]");
                    }
                }
                // 打开任务链接停留
                if (!jumpLink.isEmpty() && openUrl(jumpLink)) {
                    int stay = browseStay == null || browseStay.getValue() == null
                            ? timeSeconds : browseStay.getValue();
                    TimeUtil.sleep(stay * 1000L);
                }
                // 完成
                JSONObject complete = request(RPC_PLATFORM_COMPLETE, platformTaskParams(task));
                if (complete != null) {
                    done++;
                    Status.flagToday(todayFlag);
                    Log.record(getName() + "✅浏览任务完成[" + title + "]");
                } else {
                    Log.record(getName() + "⚠️浏览任务完成失败[" + title + "]");
                    // 临时故障不当天封死：累计 3 次才停，连续多轮失败再按确认规则拉黑
                    String retryKey = "gameCenter::taskRetry::" + taskId;
                    int retries = Status.getIntFlagToday(retryKey) + 1;
                    Status.setIntFlagToday(retryKey, retries);
                    if (retries >= 3) {
                        Status.flagToday(todayFlag);
                    }
                    if (isAutoBlackList()) {
                        MessageUtil.MarkTaskBlackListConfirm(
                                BLACKLIST_MODEL, BLACKLIST_FIELD, BLACKLIST_TITLE, title);
                    }
                }
                TimeUtil.sleep(1000);
            }
            if (done > 0 || gamePlayed > 0) {
                Log.record(getName() + "✅本轮完成 " + done + " 个浏览任务、假玩 "
                        + gamePlayed + " 个游戏任务");
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    /**
     * 把任务列表里几处不同位置的任务合到一起：平台任务、游戏任务模块、以及直接挂在 data 下的列表。
     * 各版本返回的模块名不固定，多扫几处，游戏任务才不会漏掉。
     */
    private JSONArray collectTasks(JSONObject taskList) {
        JSONArray all = new JSONArray();
        JSONObject data = taskList == null ? null : taskList.optJSONObject("data");
        if (data == null) {
            return all;
        }
        // 真机抓包(2026-11-12/13)确认：游戏任务实际挂在 platformGameTaskModule.gameTaskList，
        // 之前只取了 platformTaskList、另三处路径全是错的，导致 21 条游戏任务(UNIFY@ 统一框架)
        // 从来没被读进来，游戏中心因此一直做不出游戏任务。
        JSONObject platformModule = data.optJSONObject("platformGameTaskModule");
        appendTasks(all, platformModule, "gameTaskList");
        appendTasks(all, platformModule, "platformTaskList");
        appendTasks(all, data.optJSONObject("gameTaskModule"), "gameTaskList");
        appendTasks(all, data, "gameTaskList");
        appendTasks(all, data, "taskList");
        return dedupTasks(all);
    }

    /** 同一任务可能同时出现在多个模块，按 taskId 去重，避免重复申报 / 重复领奖 */
    private static JSONArray dedupTasks(JSONArray src) {
        JSONArray out = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < src.length(); i++) {
            JSONObject t = src.optJSONObject(i);
            if (t == null) {
                continue;
            }
            String id = t.optString("taskId", "");
            if (!id.isEmpty() && !seen.add(id)) {
                continue;
            }
            out.put(t);
        }
        return out;
    }

    private void appendTasks(JSONArray all, JSONObject module, String key) {
        if (module == null) {
            return;
        }
        JSONArray arr = module.optJSONArray(key);
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t != null) {
                all.put(t);
            }
        }
    }

    private boolean isAutoGamePlay() {
        return autoGamePlay == null || Boolean.TRUE.equals(autoGamePlay.getValue());
    }

    private boolean isGameTitle(String title) {
        return title != null && (title.contains("游戏") || title.contains("试玩")
                || title.contains("玩一玩") || title.contains("小游戏"));
    }

    /** 任务是否处于「已完成、奖励待领取」状态；按钮文案各版本不一样，只认状态更稳 */
    private boolean claimable(JSONObject task) {
        String s = task.optString("taskStatus", "");
        return "COMPLETED".equals(s) || "FINISHED".equals(s)
                || "RECEIVABLE".equals(s) || "CAN_RECEIVE".equals(s);
    }

    /**
     * 领取「已完成」的任务奖励（含假玩后服务端推进到完成状态的游戏任务）。
     * <p>2026-11-09 真机抓包确认：{@link #RPC_TASK_RECEIVE} 成功后金币即时到账，
     * 任务从列表消失；响应 {@code data.receiveSuccess} 为是否真正领到。
     */
    private void claimRewards() {
        try {
            JSONObject taskList = request(RPC_TASKLIST, taskListParams());
            if (taskList == null) {
                Log.record(getName() + "⏭️任务列表查询失败，跳过领取");
                return;
            }
            JSONArray tasks = collectTasks(taskList);
            if (tasks.length() == 0) {
                return;
            }
            int received = 0;
            int coins = 0;
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null || !claimable(task)) {
                    continue;
                }
                String taskId = task.optString("taskId", "");
                String taskToken = task.optString("taskToken", "");
                String title = task.optString("title", taskId);
                if (taskId.isEmpty() || taskToken.isEmpty()) {
                    continue;
                }
                String flag = "gameCenter::received::" + taskId;
                if (Status.hasFlagToday(flag)) {
                    continue;
                }
                JSONObject resp = request(RPC_TASK_RECEIVE, receiveParams(task));
                JSONObject respData = resp == null ? null : resp.optJSONObject("data");
                if (respData != null && respData.optBoolean("receiveSuccess", false)) {
                    int coin = respData.optInt("coinAmount", task.optInt("goldCoinAmount", 0));
                    received++;
                    coins += coin;
                    Status.flagToday(flag);
                    Log.record(getName() + "🎁领取成功[" + title + "]#+" + coin + "金币");
                } else {
                    // 领奖失败不封死：服务端可能只是暂时没推进，下一轮再试
                    String retryKey = "gameCenter::receiveRetry::" + taskId;
                    int retries = Status.getIntFlagToday(retryKey) + 1;
                    Status.setIntFlagToday(retryKey, retries);
                    if (retries >= 3) {
                        Status.flagToday(flag);
                    }
                    Log.record(getName() + "⚠️领取失败[" + title + "]");
                }
                TimeUtil.sleep(1000);
            }
            if (received > 0) {
                Log.record(getName() + "🎁本轮领取 " + received + " 个任务，共 " + coins + " 金币");
                com.surexu.sesame.util.FeatureStats.add(getName(), "point", coins);
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    /** 领取参数与真机点击「领取」时一致 */
    private String receiveParams(JSONObject task) {
        return "[{\"__git\":\"" + GIT + "\",\"actionChannel\":\"taskList\","
                + "\"activityId\":\"P2E_PLATFORM_TASK\",\"appId\":\"" + APP_ID + "\","
                + "\"oriChInfo\":\"" + SOURCE + "\",\"p2eVersion\":\"\",\"source\":\"" + SOURCE + "\","
                + "\"taskId\":\"" + task.optString("taskId", "") + "\","
                + "\"taskToken\":\"" + task.optString("taskToken", "") + "\","
                + "\"taskType\":\"" + task.optString("taskType", "PLATFORM_TRAN_TASK") + "\"}]";
    }

    private boolean isAutoBlackList() {
        return autoBlackList != null && Boolean.TRUE.equals(autoBlackList.getValue());
    }

    private boolean isBlackListed(String title) {
        SelectModelField field = getBlackListField();
        return field != null && field.getValue() != null && field.contains(title);
    }

    private void addToBlackList(String title) {
        SelectModelField field = getBlackListField();
        if (field != null && field.contains(title)) {
            return;
        }
        MessageUtil.MarkTaskBlackList(BLACKLIST_MODEL, BLACKLIST_FIELD, BLACKLIST_TITLE, title);
    }

    /** 优先取配置里已加载的字段（进程内拉黑后要立刻读到），取不到再退回模块自己的引用 */
    private SelectModelField getBlackListField() {
        try {
            ModelFields mf = ConfigV2.INSTANCE.getModelFields(BLACKLIST_MODEL);
            if (mf != null) {
                Object f = mf.get(BLACKLIST_FIELD);
                if (f instanceof SelectModelField) {
                    return (SelectModelField) f;
                }
            }
        } catch (Throwable ignored) {
        }
        return taskBlackList;
    }

    private void rememberTaskTitle(String title) {
        synchronized (KNOWN_TASK_TITLES) {
            KNOWN_TASK_TITLES.add(title);
        }
    }

    private String taskListParams() {
        return "[{\"__git\":\"" + GIT + "\",\"appId\":\"" + APP_ID + "\",\"deviceLevel\":\"high\","
                + "\"frontEndVersion\":\"" + FRONT_END_VERSION + "\",\"p2eVersion\":\"\","
                + "\"panelLaunchableCheckMap\":{\"SET_HEAD_TASK\":false},\"sessionId\":\""
                + System.currentTimeMillis() + "\",\"setHeadPanelCheck\":false,\"source\":\""
                + SOURCE + "\",\"subscribePanelCheck\":true,\"unityDeviceLevel\":\"high\"}]";
    }

    /** 按真机抓包里页面自己发的形状补齐字段，任务类型从服务端下发里动态取，不写死。
     *  仅用于浏览任务与领奖（2026-11-13 实测有效），游戏任务申报走 gameCompleteParams。 */
    private String platformTaskParams(JSONObject task) {
        String taskId = task.optString("taskId", "");
        String taskToken = task.optString("taskToken", "");
        String taskType = task.optString("taskType", "PLATFORM_TRAN_TASK");
        return "[{\"__git\":\"" + GIT + "\",\"actionChannel\":\"taskList\","
                + "\"activityId\":\"P2E_PLATFORM_TASK\",\"appId\":\"" + APP_ID + "\","
                + "\"oriChInfo\":\"" + SOURCE + "\",\"p2eVersion\":\"\",\"source\":\"" + SOURCE + "\","
                + "\"taskId\":\"" + taskId + "\",\"taskToken\":\"" + taskToken + "\","
                + "\"taskType\":\"" + taskType + "\"}]";
    }

    /**
     * 【已停用·备查】游戏任务申报形状探测。
     * 2026-11-13 实测：下列 5 种参数形状（活动ID / 游戏appId / chInfo / channel / gameId
     * 全部按真机下发字段动态取）一律被服务端回 CONFIGURATION_ERROR，且真机玩到「领取」时
     * 手机端完成类 RPC 为 0，确认客户端没有 GAME_TRAN_TASK 的完成申报口子。
     * 故 browseTasks 不再调用；保留代码仅为将来支付宝改接口时可快速复验。
     */
    private static final String GAME_COMPLETE_PROBE = "gameCenter::gameCompleteProbeV2";
    private static final int GAME_COMPLETE_VARIANTS = 5;

    /** 从 UNIFY@<活动ID>@<游戏appId>@<序号> 里取指定段 */
    private static String unifyPart(String taskId, int index) {
        String[] parts = taskId.split("@");
        return parts.length > index ? parts[index] : "";
    }

    /** 从 jumpLink 中解出参数；query= 内层参数也一并解码后支持二次查找 */
    private static String findParam(String raw, String key) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String decoded = raw;
        try {
            decoded = java.net.URLDecoder.decode(raw, "UTF-8");
        } catch (Exception ignored) {
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:\\?|&|^)" + java.util.regex.Pattern.quote(key) + "=([^&]*)")
                .matcher(decoded);
        return m.find() ? m.group(1) : "";
    }

    /**
     * 游戏任务（GAME_TRAN_TASK）申报完成的参数形状。
     * <p>2026-11-13 实测：沿用浏览任务的写死形状会回 CONFIGURATION_ERROR。
     * 真实任务字段里带 UNIFY@<活动ID>、游戏自己的 appId、jumpLink 里的
     * chInfo / channel / oriChInfo，这里按不同组合给出几种候选，由服务端错误码定夺。
     */
    private String gameCompleteParams(JSONObject task, int variant) {
        String taskId = task.optString("taskId", "");
        String gameAppId = task.optString("appId", APP_ID);
        String activityId = unifyPart(taskId, 1);
        if (activityId.isEmpty()) {
            activityId = "P2E_PLATFORM_TASK";
        }
        String link = task.optString("jumpLink", "");
        String chInfo = findParam(link, "chInfo");
        if (chInfo.isEmpty()) {
            chInfo = SOURCE;
        }
        String inner = findParam(link, "query");
        String channel = findParam("?" + inner, "channel");
        if (channel.isEmpty()) {
            channel = "P2E_GAME_TRAN_CHANNEL";
        }
        String oriChInfo = findParam("?" + inner, "oriChInfo");
        if (oriChInfo.isEmpty()) {
            oriChInfo = SOURCE;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("__git", GIT);
            o.put("p2eVersion", "");
            o.put("taskId", taskId);
            o.put("taskToken", task.optString("taskToken", ""));
            o.put("taskType", task.optString("taskType", "GAME_TRAN_TASK"));
            switch (variant) {
                case 1: // 全动态：活动ID + 游戏appId + 页面 chInfo
                    o.put("activityId", activityId);
                    o.put("appId", gameAppId);
                    o.put("actionChannel", "taskList");
                    o.put("oriChInfo", chInfo);
                    o.put("source", chInfo);
                    break;
                case 2: // 只换活动ID，其余保持浏览任务已验证的形状
                    o.put("activityId", activityId);
                    o.put("appId", APP_ID);
                    o.put("actionChannel", "taskList");
                    o.put("oriChInfo", SOURCE);
                    o.put("source", SOURCE);
                    break;
                case 3: // 活动ID保留原名，换 appId 与 chInfo
                    o.put("activityId", "P2E_PLATFORM_TASK");
                    o.put("appId", gameAppId);
                    o.put("actionChannel", "taskList");
                    o.put("oriChInfo", chInfo);
                    o.put("source", chInfo);
                    break;
                case 4: // actionChannel 用页面下发的转化渠道
                    o.put("activityId", activityId);
                    o.put("appId", gameAppId);
                    o.put("actionChannel", channel);
                    o.put("oriChInfo", oriChInfo);
                    o.put("source", chInfo);
                    o.put("channel", channel);
                    break;
                default: // 全字段：把游戏标识、渠道、业务描述都带上
                    o.put("activityId", activityId);
                    o.put("appId", gameAppId);
                    o.put("actionChannel", channel);
                    o.put("oriChInfo", oriChInfo);
                    o.put("source", chInfo);
                    o.put("channel", channel);
                    o.put("chInfo", chInfo);
                    o.put("gameId", task.optString("gameId", ""));
                    o.put("gameAppId", gameAppId);
                    o.put("gameActionDesc", task.optString("gameActionDesc", ""));
                    break;
            }
            JSONArray arr = new JSONArray();
            arr.put(o);
            return arr.toString();
        } catch (Exception e) {
            Log.printStackTrace(e);
            return "[]";
        }
    }

    /** 发一次 RPC 拿原始响应（含 errorCode），失败不吞掉服务端提示 */
    private JSONObject requestRaw(String method, String data) {
        try {
            String resp = ApplicationHook.requestString(method, data, 1, -1);
            if (resp == null || resp.isEmpty()) {
                return null;
            }
            return new JSONObject(resp);
        } catch (Exception e) {
            Log.printStackTrace(e);
            return null;
        }
    }

    /** 游戏任务申报完成：每天只探测一次形状，成功形状当天复用，全失败当天不再刷 */
    private void declareGameTaskComplete(JSONObject task, String title) {
        int state = Status.getIntFlagToday(GAME_COMPLETE_PROBE);
        if (state == -1) {
            return;
        }
        if (state > 0) {
            JSONObject resp = requestRaw(RPC_PLATFORM_COMPLETE, gameCompleteParams(task, state));
            boolean ok = resp != null && resp.optBoolean("success", false);
            Log.record(getName() + "🧪游戏任务申报[" + title + "]#V" + state + (ok ? "✅已受理" : "未通过"));
            return;
        }
        for (int v = 1; v <= GAME_COMPLETE_VARIANTS; v++) {
            String params = gameCompleteParams(task, v);
            JSONObject resp = requestRaw(RPC_PLATFORM_COMPLETE, params);
            boolean ok = resp != null && resp.optBoolean("success", false);
            String code = resp == null ? "无响应"
                    : resp.optString("errorCode", resp.optString("resultCode", ""));
            String msg = resp == null ? ""
                    : resp.optString("errorMsg", resp.optString("resultMessage", ""));
            Log.record(getName() + "🧪申报探测[" + title + "]#V" + v + "→"
                    + (ok ? "✅成功" : code + "/" + msg));
            Log.debug(getName() + "申报探测V" + v + " 参数=" + params
                    + " 响应=" + (resp == null ? "null" : resp.toString()));
            if (ok) {
                Status.setIntFlagToday(GAME_COMPLETE_PROBE, v);
                Log.record(getName() + "✅游戏任务申报形状定为 V" + v + "，本轮后续任务复用");
                return;
            }
        }
        Status.setIntFlagToday(GAME_COMPLETE_PROBE, -1);
        Log.record(getName() + "⏭️游戏任务申报：" + GAME_COMPLETE_VARIANTS
                + " 种形状均未通过，今日不再重复申报");
    }

    /** 在支付宝进程内打开 alipays:// 链接 */
    private boolean openUrl(String url) {
        try {
            Context ctx = ApplicationHook.getContext();
            if (ctx == null || url.isEmpty()) {
                return false;
            }
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.record(getName() + "打开链接失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 当天日期 yyyy-MM-dd，签到参数 fallback 用 */
    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    /** 发一次 RPC，成功返回整个响应 JSONObject，失败返回 null */
    private JSONObject request(String method, String data) {
        try {
            String resp = ApplicationHook.requestString(method, data, 1, -1);
            if (resp == null || resp.isEmpty()) {
                return null;
            }
            JSONObject jo = new JSONObject(resp);
            if (jo.optBoolean("success", false)) {
                return jo;
            }
            // 失败响应完整打进抓包记录，便于照着服务端提示换下一条路子；不刷运行日志
            Log.debug(getName() + "抓包响应[" + method + "]: " + resp);
            return null;
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        return null;
    }
}
