package com.surexu.sesame.model.extensions;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Objects;

import com.surexu.sesame.data.TokenConfig;
import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.hook.Toast;
import com.surexu.sesame.model.task.antSports.AntSportsRpcCall;
import com.surexu.sesame.model.task.protectEcology.ProtectTreeRpcCall;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.MessageUtil;
import com.surexu.sesame.util.Status;
import com.surexu.sesame.util.StringUtil;
import com.surexu.sesame.util.TimeUtil;
import com.surexu.sesame.util.idMap.PathThemeMapListMap;

public class ExtensionsHandle {
    private static final String TAG = ExtensionsHandle.class.getSimpleName();

    public static void handleRequest(String type, String fun, Object data) {
        if (handleAlphaRequest(type, fun, data) != null) {
            return;
        }
        switch (type) {
            case "antForest":
                if (Objects.equals("getWateredItems", fun)) {
                    getWateredItems();
                }else if (Objects.equals("getWateringItems", fun)) {
                    getWateringItems();
                }else if (Objects.equals("getTreeItems", fun)) {
                    getTreeItems();
                } else if (Objects.equals("getNewTreeItems", fun)) {
                    getNewTreeItems();
                } else if (Objects.equals("queryAreaTrees", fun)) {
                    queryAreaTrees();
                } else if (Objects.equals("getUnlockTreeItems", fun)) {
                    getUnlockTreeItems();
                } else if (Objects.equals("fillWateredFriendList", fun)) {
                    fillWateredFriendList();
                }
                break;
            case "setCustomWalkPathIdList":
                addCustomWalkPathIdList((String) data);
                break;
            case "setCustomWalkPathIdQueue":
                if (Objects.equals("addCustomWalkPathIdQueue", fun)) {
                    addCustomWalkPathIdQueue((String) data);
                } else if (Objects.equals("clearCustomWalkPathIdQueue", fun)) {
                    clearCustomWalkPathIdQueue();
                }
                break;
            case "silentSwitch":
                if (Objects.equals("rotateAll", fun)) {
                    SilentSwitchRotateAll.start();
                }
                break;
            case "mockRequest":
                mockRequest(fun, (String) data);
                break;
            case "openUrl":
                // UI 侧「打开视频红包页」用：视频页没有公开入口，只能靠任务里的 btnLinkUrl 直达，
                // 由支付宝进程自己 startActivity 才不会被跨包/包可见性拦住
                openUrl(fun);
                break;
        }
    }

    /** 在支付宝进程内按 alipays:// 链接打开页面（阻塞式记录结果） */
    private static void openUrl(String url) {
        try {
            if (StringUtil.isEmpty(url)) {
                Log.record("打开页面失败：链接为空");
                return;
            }
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Context ctx = ApplicationHook.getContext();
            if (ctx == null) {
                Log.record("打开页面失败：上下文为空");
                return;
            }
            ctx.startActivity(intent);
            Log.record("已打开页面: " + url);
        } catch (Throwable t) {
            Log.record("打开页面异常: " + t.getClass().getSimpleName());
            Log.printStackTrace(TAG, t);
        }
    }

    public static Object handleAlphaRequest(String type, String fun, Object data) {
        try {
            return Class.forName("com.surexu.sesame.model.extensions.ExtensionsHandleAlpha")
                    .getMethod("handleAlphaRequest", String.class, String.class, Object.class)
                    .invoke(null, type, fun, data);
        } catch (Throwable e) {
            // catch Throwable 而非 Exception：类缺失/构造失败/静态初始化异常都可能是 Error
            // （ExtensionAlpha 未打包、字段初始化抛 NoClassDefFoundError 等），
            // 调用方 AntSports.walk 等没有外层 try，漏出去会打断任务主循环
            return null;
        }
    }
    /**
     * 模拟请求：直接以传入的 mtop 接口 api/version/data 发一次真实请求（UI 服务页入口）。
     * 请求结果写日志，并通过 com.surexu.sesame.mockRequestResult 广播回传给 UI 进程展示。
     */
    private static void mockRequest(String method, String data) {
        try {
            String result = ApplicationHook.requestString(method, data);
            String display;
            if (StringUtil.isEmpty(result)) {
                display = "模拟请求[" + method + "] 返回为空（可能失败）";
            } else if (result.length() > 2000) {
                display = "模拟请求[" + method + "] 返回长度 " + result.length() + "，前 2000 字符:\n" + result.substring(0, 2000);
            } else {
                display = "模拟请求[" + method + "] 返回:\n" + result;
            }
            Log.record(display);
            sendMockResult(method, result);
        } catch (Throwable th) {
            Log.err(TAG, "mockRequest err:", th);
            sendMockResult(method, "模拟请求异常: " + th.getMessage());
        }
    }

    private static void sendMockResult(String method, String result) {
        try {
            String replyResult = result;
            if (replyResult != null && replyResult.length() > 10000) {
                replyResult = replyResult.substring(0, 10000) + "\n...(已截断)";
            }
            Intent reply = new Intent("com.surexu.sesame.mockRequestResult");
            reply.putExtra("method", method);
            reply.putExtra("result", replyResult);
            Context context = ApplicationHook.getContext();
            if (context != null) {
                context.sendBroadcast(reply);
            }
        } catch (Throwable ignore) {
        }
    }

    private static void getWateredItems() {
        Status.getWateredFriendToday();
    }
    
    private static void getWateringItems() {
        Status.getWateringFriendToday();
    }

    private static void fillWateredFriendList() {
        Status.fillWateredFriendList();
    }
    
