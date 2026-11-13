package com.surexu.sesame.model.common.gamePlay;

import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.Status;
import com.surexu.sesame.util.TimeUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用「假玩游戏」：不真启动游戏，只把服务端要求的游玩行为按协议报上去，让游戏类任务能自动完成。
 *
 * <p>为什么要通用：原来农场悬浮球、抽抽乐各写了一套，参数写死、时间字段互相矛盾
 * （上报 30~60s、事件里 3~4s、实际只等 3~4s），很容易被判风险操作。
 * 这里统一成「一个 durationSec 同时驱动 等待 / playTime / GAME_ELAPASED_TIME」，三处永远对得上。
 *
 * <p>以后新加功能要支持游戏任务，只要在任务循环里写这一行
 * （组件内部已经处理「当天只玩一次 / 一轮最多 3 个 / 时间字段自洽 / 风控去重」）：
 * <pre>{@code
 * if (GamePlaySimulator.playForTask("功能名", task, Scene.GENERIC, playedThisRound)) {
 *     playedThisRound++;
 *     // 再走本模块自己的完成 / 领奖接口，最后重新查任务列表复核状态
 * }
 * }</pre>
 * <p>找不到游戏 id 时，组件会把任务字段打到日志，照着日志补解析路径即可，不要凭空猜接口。
 *
 * <p>注意：组件只负责「把行为报上去」，返回 true 只表示行为已上报，不代表任务已完成。
 * 服务端是否放行一律以任务列表状态复核为准；游戏服真实计数 / 真实交易类的任务不做伪造。
 */
public final class GamePlaySimulator {

    private static final String TAG = "假玩游戏🎮";
    private static final Random RANDOM = new Random();

    /** 默认游玩时长（秒）：服务端普遍要求不低于 30s */
    public static final int DEFAULT_DURATION_SEC = 32;
    /** 单轮最多伪造几个游戏任务，避免一轮把扫到的全做完 */
    public static final int MAX_PLAY_PER_ROUND = 3;

    /** 场景：不同活动的 chInfo / sceneId / 预热接口不一样 */
    public enum Scene {
        /** 农场乐园悬浮球 */
        ORCHARD_FLOAT_BALL("lianyun_nc_sydb", "nongchangleyuan", "ORCHARD", "ch_appcenter__chsub_9patch"),
        /** 农场抽抽乐 */
        ORCHARD_DRAW("bbnc_mc_xemkib05", "nongchangleyuan", "AntOrchard", "bbnc_mc_xemkib05"),
        /** 其它活动通用：只发游戏中心 / 游戏事件那几条，不发农场专用预热 */
        GENERIC("ch_appcenter__chsub_9patch", "nongchangleyuan", "", "ch_appcenter__chsub_9patch");

        final String chInfo;
        final String sceneId;
        final String noticeScene;
        final String source;

        Scene(String chInfo, String sceneId, String noticeScene, String source) {
            this.chInfo = chInfo;
            this.sceneId = sceneId;
            this.noticeScene = noticeScene;
            this.source = source;
        }
    }

    /** 一次伪造游玩的参数；用 builder 方便新模块按需补充 */
    public static final class Request {
        String appId;
        String taskId = "";
        String sceneCode = "";
        int durationSec = DEFAULT_DURATION_SEC;
        Scene scene = Scene.GENERIC;
        boolean notifyGame = false;
        boolean preload = false;
        /** 真等满时长最安全；false 时只等 3~4s，且上报同样的 3~4s（不再谎报 30s） */
        boolean realWait = true;
        String logName = "";

        public Request appId(String v) { this.appId = v; return this; }
        public Request taskId(String v) { this.taskId = v; return this; }
        public Request sceneCode(String v) { this.sceneCode = v; return this; }
        public Request durationSec(int v) { this.durationSec = v; return this; }
        public Request scene(Scene v) { this.scene = v; return this; }
        public Request notifyGame(boolean v) { this.notifyGame = v; return this; }
        public Request preload(boolean v) { this.preload = v; return this; }
        public Request realWait(boolean v) { this.realWait = v; return this; }
        public Request logName(String v) { this.logName = v; return this; }
    }

