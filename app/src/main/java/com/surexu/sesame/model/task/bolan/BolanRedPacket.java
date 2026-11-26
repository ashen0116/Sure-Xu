package com.surexu.sesame.model.task.bolan;

import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.modelFieldExt.BooleanModelField;
import com.surexu.sesame.data.modelFieldExt.IntegerModelField;
import com.surexu.sesame.data.task.ModelTask;
import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.model.base.TaskCommon;
import com.surexu.sesame.util.BolanToken;
import com.surexu.sesame.util.FeatureStats;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.PageOpenGate;
import com.surexu.sesame.util.TimeUtil;
import com.surexu.sesame.util.idMap.UserIdMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 玻弦红包（支付宝小程序「玻弦打卡 / 玻弦红包」，appId 2021005178678637）。
 *
 * <p>它是第三方小程序，接口全在 {@code https://tools.boxiantech.com}，不走 MTOP，
 * 用模块自带的 RPC 通道调不了，只能自己发 HTTPS。
 *
 * <p>实测链路（2026-11-25 全流程验证通过，余额 0.06 → 0.14）：
 * <ol>
 *   <li>{@code POST /bxtech/tool/encourage/task/list} 拉任务，body 传 {@code {}}；</li>
 *   <li>{@code POST /bxtech/tool/encourage/task/finish} body 只传 {@code {"taskId":"..."}}，
 *       成功回 {@code code=0, data.recordId=...}；重复调用回 {@code 99012 任务已完成}；</li>
 *   <li>{@code POST /bxtech/tool/encourage/task/prize/receive} body 传
 *       {@code {"recordId":"..."}}（<b>不是 taskId</b>）才真正到账，任务状态转 RECEIVED；</li>
 *   <li>{@code POST /bxtech/tool/encourage/user/info} 读余额。</li>
 * </ol>
 *
 * <p>鉴权：请求头 {@code token: <JWT>}（小写、POST，GET 会回 {@code -1 服务器繁忙}）。
 * token 靠小程序自己发 {@code /alipay/auth} 时由 {@link BolanToken#capture} 旁路读下来，
 * 所以首次要先打开一次玻弦红包页面（或关掉「禁止打开页面」让本功能自己开）。
 *
 * <p>能纯协议完成的类型（实测 code=0）：BROWSE / CPC_READING / MERCHANT_PLAN；
 * 做不了的（实测 任务未完成 / 愉悦赚广告id不存在）：SIMULATION_GUIDE、MEDIA_LINK_POSTBACK、
 * XIANYU_JUMP、MIDONG_TASK，都是要真实点击广告或按引导操作的任务，这里直接跳过并计数。
 */
public class BolanRedPacket extends ModelTask {

    private static final String NAME = "玻弦红包";
    private static final String HOST = "https://tools.boxiantech.com";
    private static final String PATH_INFO = "/bxtech/tool/encourage/user/info";
    private static final String PATH_LIST = "/bxtech/tool/encourage/task/list";
    private static final String PATH_FINISH = "/bxtech/tool/encourage/task/finish";
    private static final String PATH_RECEIVE = "/bxtech/tool/encourage/task/prize/receive";

    /** 小程序 appId：token payload 里的 appId，用于「无 token 时自动打开页面取授权」 */
    private static final String MINI_APP_ID = "2021005178678637";

    /** 可直接协议完成的类型 */
    private static final String[] PROTOCOL_TYPES = {"BROWSE", "CPC_READING", "MERCHANT_PLAN"};

    /** 同 URL 连续请求间隔（毫秒）：任务条数不多，1.2s 足够避开风控 */
    private static final long REQUEST_GAP_MS = 1200L;

    private BooleanModelField autoFinish;
    private BooleanModelField autoClaim;
    private BooleanModelField autoOpenPage;
    private IntegerModelField waitToken;
    private BooleanModelField showBalance;

    @Override
    public String getName() {
        return NAME;
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
        autoFinish = new BooleanModelField("autoFinish", "自动完成可协议任务", true);
        autoFinish.setDescription("浏览 / 阅读 / 商家计划类可直接协议完成；要真实点广告的任务做不了");
        modelFields.addField(autoFinish);
        autoClaim = new BooleanModelField("autoClaim", "自动领取奖励", true);
        autoClaim.setDescription("按 recordId 调 prize/receive，0.01 元/条直接到账");
        modelFields.addField(autoClaim);
        autoOpenPage = new BooleanModelField("autoOpenPage", "无token时自动打开页面", false);
        autoOpenPage.setDescription("首次使用需要先打开一次玻弦红包页面拿授权；静默模式下此开关无效");
        modelFields.addField(autoOpenPage);
        waitToken = new IntegerModelField("waitToken", "等token时长(秒)", 15, 5, 60);
        waitToken.setDescription("自动打开页面后等多久去读 token");
        modelFields.addField(waitToken);
        showBalance = new BooleanModelField("showBalance", "记录余额", true);
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
            String token = BolanToken.read();
            JSONObject list = token.isEmpty() ? null : post(PATH_LIST, "{}", token);
            if (!isOk(list)) {
                if (!token.isEmpty()) {
                    // token 不再可用：清掉，重新走「打开页面拿授权」这条唯一可行的路
                    Log.record(NAME + "⚠️token 已失效（" + msgOf(list) + "），清除后重取");
                    BolanToken.clear();
                }
                token = acquireToken();
                list = token.isEmpty() ? null : post(PATH_LIST, "{}", token);
            }
            if (!isOk(list)) {
                Log.record(NAME + "⏭️没有可用 token，本轮跳过。打开一次「玻弦红包」小程序，"
                        + "模块会自动把 token 存下来，之后每天就不需要再打开了");
                Log.markUnresolved();
                return;
            }
            if (showBalance == null || Boolean.TRUE.equals(showBalance.getValue())) {
                logBalance(token);
            }
            int finished = 0;
            int claimed = 0;
            int skipped = 0;
            int failed = 0;
            JSONArray tasks = list.optJSONArray("data");
            if (tasks == null) {
                JSONObject data = list.optJSONObject("data");
                tasks = data == null ? null : data.optJSONArray("taskList");
            }
            if (tasks == null || tasks.length() == 0) {
                Log.record(NAME + "⏭️任务列表为空");
                return;
            }
            boolean claim = autoClaim == null || Boolean.TRUE.equals(autoClaim.getValue());
            boolean finish = autoFinish == null || Boolean.TRUE.equals(autoFinish.getValue());
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String taskId = task.optString("taskId", "");
                String taskName = task.optString("taskName", taskId);
                String type = task.optString("taskType", "");
                String status = task.optString("taskStatus", "");
                if ("RECEIVED".equals(status)) {
                    continue;
                }
                // 已完成但没领奖的（页面里显示「领取」那种），这里补领
                if ("FINISH".equals(status)) {
                    String recordId = task.optString("recordId", "");
                    if (claim && !recordId.isEmpty() && receive(recordId, token)) {
                        claimed++;
                        Log.record(NAME + "✅补领奖励[" + taskName + "]");
                        TimeUtil.sleep(REQUEST_GAP_MS);
                    }
                    continue;
                }
                if (!"INIT".equals(status) || taskId.isEmpty()) {
                    skipped++;
                    continue;
                }
                if (!isProtocolType(type)) {
                    skipped++;
                    continue;
                }
                if (!finish) {
                    continue;
                }
                JSONObject fr = post(PATH_FINISH, "{\"taskId\":\"" + taskId + "\"}", token);
                if (!isOk(fr)) {
                    failed++;
                    Log.record(NAME + "⚠️任务未完成[" + taskName + "/" + type + "]：" + msgOf(fr));
                    TimeUtil.sleep(REQUEST_GAP_MS);
                    continue;
                }
                finished++;
                String recordId = "";
                JSONObject fdata = fr.optJSONObject("data");
                if (fdata != null) {
                    recordId = fdata.optString("recordId", "");
                }
                boolean got = claim && !recordId.isEmpty() && receive(recordId, token);
                if (got) {
                    claimed++;
                    FeatureStats.add(NAME, "point", 1);
                    Log.record(NAME + "✅任务完成并到账[" + taskName + "]");
                } else {
                    Log.record(NAME + "✅任务完成[" + taskName + "]（奖励待领取）");
                }
                TimeUtil.sleep(REQUEST_GAP_MS);
            }
            StringBuilder sb = new StringBuilder(NAME).append("✅本轮结束：完成 ")
                    .append(finished).append(" 条，到账 ").append(claimed).append(" 条");
            if (skipped > 0) {
                sb.append("，跳过 ").append(skipped).append(" 条（需真实点击广告/非协议类型）");
            }
            if (failed > 0) {
                sb.append("，失败 ").append(failed).append(" 条");
                Log.markUnresolved();
            }
            Log.record(sb.toString());
        } catch (Exception e) {
            Log.record(NAME + "异常：" + e.getClass().getSimpleName());
            Log.printStackTrace(e);
        }
    }

    /** 读余额（累计活动奖励 / 待提现） */
    private void logBalance(String token) {
        JSONObject info = post(PATH_INFO, "{}", token);
        if (!isOk(info)) {
            return;
        }
        JSONObject data = info.optJSONObject("data");
        if (data == null) {
            return;
        }
        String amount = data.optString("amount", "");
        String total = data.optString("totalAmount", "");
        if (!amount.isEmpty()) {
            Log.record(NAME + "💰余额 " + amount + " 元"
                    + (total.isEmpty() || total.equals(amount) ? "" : "（累计 " + total + " 元）"));
        }
    }

    /** 取 token：没有就按配置尝试打开小程序页面，等 Hook 把 token 存下来 */
    private String acquireToken() {
        if (autoOpenPage == null || !Boolean.TRUE.equals(autoOpenPage.getValue())) {
            return "";
        }
        if (PageOpenGate.isBlocked()) {
            Log.record(NAME + "⏸️静默模式已拦截页面打开，无法自动取 token，请手动打开一次玻弦红包");
            return "";
        }
        String url = "alipays://platformapi/startapp?appId=" + MINI_APP_ID;
        if (!PageOpenGate.open(ApplicationHook.getContext(), url, NAME)) {
            return "";
        }
        Log.record(NAME + "🔓已打开玻弦红包页面，等待 token…");
        int wait = waitToken == null || waitToken.getValue() == null ? 15 : waitToken.getValue();
        for (int i = 0; i < wait; i++) {
            TimeUtil.sleep(1000);
            String token = BolanToken.read();
            if (!token.isEmpty()) {
                return token;
            }
        }
        Log.record(NAME + "⌛等待 token 超时（" + wait + "s）");
        return "";
    }

    /** 按 recordId 领奖；成功即任务转 RECEIVED */
    private boolean receive(String recordId, String token) {
        JSONObject r = post(PATH_RECEIVE, "{\"recordId\":\"" + recordId + "\"}", token);
        return isOk(r);
    }

    private boolean isProtocolType(String type) {
        if (type == null) {
            return false;
        }
        for (String t : PROTOCOL_TYPES) {
            if (t.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** 成功判定：业务码 code == 0（响应是 {"code":0,"msg":"请求成功","success":true}） */
    private boolean isOk(JSONObject resp) {
        return resp != null && resp.optInt("code", -1) == 0;
    }

    private String msgOf(JSONObject resp) {
        if (resp == null) {
            return "无响应";
        }
        return resp.optString("msg", resp.optString("errorMsg", "code=" + resp.optInt("code", -1)));
    }

    /**
     * 发一次 POST。鉴权头必须是<b>小写 {@code token}</b>；GET 会被服务端当成异常请求
     * （回 {@code -1 服务器繁忙}），所以这里固定 POST + JSON body。
     */
    private JSONObject post(String path, String body, String token) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(HOST + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("accept", "application/json");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("token", token);
            }
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write((body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 200 && code <= 299 ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            String text = sb.toString();
            if (text.isEmpty()) {
                return null;
            }
            return new JSONObject(text);
        } catch (Exception e) {
            Log.record(NAME + "⚠️接口请求异常(" + path + ")：" + e.getClass().getSimpleName());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
