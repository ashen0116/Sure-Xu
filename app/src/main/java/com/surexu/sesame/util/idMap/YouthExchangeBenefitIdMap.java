package com.surexu.sesame.util.idMap;

import java.util.Map;

import com.surexu.sesame.util.FileUtil;

/** 青春特权「兑换列表」的 id->名称 映射，用于 UI 侧勾选兑换项（benefitId -> 标题）。 */
public class YouthExchangeBenefitIdMap {

    private static final StringMapStore STORE = new StringMapStore(FileUtil::getYouthExchangeBenefitIdMap);

    public static Map<String, String> getMap() {
        return STORE.getMap();
    }

    public static void add(String key, String value) {
        STORE.add(key, value);
    }

    public static void remove(String key) {
        STORE.remove(key);
    }

    public static void load(String userId) {
        STORE.load(userId);
    }

    public static boolean save(String userId) {
        return STORE.save(userId);
    }
}
