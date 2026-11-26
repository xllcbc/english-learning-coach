package com.writelab.evaluation;

import com.writelab.agent.TutorModelClient;
import com.writelab.agent.TutorModelContext;
import com.writelab.agent.TutorModelDecision;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Deterministic scripted model used by the WebSocket contract test and the on-demand gate
 * evaluations. Each call consumes one decision; an unscripted call fails the test instead of
 * silently reaching a provider.
 */
public final class ScriptedTutorModelClient implements TutorModelClient {

    private final BlockingQueue<Function<TutorModelContext, TutorModelDecision>> script =
            new LinkedBlockingQueue<>();
    private final AtomicInteger calls = new AtomicInteger();

    public void reset() {
        script.clear();
        calls.set(0);
    }

    @SafeVarargs
    public final void script(Function<TutorModelContext, TutorModelDecision>... steps) {
        script.addAll(List.of(steps));
    }

    public int callCount() {
        return calls.get();
    }

    @Override
    public TutorModelDecision next(TutorModelContext context) {
        calls.incrementAndGet();
        Function<TutorModelContext, TutorModelDecision> step = script.poll();
        if (step == null) {
            throw new AssertionError("Model was called beyond the scripted decisions");
        }
        return step.apply(context);
    }
}
