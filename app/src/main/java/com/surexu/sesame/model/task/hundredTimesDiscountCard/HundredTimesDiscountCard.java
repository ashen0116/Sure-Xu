package com.surexu.sesame.model.task.hundredTimesDiscountCard;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.modelFieldExt.BooleanModelField;
import com.surexu.sesame.data.modelFieldExt.IntegerModelField;
import com.surexu.sesame.data.task.ModelTask;
import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.model.base.TaskCommon;
import com.surexu.sesame.model.common.gamePlay.GamePlaySimulator;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.Status;
import com.surexu.sesame.util.TimeUtil;
import com.surexu.sesame.util.idMap.UserIdMap;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 百次立减卡（支付宝 - 支付有礼里的「百次立减」）。
 *
 * <p>核心是「每日访问领立减次数」，接口命名空间
 * {@code alipay.ofpgrowth.hundredtimesdiscountcard.*}：
 * <ul>
 *   <li>签到：{@code signintask.query} 查 {@code signInTaskInfo}，拿到当天要触发的
 *       {@code needTriggerTaskId} / {@code taskCenterId}，再用通用的
 *       {@code alipay.promoprod.applet.trigger}（stageCode=send）触发签到；</li>
 *   <li>场景奖励：{@code promote.used.trigger} 四个场景（学生假期 / 固定周 / 渠道奖励 / 增强券），
 *       页面打开时自动送的次数；</li>
 *   <li>浏览任务：{@code task.listquery} 拉列表，只做 {@code TASK_TYPE=BROWSER} 的
 *       打开链接停留 + stageCode=receive 领取；{@code N_PAY} 真实支付类一律跳过；</li>
 *   <li>查余额：{@code info.query} 读剩余次数 / 累计立减金额。</li>
 * </ul>
 *
 * <p>注意：浏览任务的「领取」动作用的是 stageCode=receive（参照支付有礼），
 * 抓包时只看到签到（send），浏览领取这条如果对不上，改 stageCode 即可。
 */
public class HundredTimesDiscountCard extends ModelTask {

    private static final String PREFIX = "alipay.ofpgrowth.hundredtimesdiscountcard.";
    private static final String RPC_INFO = PREFIX + "info.query";
    private static final String RPC_SIGN_QUERY = PREFIX + "signintask.query";
    private static final String RPC_TASK_QUERY = PREFIX + "task.listquery";
    private static final String RPC_PROMOTE = PREFIX + "promote.used.trigger";
    private static final String RPC_APP_TRIGGER = "alipay.promoprod.applet.trigger";
    private static final String RPC_TASK_RECEIVE = PREFIX + "task.receive";

    private static final String CH = "ch_appcenter__chsub_myFavorite";

    /**
     * 下面三个接口是 facade RPC(走 appName/facadeName/methodName 路由),
     * 不能用普通 operationType 直接调(会无响应)。这些字段通过 relation 参数
     * 合并进 params 顶层(见 NewRpcBridge.buildExtraParams)。
     */
    private static final String FACADE_BASE = "\"appName\":\"ofpgrowth\",\"facadeName\":\"HundredTimesDiscountCardRpcService\",\"getResponse\":true";
    private static final String FACADE_PROMOTE = "{" + FACADE_BASE + ",\"methodName\":\"triggerPromoteUsed\"}";
    private static final String FACADE_TASK_QUERY = "{" + FACADE_BASE + ",\"methodName\":\"queryTaskList\"}";
    private static final String FACADE_TASK_RECEIVE = "{" + FACADE_BASE + ",\"methodName\":\"taskReceive\"}";

    /** 场景奖励：{场景名, 是否带 sign 字段} */
    private static final String[][] SCENES = {
            {"STUDENT_VACATION", ""},
            {"FIX_WEEKLY", ""},
            {"CHANNEL_REWARD", ",\"sign\":\"\""},
            {"ENHANCE_COUPON", ""},
    };

