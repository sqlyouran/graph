package com.looptrip;

/** 图边：从哪来、到哪去、什么条件下能走。条件名字由状态实现解释。 */
public record EdgeSpec(String from, String to, String condition) {
}