    private GamePlaySimulator() {
    }

    /** 最简一行调用：默认场景、默认时长 */
    public static boolean play(String appId) {
        return play(new Request().appId(appId));
    }

    public static boolean play(String appId, int durationSec, Scene scene) {
        return play(new Request().appId(appId).durationSec(durationSec).scene(scene));
    }

    /**
     * 从任务 JSON 里自动找游戏 id 和时长并伪造游玩；找不到游戏 id 返回 false，调用方照旧跳过。
     * 支持的 id 路径：appId / gameId / game_id / gameItemId / contentId /
     * prodPlayParam.taskCategorization.categorizationParamModel.game_id
     * 支持的时长路径：duration / timeCount / timeSeconds / floatBallDuration / browseTime / playTime
     */
    public static boolean tryPlay(JSONObject task) {
        return tryPlay(task, Scene.GENERIC);
    }

    public static boolean tryPlay(JSONObject task, Scene scene) {
        if (task == null) {
            return false;
        }
        String appId = findGameId(task);
        if (appId.isEmpty()) {
            return false;
        }
        Request req = new Request()
                .appId(appId)
                .scene(scene)
                .durationSec(findDuration(task))
                .taskId(firstNonEmpty(task, "taskId", "iepTaskId", "taskType", "actionBizId"))
                .sceneCode(firstNonEmpty(task, "sceneCode", "iepSceneCode", "taskSceneCode"))
                .logName(firstNonEmpty(task, "title", "taskName", "name"));
        return play(req);
    }

    /** 明显不是「玩游戏」的任务：加桌面 / 订阅 / 签到 / 游戏中心入口这些 */
    private static final String[] NOT_GAME_HINTS = {
            "添加到桌面", "加桌面", "添加桌面", "桌面快捷", "订阅", "收藏", "关注",
            "签到", "开通", "绑定", "逛一逛游戏中心", "游戏中心添加到",
            // 纯浏览/看视频类任务：跳转链接里也带 appId，但人家要的是「报名+停留」，
            // 不是真玩游戏，别再花 32 秒去报一次游玩行为
            "逛一逛", "浏览15秒", "15s逛", "看漫剧", "搜一搜", "看视频"
    };

    /** 标题带「游戏」两字不代表要玩游戏：「把游戏中心加到桌面」就只是个加桌面任务 */
    public static boolean isObviouslyNotGame(String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        for (String h : NOT_GAME_HINTS) {
            if (title.contains(h)) {
                return true;
            }
        }
        return "游戏中心".equals(title.trim());
    }

    /** 任务是不是「要玩游戏」类型；新模块用它决定要不要放行而不是拉黑 */
    public static boolean isGameTask(JSONObject task) {
        if (task == null) {
            return false;
        }
        String type = firstNonEmpty(task, "taskType", "TASK_TYPE", "taskProdPlayType",
                "taskTriggerType", "taskSubType", "bizType");
        String id = firstNonEmpty(task, "taskId", "iepTaskId");
        String title = firstNonEmpty(task, "title", "taskName", "name", "taskTitle", "subTitle");
        for (String key : CONTAINER_KEYS) {
            JSONObject o = task.optJSONObject(key);
            if (o == null) {
                continue;
            }
            type += " " + firstNonEmpty(o, "TASK_TYPE", "taskType", "taskTriggerType", "bizType");
            title += " " + firstNonEmpty(o, "taskTitle", "title", "taskName", "subTitle");
        }
        // 先排掉明确不是游戏的任务，否则「游戏中心」这种入口名会被误判成要玩的游戏
        if (isObviouslyNotGame(title)) {
            return false;
        }
        if (!findGameId(task).isEmpty()) {
            return true;
        }
        // 有些游戏任务标题就一个「玩」字打头（例如「玩打个螺丝」），压根没有「游戏」两字，
        // 不能因为字面不含关键词就当普通任务跳过
        return containsGame(type) || containsGame(id) || containsGame(title)
                || title.trim().startsWith("玩");
    }

