package com.surexu.sesame.model.task.handi;

import com.surexu.sesame.hook.MtopSender;
import com.surexu.sesame.hook.SesameDogAuth;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.StringUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 集汗滴(支付宝体育服务 alisports/handi 活动)的 MTOP 调用封装。
 *
 * <p>页面 spm {@code a21i0.handi2}，商品流 moduleId=177，tabId=503，奖励归因走阿里妈妈 CPC
 * （每条商品带 {@code reportExt.sig}，曝光上报 {@code goodsad.exposure} 时必须原样带回）。
 *
 * <p>全部接口都复用宿主(支付宝进程内)自己的 mtopsdk 实例，走 {@link MtopSender}，不需要 root/独立签名。
 */
public class HandiRpcCall {

    private static final String TAG = "HandiRpcCall";

    public static final String API_INDEX = "mtop.alisports.activity.handi.browsefindrewardindex";
    public static final String API_GET_ADS = "mtop.alisports.club.goodsad.getAds";
    public static final String API_EXPOSURE = "mtop.alisports.club.goodsad.exposure";
    public static final String API_REDPACKET = "mtop.alisports.club.redpacket.getByModuleId";
    public static final String API_CART = "mtop.alisports.club.savamoney.cart.index";
    /** 集汗滴任务中心(activity.taskcenter, 与 messi.taskcenter 不是一套) */
    public static final String API_TASK_MODULE = "mtop.alisports.activity.taskcenter.getTaskModuleInfo";
    public static final String API_TASK_PRE_DONE = "mtop.alisports.activity.taskcenter.preDone";
    /** 完成接口(真名是 done, postDone/finish/complete 都是请求API不存在) */
    public static final String API_TASK_DONE = "mtop.alisports.activity.taskcenter.done";
    /** 领奖接口(任务已完成待领取时才能成功, 否则 NO_GRANT_BENEFIT_TASK) */
    public static final String API_TASK_GRANT = "mtop.alisports.activity.taskcenter.grant";
    /** 汗滴换额度(空 body, 返回 {"transferBonus":N}, 1 汗滴 = 1 分现金) */
    public static final String API_TRANSFER_BONUS = "mtop.alisports.activity.handi.transferbonus";
    public static final String API_TASK_DETAIL = "mtop.alisports.activity.taskcenter.queryUserTaskDetail";
    /** 集汗滴活动 id(首页返回的 taskActivityId) */
    public static final int TASK_ACTIVITY_ID = 13309;
    public static final String DIS_POINT = "a21i0.22888684a21i0.headerActivity.miniapp_cpc_jihandi";
    public static final String VISIT_URL = "https://tyssr.m.taobao.com/app/alisports-fe/handi-ssr/newhome?x-ssr=true"
            + "&spm=" + DIS_POINT + "&chn=alipay.zfbsy.gg";

    /** 页面 spm 前缀（抓包实测） */
    public static final String SPM = "a21i0.handi2";
    public static final String SPM_ROOT = "a21i0.22888684a21i0";
    public static final String SPM_CHILD = "a21i0.22888684a21i0.headerActivity.miniapp_cpc_jihandi";
    public static final String CHN = "alipay.zfbsy.gg";
    public static final int MODULE_ID = 177;
    public static final String TAB_ID = "503";

    private HandiRpcCall() {
    }

