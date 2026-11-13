package com.surexu.sesame.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.surexu.sesame.util.idMap.YouthExchangeBenefitIdMap;

/** 青春特权兑换项，供 SelectModelField 勾选（id=benefitId，name=标题）。 */
public class YouthExchangeBenefit extends IdAndName {

    public YouthExchangeBenefit(String i, String n) {
        id = i;
        name = n;
    }

    public static List<YouthExchangeBenefit> getList() {
        List<YouthExchangeBenefit> list = new ArrayList<>();
        Set<Map.Entry<String, String>> idSet = YouthExchangeBenefitIdMap.getMap().entrySet();
        for (Map.Entry<String, String> entry : idSet) {
            list.add(new YouthExchangeBenefit(entry.getKey(), entry.getValue()));
        }
        return list;
    }
}