    private BooleanModelField signIn;
    private BooleanModelField sceneReward;
    private BooleanModelField autoBrowse;
    private BooleanModelField autoGamePlay;
    private IntegerModelField browseStay;
    private BooleanModelField showBalance;

    @Override
    public String getName() {
        return "百次立减";
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
        signIn = new BooleanModelField("signIn", "每日签到领次数", true);
        modelFields.addField(signIn);
        sceneReward = new BooleanModelField("sceneReward", "场景奖励", true);
        sceneReward.setDescription("打开页面送的次数");
        modelFields.addField(sceneReward);
        autoBrowse = new BooleanModelField("autoBrowse", "自动做浏览任务", true);
        autoBrowse.setDescription("浏览得次数");
        modelFields.addField(autoBrowse);
        autoGamePlay = new BooleanModelField("autoGamePlay", "自动假玩游戏任务", true);
        autoGamePlay.setDescription("任务里要玩小游戏时，自动上报游玩时长，不用真开游戏");
        modelFields.addField(autoGamePlay);
        browseStay = new IntegerModelField("browseStay", "浏览停留(秒)", 12, 10, 30);
        modelFields.addField(browseStay);
        showBalance = new BooleanModelField("showBalance", "记录剩余次数", true);
        modelFields.addField(showBalance);
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
            if (showBalance != null && showBalance.getValue().equals(Boolean.TRUE)) {
                queryBalance();
            }
            if (signIn != null && signIn.getValue().equals(Boolean.TRUE)) {
                doSignIn();
            }
            if (sceneReward != null && sceneReward.getValue().equals(Boolean.TRUE)) {
                doSceneRewards();
            }
            if (autoBrowse != null && autoBrowse.getValue().equals(Boolean.TRUE)) {
                doBrowseTasks();
            }
        } catch (Exception e) {
            Log.record(getName() + "异常：" + e.getClass().getSimpleName());
            Log.printStackTrace(e);
        }
    }

    /** 查卡片信息，记录剩余次数 / 累计立减 */
    private void queryBalance() {
        try {
            JSONObject jo = request(RPC_INFO, infoParams());
            if (jo == null) {
                Log.record(getName() + "⏭️卡片信息查询失败");
                return;
            }
            JSONObject card = jo.optJSONObject("data").optJSONObject("discountCardInfo");
            if (card == null) {
                return;
            }
            int remaining = card.optInt("remainingTimes", -1);
            int usedTimes = card.optInt("usedTimes", 0);
            int totalTimes = card.optInt("totalTimes", 0);
            JSONObject usedAmount = card.optJSONObject("usedTotalAmount");
            String amount = usedAmount == null ? "" : usedAmount.optString("amount", "");
            if (remaining >= 0) {
                Log.record(getName() + "：剩余 " + remaining + " 次，已用 " + usedTimes + "/" + totalTimes
                        + (amount.isEmpty() ? "" : "，累计立减 " + amount + " 元"));
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    /** 每日签到领次数 */
    private void doSignIn() {
        try {
            JSONObject jo = request(RPC_SIGN_QUERY, "[{}]");
            if (jo == null) {
                Log.record(getName() + "⏭️签到状态查询失败");
                return;
            }
            JSONObject info = jo.optJSONObject("data").optJSONObject("signInTaskInfo");
            if (info == null) {
                Log.record(getName() + "⏭️未取到签到信息");
                return;
            }
            String needTaskId = info.optString("needTriggerTaskId", "");
            String taskCenterId = info.optString("taskCenterId", "");
            if (needTaskId.isEmpty() || taskCenterId.isEmpty()) {
                Log.record(getName() + "⏭️今日无可签到任务");
                return;
            }
            if (Status.hasFlagToday("htdcSignIn")) {
                Log.record(getName() + "⏭️今日已签到");
                return;
            }
            // 第一步：触发签到任务（send）
            String data = "[{\"appletId\":\"" + needTaskId + "\",\"stageCode\":\"send\",\"taskCenId\":\""
                    + taskCenterId + "\"}]";
            JSONObject r = request(RPC_APP_TRIGGER, data);
            if (!isOk(r)) {
                Log.record(getName() + "❌签到触发失败");
                return;
            }
            // 第二步：领取确认（开心收下）
            String receiveData = "[{\"chInfo\":\"signInTask\",\"taskId\":\"" + needTaskId + "\"}]";
            JSONObject r2 = request(RPC_TASK_RECEIVE, receiveData, FACADE_TASK_RECEIVE);
            boolean received = isOk(r2);
            Status.flagToday("htdcSignIn");
            Log.record(getName() + "✅签到成功" + (received ? "" : "（领取确认未返回）"));
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    /** 是否调用成功：响应顶层 success 为 true */
    private boolean isOk(JSONObject resp) {
        return resp != null && resp.optBoolean("success", false);
    }

    /** 场景奖励（页面打开自动送的次数） */
    private void doSceneRewards() {
        int done = 0;
        for (String[] scene : SCENES) {
            String name = scene[0];
            if (Status.hasFlagToday("htdcScene_" + name)) {
                continue;
            }
            String data = "[{\"chInfo\":\"" + CH + "\",\"extInfo\":{\"scene\":\"" + name + "\"" + scene[1] + "}}]";
            JSONObject r = request(RPC_PROMOTE, data, FACADE_PROMOTE);
            if (isOk(r)) {
                Status.flagToday("htdcScene_" + name);
                done++;
            }
            TimeUtil.sleep(800);
        }
        if (done > 0) {
            Log.record(getName() + "✅场景奖励领取 " + done + " 个");
        }
    }

    /** 做任务：BROWSER 打开链接停留后领取；游戏任务先假玩，再走同一个领取动作 */
    private void doBrowseTasks() {
        try {
            JSONObject jo = request(RPC_TASK_QUERY, "[{\"extInfo\":{\"taskId\":\"\"}}]", FACADE_TASK_QUERY);
            if (jo == null) {
                Log.record(getName() + "⏭️任务列表查询失败");
                return;
            }
            JSONObject data = jo.optJSONObject("data");
            JSONArray taskList = data == null ? null : data.optJSONArray("taskList");
            if (taskList == null || taskList.length() == 0) {
                return;
            }
            int done = 0;
            int skipped = 0;
            int gamePlayed = 0;
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                JSONObject props = task.optJSONObject("taskExtProps");
                String type = props == null ? "" : props.optString("TASK_TYPE", "");
                String taskId = task.optString("taskId", "");
                String title = props == null ? taskId : props.optString("taskTitle", taskId);
                // 非 BROWSER 的游戏任务放行；BROWSER 里标题带游戏 / 试玩的也按游戏任务处理
                boolean game = isAutoGamePlay() && GamePlaySimulator.isGameTask(task)
                        && (!"BROWSER".equals(type) || isGameTitle(title));
                if (!"BROWSER".equals(type) && !game) {
                    if (!type.isEmpty()) {
                        skipped++;
                    }
                    continue;
                }
                String status = task.optString("taskStatus", "");
                if (status.startsWith("COMPLETE") || status.startsWith("FINISH")
                        || "DONE".equals(status) || "RECEIVED".equals(status)) {
                    continue;
                }
                if (taskId.isEmpty()) {
                    continue;
                }
                String link = props == null ? "" : props.optString("btnLinkUrl", "");
                int browse = parseBrowse(props);
                if (game) {
                    if (!GamePlaySimulator.playForTask(getName(), task,
                            GamePlaySimulator.Scene.GENERIC, gamePlayed)) {
                        Log.record(getName() + "⏭️游戏任务[" + title
                                + "]缺游戏id或本轮已满，跳过（日志里有字段，抓包后可补）");
                        continue;
                    }
                    gamePlayed++;
                    Log.record(getName() + "🎮游戏任务已上报游玩[" + title + "]");
                } else {
                    Log.record(getName() + "👀浏览任务[" + title + "]");
                }
                // 打开链接停留（服务端按停留时间判定浏览）
                if (!game && !link.isEmpty() && openUrl(link)) {
                    int stay = browseStay == null || browseStay.getValue() == null
                            ? browse : browseStay.getValue();
                    TimeUtil.sleep(stay * 1000L);
                }
                // 领取（task.receive 只传 taskId，不带 chInfo）；游戏任务是假玩后复核
                String receiveData = "[{\"taskId\":\"" + taskId + "\"}]";
                JSONObject r = request(RPC_TASK_RECEIVE, receiveData, FACADE_TASK_RECEIVE);
                if (isOk(r)) {
                    done++;
                    com.surexu.sesame.util.FeatureStats.add(getName(), "point", 1);
                    Log.record(getName() + "✅任务完成[" + title + "]");
                } else {
                    Log.record(getName() + "⚠️任务领取失败[" + title + "]"
                            + (r == null ? "" : "：" + r.optString("errorMsg", "")));
                }
                TimeUtil.sleep(1000);
            }
            if (done > 0 || gamePlayed > 0) {
                Log.record(getName() + "✅本轮完成 " + done + " 个任务（假玩 "
                        + gamePlayed + " 个游戏任务）"
                        + (skipped > 0 ? "，跳过 " + skipped + " 个支付类" : ""));
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    private boolean isAutoGamePlay() {
        return autoGamePlay == null || Boolean.TRUE.equals(autoGamePlay.getValue());
    }

    private boolean isGameTitle(String title) {
        return title != null && (title.contains("游戏") || title.contains("试玩")
                || title.contains("玩一玩") || title.contains("小游戏"));
    }

    /** 解析浏览秒数，默认 10 */
    private int parseBrowse(JSONObject props) {
        if (props == null) {
            return 10;
        }
        try {
            return Integer.parseInt(props.optString("browseTime", "10"));
        } catch (Exception e) {
            return 10;
        }
    }

    /** info.query 参数：chInfo + 空 extInfo 模板（与页面抓包一致） */
    private String infoParams() {
        return "[{\"chInfo\":\"" + CH + "\",\"extInfo\":{\"LANDING_PAGE_INFO\":\"\",\"action\":\"\","
                + "\"addTimesFlag\":\"false\",\"appendCount\":\"\",\"benefit\":\"\",\"bizNo\":\"\","
                + "\"chInfo\":\"" + CH + "\",\"directReceiveFlag\":\"false\",\"extend001\":\"\","
                + "\"isvPid\":\"\",\"mainChannelInstId\":\"\",\"operatorAlipayUid\":\"\",\"pageModule\":\"\","
                + "\"partnerId\":\"\",\"pid\":\"\",\"sceneCode\":\"\",\"shareId\":\"\",\"shareUserId\":\"\","
                + "\"sign\":\"\",\"subChInfo\":\"\",\"taskId\":\"\",\"token\":\"\",\"tradeNo\":\"\","
                + "\"triggerChannel\":\"" + CH + "\"},\"subChInfo\":\"\"}]";
    }

    /** 发一次 RPC，返回整个响应 JSONObject；网络失败或解析失败返回 null */
    private JSONObject request(String method, String data) {
        try {
            String resp = ApplicationHook.requestString(method, data, 1, -1);
            if (resp == null || resp.isEmpty()) {
                return null;
            }
            return new JSONObject(resp);
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        return null;
    }

    /** 发一次 facade RPC：relation 传 facade 参数(appName/facadeName/methodName/getResponse) */
    private JSONObject request(String method, String data, String facade) {
        try {
            String resp = ApplicationHook.requestString(method, data, facade, 1, -1);
            if (resp == null || resp.isEmpty()) {
                return null;
            }
            return new JSONObject(resp);
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        return null;
    }

    /** 在支付宝进程内打开 alipays:// 链接 */
    private boolean openUrl(String url) {
        try {
            if (url == null || url.isEmpty()) {
                return false;
            }
            Context ctx = ApplicationHook.getContext();
            if (ctx == null) {
                return false;
            }
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
            Log.record(getName() + "打开链接失败：" + t.getClass().getSimpleName());
            return false;
        }
    }
}
