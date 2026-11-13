package com.surexu.sesame.model.task.youthPrivilege;

import android.content.Context;
import android.content.Intent;

import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.modelFieldExt.BooleanModelField;
import com.surexu.sesame.data.modelFieldExt.IntegerModelField;
import com.surexu.sesame.data.modelFieldExt.SelectModelField;
import com.surexu.sesame.data.task.ModelTask;
import com.surexu.sesame.entity.YouthExchangeBenefit;
import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.model.base.TaskCommon;
import com.surexu.sesame.model.task.antForest.Privilege;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.Status;
import com.surexu.sesame.util.TimeUtil;
import com.surexu.sesame.util.idMap.UserIdMap;
import com.surexu.sesame.util.idMap.YouthExchangeBenefitIdMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 青春特权「赚青春豆」：签到 + 浏览任务 + 兑换 + 公益道具 + 抽奖。
 *
 * <p>接口全部走 {@code com.alipay.mobileopl.youthprivilege.rpc.mgw.*}：
 * <ul>
 *   <li>签到：{@code queryCheckInModel} 查 action，{@code checkIn} 执行；</li>
 *   <li>浏览任务：{@code queryTaskModel} 拉列表，PROCESSING / TO_APPLY 都做，
 *       先 {@code taskSignUp} 报名、打开 taskUrl 停留，再依次试几条完成上报通道，
 *       每报一次重拉列表复核 currentCount，涨了才算做成；</li>
 *   <li>兑换：{@code queryExchangeModel} 拉列表，{@code exchange} 按勾选的 benefitId 兑换；</li>
 *   <li>抽奖：{@code queryLotteryEntrance} 拉奖池，动作接口尚未抓到，只记录。</li>
 * </ul>
 *
 * <p>兑换列表像森林「兑换权益」那样做成可勾选：先查列表写进
 * {@link YouthExchangeBenefitIdMap}（供 UI 读），再按选中项逐个兑换。
 */
public class YouthPrivilegeTasks extends ModelTask {

    private static final String PREFIX = "com.alipay.mobileopl.youthprivilege.rpc.mgw.";

    private static final String RPC_CHECK_QUERY = PREFIX + "queryCheckInModel";
    private static final String RPC_CHECK_IN = PREFIX + "checkIn";
    private static final String RPC_TASK = PREFIX + "queryTaskModel";
    private static final String RPC_SIGNUP = PREFIX + "taskSignUp";
    private static final String RPC_EXCHANGE_QUERY = PREFIX + "queryExchangeModel";
    private static final String RPC_EXCHANGE = PREFIX + "exchange";
    private static final String RPC_LOTTERY = PREFIX + "queryLotteryEntrance";

    /** 任务「做完」的几条候选通道，按服务端下发的字段现拼参数，不写死任务号。 */
    private static final String RPC_SDK_TRIGGER = "com.alipay.pcreditbfweb.sdk.task.trigger";
    private static final String RPC_PROMO_TRIGGER = "alipay.promoprod.applet.trigger";
    private static final String RPC_COMPLETE_NOTICE = "alipay.promoprod.task.complete.notice";
    private static final String RPC_DIRECT_COMPLETE = "com.alipay.fundscenebff.needle.taskDirectPrize.complete";
    private static final String RPC_ANTIEP_FINISH = "com.alipay.antiep.finishTask";
    private static final String RPC_TASK_COMPLETE = PREFIX + "taskComplete";

    private static final String ROUTE_KEY = "youthPrivilege::completeRoute";
    private static final int ROUTE_COUNT = 6;

    private static final String CH = "ch_appcenter__chsub_9patch";
    private static final String TASK_QUERY = "[{\"chInfo\":\"" + CH + "\",\"skipTaskList\":false}]";

    private BooleanModelField checkIn;
    private BooleanModelField autoBrowse;
    private IntegerModelField browseStay;
    private BooleanModelField exchangeBenefit;
    private SelectModelField exchangeList;
    private BooleanModelField claimProps;