    /** 给统一路由用：从任务下发字段里动态取游戏 id，不写死某天抓到的值。 */
    public static String gameIdOf(JSONObject task) {
        return findGameId(task);
    }

    /** 不用 java.util.function，避免低版本 Android 未开 desugaring 时编译不过 */
    public interface FinishAction {
        boolean run();
    }

    /** 伪造完成后接着执行本模块自己的完成 / 领奖动作 */
    public static boolean playAndThen(Request req, FinishAction finishAction) {
        if (!play(req) && !hasPlayedToday(req)) {
            return false;
        }
        try {
            return finishAction != null && finishAction.run();
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return false;
        }
    }

    /**
     * 新功能接入只要这一行：识别游戏任务 → 找游戏 id → 上报一次游玩行为。
     * <p>返回 true 表示「今天这个任务的游玩行为已经报过了」，调用方可以继续走自己的完成 / 领奖接口；
     * 返回 false 表示不是游戏任务、缺游戏 id 或本轮已达上限，调用方照旧跳过。
     *
     * @param module          功能名，只用于日志，例如「游戏中心」
     * @param playedThisRound 本轮已经假玩过几个，配合 {@link #MAX_PLAY_PER_ROUND} 限速
     */
    public static boolean playForTask(String module, JSONObject task, Scene scene, int playedThisRound) {
        if (!isGameTask(task) || playedThisRound >= MAX_PLAY_PER_ROUND) {
            return false;
        }
        String appId = findGameId(task);
        if (appId.isEmpty()) {
            logMissingGameIdOnce(module, task);
            return false;
        }
        Request req = new Request()
                .appId(appId)
                .scene(scene == null ? Scene.GENERIC : scene)
                .durationSec(findDuration(task))
                .taskId(firstNonEmpty(task, "taskId", "iepTaskId", "taskType", "actionBizId"))
                .sceneCode(firstNonEmpty(task, "sceneCode", "iepSceneCode", "taskSceneCode"))
                .logName(module + "·" + firstNonEmpty(task, "title", "taskTitle", "taskName", "name"));
        if (hasPlayedToday(req)) {
            return true;
        }
        // 把当天取到的 id 打到日志里：以后换游戏了，看这行就知道是跟着任务动态变的
        Log.record(TAG + "🎯[" + module + "]从任务下发字段动态取到游戏id=" + appId);
        return play(req);
    }

    /** 今天这个游戏任务的游玩行为是否已经上报过 */
    public static boolean hasPlayedToday(JSONObject task) {
        if (task == null) {
            return false;
        }
        String appId = findGameId(task);
        if (appId.isEmpty()) {
            return false;
        }
        return Status.hasFlagToday(flagOf(new Request().appId(appId)
                .taskId(firstNonEmpty(task, "taskId", "iepTaskId", "taskType", "actionBizId"))));
    }

    private static boolean hasPlayedToday(Request req) {
        return Status.hasFlagToday(flagOf(req));
    }

    private static String flagOf(Request req) {
        return "gamePlay::" + req.appId + "::" + (req.taskId == null ? "" : req.taskId);
    }

    /** 缺游戏 id 时把任务字段打到日志里，方便真机抓包后补解析路径；同一个任务当天只记一次 */
    private static void logMissingGameIdOnce(String module, JSONObject task) {
        String key = firstNonEmpty(task, "taskId", "iepTaskId", "taskType", "actionBizId");
        String flag = "gamePlay::missing::" + module + "::" + (key.isEmpty() ? "unknown" : key);
        if (Status.hasFlagToday(flag)) {
            return;
        }
        Status.flagToday(flag);
        StringBuilder sb = new StringBuilder();
        try {
            Iterator<String> it = task.keys();
            int n = 0;
            while (it.hasNext() && n < 24) {
                String k = it.next();
                String v = String.valueOf(task.opt(k));
                if (v.length() > 80) {
                    v = v.substring(0, 80) + "…";
                }
                sb.append(k).append('=').append(v).append("; ");
                n++;
            }
        } catch (Throwable ignored) {
        }
        // 摘要里长字段被截断了，完整任务 JSON 单独写进调试日志，抓包时照着这个补 appId 解析路径
        try {
            Log.debug(TAG + "⚠️[" + module + "]缺游戏id完整任务JSON：" + task);
        } catch (Throwable ignored) {
        }
        Log.record(TAG + "⚠️[" + module + "]游戏任务没找到游戏id，需抓包补字段：" + sb);
    }

