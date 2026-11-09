package com.writelab.writing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WordCounter {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*");

    private WordCounter() {
    }

    public static int count(String text) {
        if (text == null) {
            return 0;
        }
        Matcher matcher = TOKEN.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