    @Override
    public String getName() {
        return "青春特权";
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
        checkIn = new BooleanModelField("checkIn", "领取签到奖励", true);
        modelFields.addField(checkIn);
        autoBrowse = new BooleanModelField("autoBrowse", "自动做浏览任务", true);
        autoBrowse.setDescription("跳页面停十几秒");
        modelFields.addField(autoBrowse);
        browseStay = new IntegerModelField("browseStay", "浏览停留(秒)", 16, 10, 30);
        browseStay.setDescription("够15秒算完成");
        modelFields.addField(browseStay);
        exchangeBenefit = new BooleanModelField("exchangeBenefit", "自动兑换", true);
        modelFields.addField(exchangeBenefit);
        exchangeList = new SelectModelField("exchangeList", "兑换列表", new LinkedHashSet<>(),
                YouthExchangeBenefit::getList, "勾选要兑换的红包").setDependsOn("exchangeBenefit");
        modelFields.addField(exchangeList);
        claimProps = new BooleanModelField("claimProps", "领取公益道具", true);
        claimProps.setDescription("双击卡/时光加速器/保护罩");
        modelFields.addField(claimProps);
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
            if (checkIn != null && checkIn.getValue().equals(Boolean.TRUE)) {
                doCheckIn();
            }
            if (autoBrowse != null && autoBrowse.getValue().equals(Boolean.TRUE)) {
                browseTasks();
            }
            if (exchangeBenefit != null && exchangeBenefit.getValue().equals(Boolean.TRUE)) {
                exchange();
            }
            if (claimProps != null && claimProps.getValue().equals(Boolean.TRUE)) {
                Privilege.youthPrivilege();
            }
            logLottery();
        } catch (Exception e) {
            Log.record(getName() + "异常：" + e.getClass().getSimpleName());
            Log.printStackTrace(e);
        }
    }

    /** 签到 */
    private void doCheckIn() {
        try {
            JSONObject jo = request(RPC_CHECK_QUERY,
                    "[{\"chInfo\":\"" + CH + "\",\"queryAd\":true,\"skipTaskModule\":false}]");
            if (jo == null) {
                Log.record(getName() + "⏭️签到状态查询失败");
                return;
            }
            JSONObject info = jo.optJSONObject("studentCheckInInfo");
            String action = info == null ? "" : info.optString("action", "");
            if (!"CHECK_IN".equals(action)) {
                // 实测：签过之后页面会变成 DO_TASK（"去做任务"），不是没签到、也不是坏了。
                Log.record(getName() + "⏭️签到状态=" + (action.isEmpty() ? "空" : action)
                        + "（今日已领过，或要等下一轮签到时段）");
                return;
            }
            if (Status.hasFlagToday("youthCheckIn")) {
                Log.record(getName() + "⏭️今日已签到");
                return;
            }
            JSONObject result = request(RPC_CHECK_IN, "[{\"chInfo\":\"" + CH + "\"}]");
            if (result != null) {
                Status.flagToday("youthCheckIn");
                Log.record(getName() + "✅签到成功");
            } else {
                Log.record(getName() + "❌签到失败");
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    /** 拉任务：报名 -> 打开停留 -> 报完成 -> 重拉列表复核，按服务端下发的次数做满 */
    private void browseTasks() {
        try {
            JSONObject jo = request(RPC_TASK, TASK_QUERY);
            if (jo == null) {
                Log.record(getName() + "⏭️任务列表查询失败");
                return;
            }
            JSONArray tasks = collectTasks(jo);
            if (tasks.length() == 0) {
                Log.record(getName() + "⏭️本轮服务端没下发任务");
                return;
            }
            int done = 0;
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String type = task.optString("taskType", "");
                String status = task.optString("taskStatus", "");
                String action = task.optString("taskAction", "");
                String name = task.optString("taskName", "");
                String code = task.optString("taskCode", "");
                if (code.isEmpty()) {
                    continue;
                }
                if (!"BROWSER".equals(type)) {
                    Log.record(getName() + "⏭️非浏览任务[" + name + "]类型=" + type + "，先不动");
                    continue;
                }
                int current = task.optInt("currentCount", 0);
                int total = Math.max(1, task.optInt("totalCount", 1));
                if (current >= total || isFinished(status)) {
                    Log.record(getName() + "✅任务[" + name + "]已完成 " + current + "/" + total);
                    continue;
                }
                Log.record(getName() + "👀任务[" + name + "] " + current + "/" + total
                        + " 状态=" + status + "/" + action);
                Log.record(getName() + "任务原始内容：" + clip(task.toString()));
                Log.debug(getName() + "任务原始内容：" + task);
                int after = completeWithRoutes(task, current, total);
                if (after > current) {
                    done++;
                    Log.record(getName() + "🎉任务[" + name + "]进度 " + current + "→" + after + "/" + total);
                } else {
                    Log.record(getName() + "❌任务[" + name + "]进度没涨，仍是 " + after + "/" + total);
                }
            }
            Log.record(getName() + "✅本轮做成 " + done + "/" + tasks.length() + " 个任务");
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    /** 推荐位、各组列表都收进来，按 taskCode 去重 */
    private JSONArray collectTasks(JSONObject jo) {
        JSONArray all = new JSONArray();
        JSONObject mod = jo.optJSONObject("studentTaskModule");
        if (mod == null) {
            return all;
        }
        JSONObject rec = mod.optJSONObject("checkInRecommendTask");
        if (rec != null) {
            all.put(rec);
        }
        JSONArray groups = mod.optJSONArray("taskGroupList");
        if (groups != null) {
            for (int g = 0; g < groups.length(); g++) {
                JSONObject group = groups.optJSONObject(g);
                if (group == null) {
                    continue;
                }
                JSONArray list = group.optJSONArray("taskList");
                if (list == null) {
                    continue;
                }
                for (int i = 0; i < list.length(); i++) {
                    all.put(list.opt(i));
                }
            }
        }
        JSONArray out = new JSONArray();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            if (t == null) {
                continue;
            }
            String code = t.optString("taskCode", "");
            if (!code.isEmpty() && !seen.add(code)) {
                continue;
            }
            out.put(t);
        }
        return out;
    }

    /**
     * 一次一次把次数做满：每次先打开链接停留，再依次试几条「报完成」的路子；
     * 每报一次都重拉任务列表看 currentCount 有没有涨，涨了才算数。
     */
    private int completeWithRoutes(JSONObject task, int current, int total) {
        String code = task.optString("taskCode", "");
        String url = task.optString("taskUrl", "");
        int stay = browseStay == null || browseStay.getValue() == null ? 16 : browseStay.getValue();
        int known = Status.getIntFlagToday(ROUTE_KEY);
        int now = current;
        for (int round = current; round < total; round++) {
            // 2026-11-13 实测：直接报完成会回 10002901「任务报名记录不存在或已过期」，
            // 报名记录有时效，每做一次之前都要重新报名，且和完成上报共用同一个流水号。
            signupForRound(task);
            if (!url.isEmpty() && openUrl(url)) {
                TimeUtil.sleep(stay * 1000L);
            } else if (url.isEmpty()) {
                Log.record(getName() + "⚠️任务没有跳转链接，直接试完成上报");
            }
            now = queryProgress(code, now);
            if (now > round) {
                Log.record(getName() + "✅页面自己记了完成，进度 " + now);
                continue;
            }
            boolean advanced = false;
            for (int pass = 0; pass < 2 && !advanced; pass++) {
                for (int r = 1; r <= ROUTE_COUNT && !advanced; r++) {
                    if (known > 0 && ((pass == 0 && r != known) || (pass == 1 && r == known))) {
                        continue;
                    }
                    if (known == 0 && pass == 1) {
                        break;
                    }
                    String[] route = completeRoute(r, task);
                    if (route == null) {
                        continue;
                    }
                    String resp = requestRaw(route[0], route[1]);
                    Log.record(getName() + "🔍路子" + r + "[" + route[0] + "] " + brief(resp));
                    Log.record(getName() + "🔍路子" + r + " 参数：" + clip(route[1])
                            + " 响应：" + clip(resp));
                    Log.debug(getName() + "🔍路子" + r + " 参数：" + route[1] + " 完整响应：" + resp);
                    int after = queryProgress(code, now);
                    if (after > now) {
                        now = after;
                        known = r;
                        Status.setIntFlagToday(ROUTE_KEY, r);
                        Log.record(getName() + "🎯路子" + r + "有效，进度→" + now);
                        advanced = true;
                    }
                }
            }
            if (!advanced) {
                Log.record(getName() + "⏹️几条路子都试过，进度没涨，本轮先停");
                break;
            }
        }
        return queryProgress(code, now);
    }

    /** 每次完成前补报名：两条通道都试，服务端认哪条算哪条 */
    private void signupForRound(JSONObject task) {
        String code = task.optString("taskCode", "");
        String cent = task.optString("taskCentId", "");
        String outBizNo = code + today();
        String signupArgs = signupData(task);
        String a = requestRaw(RPC_SIGNUP, signupArgs);
        String triggerArgs = "[{\"appletId\":\"" + code + "\",\"extInfo\":{},\"needleParam\":{},"
                + "\"outBizNo\":\"" + outBizNo + "\",\"retryFlag\":true,"
                + "\"stageCode\":\"signup\",\"taskCenId\":\"" + cent + "\"}]";
        String b = requestRaw(RPC_SDK_TRIGGER, triggerArgs);
        Log.record(getName() + "📋报名A[taskSignUp] " + brief(a)
                + " ｜ 报名B[trigger/signup] " + brief(b));
        Log.debug(getName() + "📋报名A 参数：" + signupArgs + " 响应：" + a
                + " ｜ 报名B 参数：" + triggerArgs + " 响应：" + b);
    }

    /** 重拉列表，看某个任务当前做了几次；查不到就返回原值，绝不假装涨了 */
    private int queryProgress(String code, int fallback) {
        JSONObject jo = request(RPC_TASK, TASK_QUERY);
        if (jo == null) {
            return fallback;
        }
        JSONArray tasks = collectTasks(jo);
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t != null && code.equals(t.optString("taskCode", ""))) {
                int c = t.optInt("currentCount", fallback);
                Log.record(getName() + "🔄复核[" + t.optString("taskName", code) + "]进度=" + c
                        + " 状态=" + t.optString("taskStatus", "") + "/" + t.optString("taskAction", ""));
                return c;
            }
        }
        return fallback;
    }

    /** 几条候选通道，参数全部取自服务端下发的任务内容 */
    private String[] completeRoute(int route, JSONObject task) {
        String code = task.optString("taskCode", "");
        String cent = task.optString("taskCentId", "");
        String source = task.optString("taskSource", "PROMOKERNEL");
        String type = task.optString("taskType", "BROWSER");
        String outBizNo = code + today();
        switch (route) {
            case 1:
                return new String[]{RPC_SDK_TRIGGER,
                        "[{\"appletId\":\"" + code + "\",\"extInfo\":{},\"needleParam\":{},"
                                + "\"outBizNo\":\"" + outBizNo + "\",\"retryFlag\":true,"
                                + "\"stageCode\":\"send\",\"taskCenId\":\"" + cent + "\"}]"};
            case 2:
                return new String[]{RPC_PROMO_TRIGGER,
                        "[{\"appletId\":\"" + code + "\",\"chInfo\":\"" + CH + "\","
                                + "\"outbizno\":\"" + outBizNo + "\",\"stageCode\":\"send\","
                                + "\"taskCenInfo\":\"" + cent + "\"}]"};
            case 3:
                return new String[]{RPC_COMPLETE_NOTICE,
                        "[{\"chInfo\":\"" + CH + "\",\"taskCenInfo\":\"" + cent
                                + "\",\"taskCenInfos\":[\"" + cent + "\"]}]"};
            case 4:
                return new String[]{RPC_DIRECT_COMPLETE,
                        "[{\"taskCenId\":\"" + cent + "\",\"taskId\":\"" + code + "\"}]"};
            case 5:
                return new String[]{RPC_ANTIEP_FINISH,
                        "[{\"sceneCode\":\"" + cent + "\",\"taskType\":\"" + type
                                + "\",\"outBizNo\":\"" + outBizNo + "\"}]"};
            case 6:
                return new String[]{RPC_TASK_COMPLETE,
                        "[{\"taskCode\":\"" + code + "\",\"taskCentId\":\"" + cent
                                + "\",\"taskSource\":\"" + source + "\",\"taskType\":\"" + type
                                + "\",\"chInfo\":\"" + CH + "\"}]"};
            default:
                return null;
        }
    }

    private static String signupData(JSONObject task) {
        return "[{\"taskCode\":\"" + task.optString("taskCode", "")
                + "\",\"taskSource\":\"" + task.optString("taskSource", "PROMOKERNEL")
                + "\",\"taskType\":\"" + task.optString("taskType", "BROWSER") + "\"}]";
    }

    private static boolean isFinished(String status) {
        return "COMPLETE".equals(status) || "FINISHED".equals(status)
                || "DONE".equals(status) || "SUCCESS".equals(status)
                || "RECEIVE_SUCCESS".equals(status);
    }

    /** 探测用：失败响应也要原样拿回来打进日志，不能只留个 null */
    private String requestRaw(String method, String data) {
        try {
            String resp = ApplicationHook.requestString(method, data, 1, -1);
            return resp == null || resp.isEmpty() ? "空响应" : resp;
        } catch (Throwable t) {
            return "异常:" + t.getClass().getSimpleName();
        }
    }

    private static String clip(String s) {
        if (s == null) {
            return "无";
        }
        return s.length() > 900 ? s.substring(0, 900) + "…" : s;
    }

    private static String brief(String resp) {
        if (resp == null) {
            return "无响应";
        }
        try {
            JSONObject jo = new JSONObject(resp);
            return "success=" + jo.optBoolean("success", false)
                    + " code=" + jo.optString("resultCode", jo.optString("errorCode", ""))
                    + " msg=" + jo.optString("resultMessage", jo.optString("errorMsg", ""));
        } catch (Exception e) {
            return resp.length() > 160 ? resp.substring(0, 160) : resp;
        }
    }

    private static String today() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return sdf.format(new Date());
    }

    /** 兑换：先刷新列表写 idMap（供 UI 勾选），再按选中项兑换 */
    private void exchange() {
        try {
            JSONObject jo = request(RPC_EXCHANGE_QUERY, "[{\"chInfo\":\"" + CH + "\"}]");
            if (jo == null) {
                Log.record(getName() + "⏭️兑换列表查询失败");
                return;
            }
            JSONArray list = jo.optJSONArray("exchangeBenefitInfoList");
            if (list == null || list.length() == 0) {
                return;
            }
            // 刷新 idMap，供 UI 侧展示可选兑换项
            String uid = UserIdMap.getCurrentUid();
            YouthExchangeBenefitIdMap.load(uid);
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                YouthExchangeBenefitIdMap.add(
                        item.optString("benefitId", ""),
                        item.optString("title", "") + "(" + item.optString("value", "?") + "豆)");
            }
            YouthExchangeBenefitIdMap.save(uid);
            // 执行选中项
            if (exchangeList == null || exchangeList.getValue() == null || exchangeList.getValue().isEmpty()) {
                return;
            }
            for (String benefitId : exchangeList.getValue()) {
                if (Status.hasFlagToday("youthExchange_" + benefitId)) {
                    continue;
                }
                JSONObject r = request(RPC_EXCHANGE,
                        "[{\"benefitId\":\"" + benefitId + "\",\"chInfo\":\"" + CH + "\"}]");
                if (r != null && r.optBoolean("success", false)) {
                    Status.flagToday("youthExchange_" + benefitId);
                    Log.record(getName() + "🎁兑换成功[" + benefitId + "]");
                } else {
                    String msg = r == null ? "" : r.optString("resultMessage", "");
                    Log.record(getName() + "⚠️兑换失败[" + benefitId + "]" + msg);
                }
                TimeUtil.sleep(1500);
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    /** 记录抽奖奖池 */
    private void logLottery() {
        try {
            JSONObject jo = request(RPC_LOTTERY, "[{\"chInfo\":\"" + CH + "\"}]");
            if (jo == null) {
                return;
            }
            JSONArray list = jo.optJSONArray("prizeInfoList");
            if (list == null || list.length() == 0) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.length() && i < 6; i++) {
                JSONObject item = list.optJSONObject(i);
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(item.optString("title", ""));
            }
            Log.record(getName() + "🎰抽奖奖池：" + sb);
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    /** 发一次 RPC，成功返回整个响应 JSONObject，失败返回 null */
    private JSONObject request(String method, String data) {
        try {
            String resp = ApplicationHook.requestString(method, data, 1, -1);
            if (resp == null || resp.isEmpty()) {
                return null;
            }
            JSONObject jo = new JSONObject(resp);
            if (jo.optBoolean("success", false)
                    || "SUCCESS".equals(jo.optString("resultCode", ""))) {
                return jo;
            }
            return null;
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        return null;
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
}