    /**
     * 完整链路：预热 → 进游戏 → 首帧 → 加载完成 → 等待 → 上报时长 → 游玩事件。
     * 所有上报共用同一个 playSeconds，避免三处时间对不上被判异常。
     */
    public static boolean play(Request req) {
        if (req == null || req.appId == null || req.appId.isEmpty()) {
            return false;
        }
        Scene scene = req.scene == null ? Scene.GENERIC : req.scene;
        String flag = flagOf(req);
        if (Status.hasFlagToday(flag)) {
            return false;
        }
        // 实际等待秒数：真等满用服务端要求时长；极速模式只等 3~4s，上报也用同一个值
        int playSeconds = req.realWait
                ? Math.max(3, Math.min(req.durationSec <= 0 ? DEFAULT_DURATION_SEC : req.durationSec, 120))
                : 3 + RANDOM.nextInt(2);
        try {
            String name = req.logName == null || req.logName.isEmpty() ? req.appId : req.logName;
            Log.record(TAG + "开始[" + name + "]#时长" + playSeconds + "s");

            if (req.preload) {
                gamepreloadConsult(req.appId, scene);
            }
            if (req.notifyGame && !scene.noticeScene.isEmpty()) {
                noticeGame(req.appId, scene);
            }
            submitUserAction(req.appId, "enterGame");
            TimeUtil.sleep(500);
            submitEvent(req.appId, "GAME_FIRST_FRAME", 0L, scene);
            TimeUtil.sleep(500);
            submitEvent(req.appId, "loading_completed", 0L, scene);

            TimeUtil.sleep(playSeconds * 1000L);
            // playTime 单位秒、GAME_ELAPASED_TIME 单位毫秒，同源换算
            submitDuration(req.appId, playSeconds, scene);
            submitEvent(req.appId, "game_play", playSeconds * 1000L, scene);

            Status.flagToday(flag);
            Log.record(TAG + "完成[" + name + "]#行为已上报，等待任务列表复核");
            return true;
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return false;
        }
    }

    /* ---------------- RPC ---------------- */