    private static void getNewTreeItems() {
        try {
            JSONObject jo = new JSONObject(ProtectTreeRpcCall.queryTreeItemsForExchange("COMING", "project"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray ja = jo.getJSONArray("treeItems");
            if (ja.length() == 0) {
                Log.forest("新树上苗🌱[当前没有新树上苗信息!]");
                return;
            }
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.getJSONObject(i);
                if (!jo.has("projectType"))
                    continue;
                if (!"TREE".equals(jo.getString("projectType")))
                    continue;
                if (!"COMING".equals(jo.getString("applyAction")))
                    continue;
                String projectId = jo.getString("itemId");
                queryTreeForExchange(projectId);
            }
        } catch (Throwable t) {
            Log.err(TAG, "getTreeItems err:", t);
        }
    }

    private static void queryTreeForExchange(String projectId) {
        try {
            JSONObject jo = new JSONObject(ProtectTreeRpcCall.queryTreeForExchange(projectId));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject exchangeableTree = jo.optJSONObject("exchangeableTree");
            if (exchangeableTree == null) {
                return;
            }
            int currentBudget = exchangeableTree.optInt("currentBudget", 0);
            String region = exchangeableTree.optString("region", "");
            String treeName = exchangeableTree.optString("treeName", "");
            String tips = "不可合种";
            if (exchangeableTree.optBoolean("canCoexchange", false)) {
                JSONObject extendInfo = exchangeableTree.optJSONObject("extendInfo");
                String cooperateTypes = extendInfo == null ? "" : extendInfo.optString("cooperate_template_id_list", "");
                tips = StringUtil.isEmpty(cooperateTypes) ? "可以合种" : "可以合种-合种类型：" + cooperateTypes;
            }
            Log.forest("新树上苗🌱[" + region + "-" + treeName + "]#" + currentBudget + "株-" + tips);
        } catch (Throwable t) {
            Log.err(TAG, "queryTreeForExchange err:", t);
        }
    }

    private static void getTreeItems() {
        try {
            JSONObject jo = new JSONObject(ProtectTreeRpcCall.queryTreeItemsForExchange("AVAILABLE,ENERGY_LACK", "project"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray ja = jo.getJSONArray("treeItems");
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.getJSONObject(i);
                if (!jo.has("projectType"))
                    continue;
                String projectId = jo.getString("itemId");
                String itemName = jo.getString("itemName");
                getTreeCurrentBudget(projectId, itemName);
                TimeUtil.sleep(100);
            }
        } catch (Throwable t) {
            Log.err(TAG, "getTreeItems err:", t);
        }
    }

    private static void getTreeCurrentBudget(String projectId, String treeName) {
        try {
            JSONObject jo = new JSONObject(ProtectTreeRpcCall.queryTreeForExchange(projectId));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                JSONObject exchangeableTree = jo.optJSONObject("exchangeableTree");
                if (exchangeableTree == null) {
                    return;
                }
                int currentBudget = exchangeableTree.optInt("currentBudget", 0);
                String region = exchangeableTree.optString("region", "");
                Log.forest("树苗查询🌱[" + region + "-" + treeName + "]#剩余:" + currentBudget);
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryTreeForExchange err:", t);
        }
    }

    private static void queryAreaTrees() {
        try {
            JSONObject jo = new JSONObject(ProtectTreeRpcCall.queryAreaTrees());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject areaTrees = jo.getJSONObject("areaTrees");
            JSONObject regionConfig = jo.getJSONObject("regionConfig");
            Iterator<String> regionKeys = regionConfig.keys();
            while (regionKeys.hasNext()) {
                String regionKey = regionKeys.next();
                if (!areaTrees.has(regionKey)) {
                    JSONObject region = regionConfig.getJSONObject(regionKey);
                    String regionName = region.optString("regionName");
                    Log.forest("未解锁地区🗺️[" + regionName + "]");
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "queryAreaTrees err:", t);
        }
    }

    private static void getUnlockTreeItems() {
        try {
            JSONObject jo = new JSONObject(ProtectTreeRpcCall.queryTreeItemsForExchange("", "project"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray ja = jo.getJSONArray("treeItems");
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.getJSONObject(i);
                if (!jo.has("projectType"))
                    continue;
                int certCountForAlias = jo.optInt("certCountForAlias", -1);
                if (certCountForAlias == 0) {
                    String itemName = jo.optString("itemName");
                    String region = jo.optString("region");
                    String organization = jo.optString("organization");
                    Log.forest("未解锁项目🐘[" + region + "-" + itemName + "]#" + organization);
                }
            }
        } catch (Throwable t) {
            Log.err(TAG, "getUnlockTreeItems err:", t);
        }
    }

    private static void addCustomWalkPathIdList(String pathId) {
        if (!StringUtil.isEmpty(pathId)) {
            String pathName = AntSportsRpcCall.queryPathName(pathId);
            if (pathName == null) {
                Toast.show("添加自定义路线列表失败:找不到路线信息");
                return;
            }
            PathThemeMapListMap.load();
            PathThemeMapListMap.add(pathId, pathName);
            PathThemeMapListMap.save();
            Toast.show("添加自定义路线列表成功:" + pathName);
        }
    }

    private static void addCustomWalkPathIdQueue(String pathId) {
        if (!StringUtil.isEmpty(pathId)) {
            String pathName = AntSportsRpcCall.queryPathName(pathId);
            if (pathName == null) {
                Toast.show("添加待行走路线队列失败:找不到路线信息");
                return;
            }
            if (TokenConfig.addCustomWalkPathIdQueue(pathId)) {
                Toast.show("添加待行走路线队列成功:" + pathName);
            }
        }
    }

    private static void clearCustomWalkPathIdQueue() {
        if (TokenConfig.clearCustomWalkPathIdQueue()) {
            Toast.show("清除待行走路线队列成功");
        }
    }
}
