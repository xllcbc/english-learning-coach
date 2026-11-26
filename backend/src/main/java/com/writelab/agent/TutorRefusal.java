package com.writelab.agent;

/**
 * The model-facing contract for Teacher Conversation when the turn has no citable source. A
 * refusal is not an answer with a caveat: it is the explicit statement below, so the service can
 * tell a grounded refusal apart from a fabricated assertion that simply forgot a citation.
 */
public final class TutorRefusal {

    public static final String NO_GROUNDED_SOURCE =
            "我目前没有可引用的依据材料，不能可靠回答这个问题。"
                    + "请先在「我的文档」补充材料，或重新生成教师讲解后再问。";

    private TutorRefusal() {
    }

    public static boolean isNoGroundedSourceRefusal(String answer) {
        return answer != null && answer.strip().equals(NO_GROUNDED_SOURCE);
    }
}
