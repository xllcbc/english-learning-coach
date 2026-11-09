package com.writelab.practice;

import com.writelab.feedback.FeedbackModel;
import com.writelab.feedback.ModelMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports the active model mode so the page can label Mock vs real before any practice is
 * submitted, rather than inferring it only from a result.
 */
@RestController
@RequestMapping("/api/model-mode")
public class ModelModeController {

    private final FeedbackModel feedbackModel;

    public ModelModeController(FeedbackModel feedbackModel) {
        this.feedbackModel = feedbackModel;
    }

    @GetMapping
    ModelModeResponse current() {
        ModelMode mode = feedbackModel.mode();
        return new ModelModeResponse(mode, mode == ModelMode.MOCK);
    }

    record ModelModeResponse(ModelMode modelMode, boolean mockMode) {
    }
}
