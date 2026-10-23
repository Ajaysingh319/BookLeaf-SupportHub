package com.bookleaf.support.testsupport;

import com.bookleaf.support.ai.AiUnavailableException;
import com.bookleaf.support.ai.LlmClient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A fake AI provider for tests. Each test decides what the "model" does (answer, fail, rate-limit)
 * and can inspect exactly what was sent, so prompts and fallbacks are tested without a real API.
 * Calls can also be held back until released, to test what happens while the AI is still thinking.
 */
public class ControllableLlmClient implements LlmClient {

    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Supplier<LlmResult> behaviour;
    private volatile CountDownLatch gate;

    public ControllableLlmClient() {
        reset();
    }

    /** Back to the "no API key configured" state: every call fails as the real client would. */
    public void reset() {
        release();
        requests.clear();
        behaviour = () -> {
            throw new AiUnavailableException("not_configured");
        };
    }

    public void respondWith(String content) {
        behaviour = () -> new LlmResult(content, 210, 40, 25L);
    }

    public void failWith(String reasonCode) {
        behaviour = () -> {
            throw new AiUnavailableException(reasonCode);
        };
    }

    /** Calls now wait (up to 20 seconds) until release() is called. */
    public void holdResponses() {
        gate = new CountDownLatch(1);
    }

    public void release() {
        CountDownLatch current = gate;
        if (current != null) current.countDown();
        gate = null;
    }

    public List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String provider() {
        return "test-provider";
    }

    @Override
    public String model() {
        return "test-model";
    }

    @Override
    public LlmResult complete(LlmRequest request) {
        requests.add(request);
        CountDownLatch held = gate;
        if (held != null) {
            try {
                held.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        return behaviour.get();
    }
}