    private static void request(String method, String data) {
        try {
            ApplicationHook.requestString(method, data, 1, -1);
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    private static void noticeGame(String appId, Scene scene) {
        request("com.alipay.antorchard.noticeGame",
                "[{\"appId\":\"" + appId + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\""
                        + scene.noticeScene + "\",\"source\":\"" + scene.source
                        + "\",\"version\":\"20260721.01\"}]");
    }

    private static void gamepreloadConsult(String appId, Scene scene) {
        request("com.alipay.gameflash.biz.rpc.gamepreload.consult",
                "[{\"appId\":\"" + appId + "\",\"deviceLevel\":\"high\",\"networkType\":\"WWAN\","
                        + "\"sceneContext\":{\"subScene\":\"orchard_lottery\"},\"sceneId\":\""
                        + scene.noticeScene + "\",\"source\":\"" + scene.source
                        + "\",\"unityDeviceLevel\":\"high\"}]");
    }

    private static void submitUserAction(String appId, String actionCode) {
        request("com.alipay.gamecenteruprod.biz.rpc.v3.submitUserAction",
                "[{\"actionCode\":\"" + actionCode + "\",\"gameId\":\"" + appId
                        + "\",\"paladinxVersion\":\"2.1.95\",\"source\":\"gameFramework\"}]");
    }

    private static void submitDuration(String appId, int playSeconds, Scene scene) {
        request("com.alipay.gamecenteruprod.biz.rpc.v3.submitUserPlayDurationAction",
                "[{\"gameAppId\":\"" + appId + "\",\"playTime\":" + playSeconds
                        + ",\"source\":\"" + scene.chInfo + "\",\"statisticTag\":\"\"}]");
    }

    private static void submitEvent(String appId, String eventId, long elapsedMs, Scene scene) {
        try {
            JSONObject attr = new JSONObject();
            attr.put("CH_INFO", scene.chInfo);
            attr.put("CPS_ID", "unknown");
            attr.put("GAME_VERSION", "6.5.72");
            attr.put("PALADINX_VERSION", "2.1.95");
            attr.put("PLAY_SCENE", "NORMAL");
            attr.put("SCENE_ID", scene.sceneId);
            if ("GAME_FIRST_FRAME".equals(eventId)) {
                attr.put("ALIVE_ENTER", "0");
            }
            if (elapsedMs > 0) {
                attr.put("GAME_ELAPASED_TIME", elapsedMs);
            }
            JSONObject body = new JSONObject();
            body.put("appId", appId);
            body.put("eventAttrMap", attr);
            body.put("eventId", eventId);
            body.put("idempotentNo", "platform_" + System.currentTimeMillis() + "_" + RANDOM.nextInt(100000));
            body.put("source", "loading_completed".equals(eventId)
                    ? "my.reportLoadingCompleted" : "my.reportGamePlay");
            request("com.alipay.gameevent.biz.rpc.submitEvent", "[" + body + "]");
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    /* ---------------- 任务 JSON 解析 ---------------- */

    private static final String[] GAME_ID_KEYS = {
            "appId", "gameId", "game_id", "gameItemId", "contentId", "gameAppId", "appletId",
            "miniAppId", "appid", "gameCode", "targetAppId"
    };
    private static final String[] DURATION_KEYS = {
            "duration", "timeCount", "timeSeconds", "floatBallDuration", "browseTime",
            "playTime", "playSeconds", "needPlayTime", "gamePlayTime"
    };
    /** 游戏 id 常藏在跳转链接里，如 alipays://platformapi/startapp?appId=202100... */
    private static final String[] LINK_KEYS = {
            "jumpLink", "btnLinkUrl", "linkUrl", "taskUrl", "jumpUrl", "actionUrl", "taskLink", "url",
            "targetUrl", "btnUrl", "h5Url", "pageUrl", "taskJumpUrl", "schemeUrl", "miniAppUrl"
    };
    private static final Pattern ID_IN_LINK = Pattern.compile(
            "(?:appId|gameId|game_id|miniAppId|appid)[\"'=:\\s]+([0-9A-Za-z_-]{6,})");
    /** 各活动把游戏信息塞进不同子对象，统一扫一遍 */
    private static final String[] CONTAINER_KEYS = {
            "taskExtProps", "extInfo", "floatingBall", "taskShowInfo", "bizInfo", "extProps",
            "taskDisplayConfig", "taskBaseInfo", "taskConfig", "bizParam"
    };

    private static String findGameId(JSONObject task) {
        String id = firstNonEmpty(task, GAME_ID_KEYS);
        if (!id.isEmpty()) {
            return id;
        }
        for (String key : CONTAINER_KEYS) {
            JSONObject o = task.optJSONObject(key);
            if (o != null) {
                String v = firstNonEmpty(o, GAME_ID_KEYS);
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        JSONObject pp = playParam(task);
        if (pp != null) {
            JSONObject cat = pp.optJSONObject("taskCategorization");
            JSONObject pm = cat == null ? null : cat.optJSONObject("categorizationParamModel");
            if (pm != null) {
                String v = firstNonEmpty(pm, GAME_ID_KEYS);
                if (!v.isEmpty()) {
                    return v;
                }
            }
            String v = firstNonEmpty(pp, GAME_ID_KEYS);
            if (!v.isEmpty()) {
                return v;
            }
        }
        for (String key : LINK_KEYS) {
            String v = idFromLink(task.optString(key, ""));
            if (!v.isEmpty()) {
                return v;
            }
            for (String c : CONTAINER_KEYS) {
                JSONObject o = task.optJSONObject(c);
                if (o != null) {
                    v = idFromLink(o.optString(key, ""));
                    if (!v.isEmpty()) {
                        return v;
                    }
                }
            }
        }
        // 最后兜底：游戏 id 常藏在 taskDisplayConfig.targetUrl 这种一层套一层的下发字段里。
        // 任务 JSON 是服务端每天现给的，逐层扫一遍，明天换成别的小游戏照样能取到，
        // 不需要、也绝不能把今天抓到的 appId 写死在代码里。
        return scanDeep(task, 0);
    }

    /** 递归扫任意层级的对象 / 数组 / 字符串，找游戏 id 或带 appId= 的跳转地址 */
    private static String scanDeep(JSONObject o, int depth) {
        if (o == null || depth > 6) {
            return "";
        }
        String direct = firstNonEmpty(o, GAME_ID_KEYS);
        if (!direct.isEmpty()) {
            return direct;
        }
        for (String key : LINK_KEYS) {
            String fromLink = idFromLink(o.optString(key, ""));
            if (!fromLink.isEmpty()) {
                return fromLink;
            }
        }
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            Object v = o.opt(it.next());
            if (v instanceof JSONObject) {
                String found = scanDeep((JSONObject) v, depth + 1);
                if (!found.isEmpty()) {
                    return found;
                }
            } else if (v instanceof JSONArray) {
                JSONArray arr = (JSONArray) v;
                for (int i = 0; i < arr.length() && i < 20; i++) {
                    Object item = arr.opt(i);
                    if (item instanceof JSONObject) {
                        String found = scanDeep((JSONObject) item, depth + 1);
                        if (!found.isEmpty()) {
                            return found;
                        }
                    }
                }
            } else if (v instanceof String) {
                // 只认真正带 appId/gameId 的地址串，普通文案里的数字不会被误当成游戏 id
                String s = (String) v;
                if (s.contains("appId=") || s.contains("gameId=") || s.contains("appid=")) {
                    String fromLink = idFromLink(s);
                    if (!fromLink.isEmpty()) {
                        return fromLink;
                    }
                }
            }
        }
        return "";
    }

    private static String idFromLink(String link) {
        if (link == null || link.isEmpty()) {
            return "";
        }
        Matcher m = ID_IN_LINK.matcher(link);
        return m.find() ? m.group(1) : "";
    }

    /** prodPlayParam 既可能是对象，也可能是被塞成字符串的 JSON */
    private static JSONObject playParam(JSONObject task) {
        try {
            Object raw = task.opt("prodPlayParam");
            if (raw instanceof JSONObject) {
                return (JSONObject) raw;
            }
            return new JSONObject(task.optString("prodPlayParam", "{}"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static int findDuration(JSONObject task) {
        int v = durationFrom(task);
        if (v > 0) {
            return v;
        }
        for (String key : CONTAINER_KEYS) {
            JSONObject o = task.optJSONObject(key);
            if (o != null) {
                v = durationFrom(o);
                if (v > 0) {
                    return v;
                }
            }
        }
        JSONObject pp = playParam(task);
        if (pp != null) {
            v = durationFrom(pp);
            if (v > 0) {
                return v;
            }
        }
        return DEFAULT_DURATION_SEC;
    }

    private static int durationFrom(JSONObject o) {
        for (String key : DURATION_KEYS) {
            int v = o.optInt(key, 0);
            if (v <= 0) {
                v = parseInt(o.optString(key, ""));
            }
            if (v > 0) {
                return v;
            }
        }
        return 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String firstNonEmpty(JSONObject task, String... keys) {
        for (String key : keys) {
            String v = task.optString(key, "");
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static boolean containsGame(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("game") || lower.contains("play") || lower.contains("游玩")
                || s.contains("玩游戏") || s.contains("小游戏") || s.contains("游戏")
                || s.contains("试玩");
    }
}
