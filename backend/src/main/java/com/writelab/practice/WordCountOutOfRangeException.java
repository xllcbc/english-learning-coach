package com.writelab.practice;

public class WordCountOutOfRangeException extends RuntimeException {

    public WordCountOutOfRangeException(int wordCount) {
        super("写作练习必须为 200-500 词（当前 " + wordCount + " 词）。");
    }
}
