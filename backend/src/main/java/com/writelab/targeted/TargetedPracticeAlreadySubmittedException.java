package com.writelab.targeted;

public class TargetedPracticeAlreadySubmittedException extends RuntimeException {

    public TargetedPracticeAlreadySubmittedException() {
        super("这个 Targeted Practice 已经提交过 Practice Attempt，不能再提交一次。");
    }
}