    /** alisports 活动接口通用请求头(与芝麻狗一致: alty_ext + alty_sso_token)。 */
    private static Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("alty_ext", "version=2");
        String token = SesameDogAuth.peekToken();
        if (!StringUtil.isEmpty(token)) {
            h.put("alty_sso_token", token);
        }
        return h;
    }

    /** 首页数据：返回 totalAmount / limit / isShowRedPacketList 等。 */
    public static MtopSender.Result index() {
        JSONObject data = new JSONObject();
        try {
            JSONObject inner = new JSONObject();
            inner.put("chn", CHN);
            data.put("extraParam", inner.toString());
        } catch (Throwable ignore) {
        }
        return MtopSender.send(API_INDEX, "1.0", data.toString(), false, headers());
    }

    /** 商品流分页(moduleId=177, spm=a21i0.handi2)。 */
    public static MtopSender.Result getAds(int pageNo) {
        JSONObject data = new JSONObject();
        try {
            data.put("apiVersion", "1.0");
            data.put("deviceOs", "android");
            data.put("mergeGoodsItemType", "");
            data.put("moduleId", MODULE_ID);
            data.put("pageNo", pageNo);
            data.put("pageSize", 10);
            data.put("spm", SPM);
            data.put("tabId", TAB_ID);
        } catch (Throwable ignore) {
        }
        return MtopSender.send(API_GET_ADS, "1.0", data.toString(), false, headers());
    }

    /** 曝光上报(items 为 {@link #buildExposureItems} 产出的数组字符串)。 */
    public static MtopSender.Result exposure(String itemsJson) {
        JSONObject data = new JSONObject();
        try {
            data.put("items", itemsJson);
        } catch (Throwable ignore) {
        }
        return MtopSender.send(API_EXPOSURE, "1.0", data.toString(), false, headers());
    }

    /** 按模块领红包(moduleId=177)。 */
    public static MtopSender.Result redPacket() {
        JSONObject data = new JSONObject();
        try {
            JSONObject ext = new JSONObject();
            ext.put("spm", SPM);
            ext.put("version", "0.0.0");
            data.put("extendParam", ext.toString());
            data.put("moduleId", MODULE_ID);
        } catch (Throwable ignore) {
        }
        return MtopSender.send(API_REDPACKET, "1.0", data.toString(), false, headers());
    }

    /** 现金钱包(moduleId=76)。 */
    public static MtopSender.Result cart() {
        JSONObject data = new JSONObject();
        try {
            data.put("moduleId", 76);
            data.put("spm", SPM_ROOT);
        } catch (Throwable ignore) {
        }
        return MtopSender.send(API_CART, "1.0", data.toString(), false, headers());
    }

    /** 任务列表(taskActivityId=13309)。 */
    public static MtopSender.Result listTasks() {
        JSONObject data = new JSONObject();
        try {
            JSONObject ext = new JSONObject();
            ext.put("chn", CHN);
            ext.put("dis-point", DIS_POINT);
            ext.put("visitUrl", VISIT_URL);
            data.put("extendParams", ext.toString());
            data.put("taskActivityId", TASK_ACTIVITY_ID);
        } catch (Throwable ignore) {
        }
        return MtopSender.send(API_TASK_MODULE, "1.0", data.toString(), false, headers());
    }

    /** 点「去完成」时上报的任务开始(与宿主抓包一致)。 */
    public static MtopSender.Result preDone(String taskId) {
        JSONObject data = new JSONObject();
        try {
            JSONObject ext = new JSONObject();
            ext.put("chn", CHN);
            ext.put("backwardTaskId", taskId);
            ext.put("dis-point", DIS_POINT);
            data.put("extendParams", ext.toString());
            data.put("taskId", taskId);
        } catch (Throwable ignore) {
        }
        return MtopSender.send(API_TASK_PRE_DONE, "1.0", data.toString(), false, headers());
    }

    /** 任务完成上报(必须在 preDone 之后、且真实停留超过 browseTime 秒)。 */
    public static MtopSender.Result done(String taskId) {
        JSONObject data = new JSONObject();
        try {
            data.put("taskId", taskId);
            data.put("taskActivityId", TASK_ACTIVITY_ID);
        } catch (Throwable ignore) {
        }
        return MtopSender.send(API_TASK_DONE, "1.0", data.toString(), false, headers());
    }

    /** 领取奖励(任务已完成但未领取时调用)。 */
    public static MtopSender.Result grant(String taskId) {
        JSONObject data = new JSONObject();
        try {
            data.put("taskId", taskId);
            data.put("taskActivityId", TASK_ACTIVITY_ID);
        } catch (Throwable ignore) {
        }
        return MtopSender.send(API_TASK_GRANT, "1.0", data.toString(), false, headers());
    }

    /** 汗滴换额度: 把当前所有汗滴一次性换成现金额度(空 body)。 */
    public static MtopSender.Result transferBonus() {
        return MtopSender.send(API_TRANSFER_BONUS, "1.0", "{}", false, headers());
    }

    /**
     * 一键跑: 读任务列表 → 对可协议完成的类型逐条 preDone → 按 browseTime 等待 → done → grant,
     * 按 goalFinishCount 循环跑满; 最后把汗滴换成额度。
     *
     * <p>只碰 BROWSE_PAGE 与 LINK_CLICK; BROWSE_EXTERNAL_PAGE(36132/39319) 服务端直接
     * NOT_SUPPORT_CURRENT_REQUEST, GOODS_AD_ITEM_ORDER_TASK(39308/37391) 要真下单, 一律跳过并记录。
     */
    public static void runTasks(boolean transfer) {
        long t0 = System.currentTimeMillis();
        Set<String> dead = new HashSet<>();
        int okCount = 0;
        try {
            Log.other("集汗滴🤖 一键跑开始 " + MtopSender.stateText());
            for (int round = 1; round <= 3; round++) {
                JSONObject body = listTasksBody();
                if (body == null) {
                    Log.other("集汗滴🤖 第" + round + "轮 读任务列表失败, 结束");
                    break;
                }
                List<JSONObject> pending = pickRunnable(body, dead);
                if (pending.isEmpty()) {
                    Log.other("集汗滴🤖 第" + round + "轮 没有可跑的任务");
                    break;
                }
                Log.other("集汗滴🤖 第" + round + "轮 待跑 " + pending.size() + " 条");
                for (JSONObject task : pending) {
                    String taskId = task.optString("taskId");
                    String title = task.optString("taskMainHead", "");
                    int browseTime = 0;
                    JSONObject td = task.optJSONObject("taskData");
                    if (td != null) {
                        browseTime = td.optInt("browseTime", 0);
                    }
                    // 停留时长: browseTime 至少 5 秒, 再加 8 秒余量(服务端真的计时, 差 1 秒都回 BROWSE_TIME_NOT_ENOUGH)
                    int waitSec = Math.max(browseTime, 5) + 8;

                    MtopSender.Result pre = preDone(taskId);
                    if (pre == null || !pre.ok) {
                        Log.other("集汗滴🤖 [" + taskId + "] 开始失败: " + briefOf(pre));
                        dead.add(taskId);
                        continue;
                    }
                    Log.other("集汗滴🤖 [" + taskId + "] " + title + " 已开始, 等待 " + waitSec + " 秒");
                    try {
                        Thread.sleep(waitSec * 1000L);
                    } catch (InterruptedException ignore) {
                    }
                    MtopSender.Result dn = done(taskId);
                    if (dn == null || !dn.ok) {
                        String why = briefOf(dn);
                        Log.other("集汗滴🤖 [" + taskId + "] 完成失败: " + why);
                        // 类型不支持/时长不够之外的失败都不再重试, 免得反复打接口
                        dead.add(taskId);
                        continue;
                    }
                    okCount++;
                    Log.other("集汗滴🤖 [" + taskId + "] 已完成, 领奖中");
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignore) {
                    }
                    MtopSender.Result gr = grant(taskId);
                    Log.other("集汗滴🤖 [" + taskId + "] 领奖: " + (gr != null && gr.ok ? "OK" + valueOf(gr) : briefOf(gr)));
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignore) {
                    }
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignore) {
                }
            }
            Log.other("集汗滴🤖 任务完成 " + okCount + " 条, 耗时 " + ((System.currentTimeMillis() - t0) / 1000) + " 秒");
            if (transfer) {
                doTransfer();
            }
            MtopSender.Result fin = index();
            Log.other("集汗滴🤖 收尾首页: " + balanceText(fin));
        } catch (Throwable t) {
            Log.err(TAG, "runTasks err:", t);
        }
    }

    /** 汗滴换额度并回报结果。 */
    public static void doTransfer() {
        MtopSender.Result r = transferBonus();
        if (r != null && r.ok) {
            Log.other("集汗滴💰 换额度成功: " + valueOf(r));
        } else {
            Log.other("集汗滴💰 换额度: " + briefOf(r) + " (汗滴不足或今日已换)");
        }
    }

    /** 读任务列表并返回业务体(失败时重试一次, 首次常是 MTOPCONTEXT 抖动)。 */
    private static JSONObject listTasksBody() {
        MtopSender.Result r = listTasks();
        if (r == null || !r.ok || r.body() == null) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignore) {
            }
            r = listTasks();
        }
        if (r == null || !r.ok || r.body() == null) {
            return null;
        }
        return r.body();
    }

    /** 从列表里挑出还能做且允许纯协议完成的任务。 */
    private static List<JSONObject> pickRunnable(JSONObject body, Set<String> dead) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray groups = body.optJSONArray("groups");
        if (groups == null) {
            return out;
        }
        for (int g = 0; g < groups.length(); g++) {
            JSONObject group = groups.optJSONObject(g);
            JSONArray tasks = group == null ? null : group.optJSONArray("tasks");
            if (tasks == null) {
                continue;
            }
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String taskId = task.optString("taskId");
                if (StringUtil.isEmpty(taskId) || dead.contains(taskId)) {
                    continue;
                }
                String type = task.optString("taskType");
                if (!"BROWSE_PAGE".equals(type) && !"LINK_CLICK".equals(type)) {
                    continue;
                }
                int goal = Math.max(task.optInt("goalFinishCount", 1), 1);
                int finished = task.optInt("finishCount", 0);
                int received = task.optInt("receiveCount", 0);
                if (finished >= goal && received >= goal) {
                    continue;
                }
                out.add(task);
            }
        }
        return out;
    }

    private static String valueOf(MtopSender.Result r) {
        return r == null || r.data == null ? "{}" : truncate(String.valueOf(r.data), 200);
    }

    private static String balanceText(MtopSender.Result r) {
        if (r == null || !r.ok || r.body() == null) {
            return briefOf(r);
        }
        JSONObject b = r.body();
        return "汗滴=" + b.optInt("handiBalance", -1) + " 今日汗滴=" + b.optInt("todayHandiCount", -1)
                + " 现金分=" + b.optInt("bonusBalance", -1);
    }

    /** 任意接口直发(调试用): 只知道接口名和 body 时随手试, 免得为每个猜测重打包。 */    public static MtopSender.Result callApi(String api, String version, String dataText) {
        if (StringUtil.isEmpty(api)) {
            return null;
        }
        return MtopSender.send(api, StringUtil.isEmpty(version) ? "1.0" : version,
                StringUtil.isEmpty(dataText) ? "{}" : dataText, false, headers());
    }

    /**
     * 从 getAds 返回体构造曝光 items（字段与宿主抓包一一对应）。
     *
     * @param body       getAds 的业务体(含 goodsAdItems)
     * @param startIndex 本页第一条在 feed 里的序号(用于拼 spmId 的 goods-fall.N)
     */
    public static String buildExposureItems(JSONObject body, int startIndex) {
        JSONArray out = new JSONArray();
        JSONArray items = body == null ? null : body.optJSONArray("goodsAdItems");
        if (items == null) {
            return out.toString();
        }
        long sessionId = System.currentTimeMillis();
        for (int i = 0; i < items.length(); i++) {
            JSONObject src = items.optJSONObject(i);
            if (src == null) {
                continue;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("spmId", SPM + ".goods-fall." + (startIndex + i));
                String strItemId = src.optString("strItemId", "");
                if (StringUtil.isEmpty(strItemId)) {
                    strItemId = src.optString("itemId", "");
                }
                item.put("strItemId", strItemId);
                item.put("moduleId", MODULE_ID);
                item.put("tabId", TAB_ID);

                JSONObject ext = new JSONObject();
                JSONObject report = src.optJSONObject("reportExt");
                if (report != null) {
                    for (Iterator<String> it = report.keys(); it.hasNext(); ) {
                        String k = it.next();
                        Object v = report.opt(k);
                        if (v != null && !"null".equals(String.valueOf(v))) {
                            ext.put(k, v);
                        }
                    }
                }
                ext.put("hit_prequest", "1");
                ext.put("module_id", "M_" + MODULE_ID);
                ext.put("tab_id", "T_" + TAB_ID);
                ext.put("version", "0.1.23");
                ext.put("type", src.optString("type", "CPC"));
                ext.put("chn", CHN);
                ext.put("spm", SPM_CHILD);
                ext.put("req_session_id", sessionId);
                ext.put("tab_session_id", sessionId);
                ext.put("kw_input_type", "");
                ext.put("keyword", "");
                ext.put("auto_clk_hide", true);
                item.put("ext", ext);
                out.put(item);
            } catch (Throwable t) {
                Log.err(TAG, "buildExposureItem err:", t);
            }
        }
        return out.toString();
    }

    /**
     * 一次性验证探针：首页 → 拉第1页商品 → 曝光上报 → 再查首页。
     * 只用于确认"曝光能不能把奖励记进去"，不写任何余额。
     */
    public static void probe() {
        try {
            Log.other("集汗滴🧪[探针] 基座: " + MtopSender.stateText());
            MtopSender.Result before = index();
            Log.other("集汗滴🧪[探针] 首页: " + briefOf(before));            if (before.body() != null) {
                Log.other("集汗滴🧪[探针] totalAmount=" + before.body().optString("totalAmount")
                        + " limit=" + before.body().opt("limit")
                        + " noLimit=" + before.body().optString("noLimit")
                        + " showRp=" + before.body().optString("isShowRedPacketList"));
            }

            MtopSender.Result ads = getAds(1);
            Log.other("集汗滴🧪[探针] getAds: " + briefOf(ads));
            int count = 0;
            if (ads.body() != null && ads.body().optJSONArray("goodsAdItems") != null) {
                count = ads.body().optJSONArray("goodsAdItems").length();
            }
            Log.other("集汗滴🧪[探针] 商品数=" + count + " exposureFilter="
                    + (ads.body() == null ? "-" : String.valueOf(ads.body().optJSONObject("ext"))));

            if (count <= 0) {
                Log.other("集汗滴🧪[探针] 没拿到商品，停止");
                return;
            }
            String items = buildExposureItems(ads.body(), 1);
            Log.debug("[集汗滴] 曝光 items=" + items);
            MtopSender.Result expo = exposure(items);
            Log.other("集汗滴🧪[探针] exposure: " + briefOf(expo));

            MtopSender.Result after = index();
            Log.other("集汗滴🧪[探针] 复查首页: " + briefOf(after));
            if (after.body() != null) {
                Log.other("集汗滴🧪[探针] totalAmount=" + after.body().optString("totalAmount")
                        + " noLimit=" + after.body().optString("noLimit"));
            }
        } catch (Throwable t) {
            Log.err(TAG, "probe err:", t);
        }
    }

    private static String briefOf(MtopSender.Result r) {
        if (r == null) {
            return "null";
        }
        if (r.ok) {
            return "OK " + (r.data == null ? "{}" : truncate(r.data.toString(), 300));
        }
        return "FAIL " + r.brief();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
