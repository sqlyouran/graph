package com.looptrip;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BasicContractReview {

    private static final List<String> CHINESE_DAY_NUMBERS = List.of("", "一", "二", "三", "四", "五", "六", "七");
    private static final Pattern ACCOMMODATION = Pattern.compile("住宿|酒店|入住");
    private static final Pattern ATTRACTION = Pattern.compile("景点|游览|参观");
    private static final Pattern TOTAL_COST = Pattern.compile(
            "预计总花费\\s+\\d+(?:\\.\\d{1,2})?\\s+元\\s*$");

    public BasicContractReviewResult review(PlanRequest request, String markdown) {
        List<String> problems = new ArrayList<>();
        String content = StringUtils.hasText(markdown) ? markdown : "";

        for (int day = 1; day <= request.days(); day++) {
            Pattern dayHeading = dayHeadingPattern(day);
            if (!dayHeading.matcher(content).find()) {
                problems.add("缺少第 " + day + " 天安排");
            }
        }
        if (!ACCOMMODATION.matcher(content).find()) {
            problems.add("缺少住宿安排");
        }
        if (!ATTRACTION.matcher(content).find()) {
            problems.add("缺少景点安排");
        }
        if (!TOTAL_COST.matcher(content).find()) {
            problems.add("末尾缺少固定格式“预计总花费 XXXX 元”");
        }
        return new BasicContractReviewResult(problems.isEmpty(), problems);
    }

    private Pattern dayHeadingPattern(int day) {
        String number = "(?:" + day + "|" + CHINESE_DAY_NUMBERS.get(day) + ")";
        return Pattern.compile(
                "^.*(?:第\\s*" + number + "\\s*(?:天|日)|(?<![A-Za-z])day\\s*" + day + "(?!\\d)).*$",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    }
}
