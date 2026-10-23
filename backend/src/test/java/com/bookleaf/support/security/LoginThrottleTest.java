package com.bookleaf.support.security;

import com.bookleaf.support.TestProperties;
import com.bookleaf.support.testsupport.MutableClock;
import com.bookleaf.support.web.error.TooManyAttemptsException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Limits from the test configuration: 5 failures per (address, email), 50 per address, 15 minute lockout. */
class LoginThrottleTest {

    private final MutableClock clock = new MutableClock();
    private final LoginThrottle throttle = new LoginThrottle(TestProperties.create(), clock);

    private void fail(String address, String email, int times) {
        for (int i = 0; i < times; i++) {
            throttle.checkAllowed(address, email);
            throttle.recordFailure(address, email);
        }
    }

    @Test
    void anAccountIsLockedAfterTheFifthFailure() {
        fail("1.1.1.1", "a@x.test", 4);
        assertThatCode(() -> throttle.checkAllowed("1.1.1.1", "a@x.test")).doesNotThrowAnyException();
        throttle.recordFailure("1.1.1.1", "a@x.test");   // the fifth

        assertThatThrownBy(() -> throttle.checkAllowed("1.1.1.1", "a@x.test")).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void theLockLastsFifteenMinutesAndThenEnds() {
        fail("1.1.1.1", "a@x.test", 5);
        clock.advance(Duration.ofMinutes(14));
        assertThatThrownBy(() -> throttle.checkAllowed("1.1.1.1", "a@x.test")).isInstanceOf(TooManyAttemptsException.class);

        clock.advance(Duration.ofMinutes(2));
        assertThatCode(() -> throttle.checkAllowed("1.1.1.1", "a@x.test")).doesNotThrowAnyException();
        // and counting starts again from zero
        fail("1.1.1.1", "a@x.test", 4);
        assertThatCode(() -> throttle.checkAllowed("1.1.1.1", "a@x.test")).doesNotThrowAnyException();
    }

    @Test
    void theRetryAfterTimeCountsDownAndNeverExceedsTheLockout() {
        fail("1.1.1.1", "a@x.test", 5);
        long first = retryAfter("1.1.1.1", "a@x.test");
        assertThat(first).isBetween(14L * 60, 15L * 60);

        clock.advance(Duration.ofMinutes(10));
        assertThat(retryAfter("1.1.1.1", "a@x.test")).isBetween(4L * 60, 5L * 60 + 1);
    }

    private long retryAfter(String address, String email) {
        try {
            throttle.checkAllowed(address, email);
        } catch (TooManyAttemptsException ex) {
            return ex.getRetryAfterSeconds();
        }
        throw new AssertionError("Expected a lock");
    }

    @Test
    void aLockedAccountDoesNotLockOtherAccountsFromTheSameAddress() {
        fail("1.1.1.1", "a@x.test", 5);
        assertThatCode(() -> throttle.checkAllowed("1.1.1.1", "b@x.test")).doesNotThrowAnyException();
        // nor the same email from a different address
        assertThatCode(() -> throttle.checkAllowed("2.2.2.2", "a@x.test")).doesNotThrowAnyException();
    }

    @Test
    void emailsAreComparedWithoutCaseOrSurroundingSpaces() {
        fail("1.1.1.1", "A@X.test", 3);
        fail("1.1.1.1", "  a@x.TEST ", 2);
        assertThatThrownBy(() -> throttle.checkAllowed("1.1.1.1", "a@x.test")).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void unknownEmailsAreCountedLikeKnownOnes() {
        // the throttle has no idea which accounts exist, so it cannot reveal it
        fail("1.1.1.1", "nobody@x.test", 5);
        assertThatThrownBy(() -> throttle.checkAllowed("1.1.1.1", "nobody@x.test")).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void aSuccessfulSignInClearsTheAccountsCountButNotTheAddressCount() {
        fail("1.1.1.1", "a@x.test", 4);
        throttle.recordSuccess("1.1.1.1", "a@x.test");
        fail("1.1.1.1", "a@x.test", 4);   // would have been 8 without the reset
        assertThatCode(() -> throttle.checkAllowed("1.1.1.1", "a@x.test")).doesNotThrowAnyException();
    }

    @Test
    void anAddressTryingManyAccountsIsLockedAtTheHigherLimit() {
        for (int i = 0; i < 49; i++) {
            fail("9.9.9.9", "user" + i + "@x.test", 1);
        }
        assertThatCode(() -> throttle.checkAllowed("9.9.9.9", "fresh@x.test")).doesNotThrowAnyException();
        throttle.recordFailure("9.9.9.9", "user49@x.test");   // the 50th failure from this address

        assertThatThrownBy(() -> throttle.checkAllowed("9.9.9.9", "fresh@x.test")).isInstanceOf(TooManyAttemptsException.class);
        assertThatCode(() -> throttle.checkAllowed("8.8.8.8", "fresh@x.test")).doesNotThrowAnyException();
    }

    @Test
    void oldFailuresAreForgottenWhenTheWindowPasses() {
        fail("1.1.1.1", "a@x.test", 4);
        clock.advance(Duration.ofMinutes(16));
        fail("1.1.1.1", "a@x.test", 4);   // the first four no longer count
        assertThatCode(() -> throttle.checkAllowed("1.1.1.1", "a@x.test")).doesNotThrowAnyException();
    }

    @Test
    void keepingFailingWhileLockedDoesNotExtendTheLock() {
        fail("1.1.1.1", "a@x.test", 5);
        for (int i = 0; i < 20; i++) {
            throttle.recordFailure("1.1.1.1", "a@x.test");   // attempts during the lock (refused before this point in the app)
        }
        clock.advance(Duration.ofMinutes(16));
        assertThatCode(() -> throttle.checkAllowed("1.1.1.1", "a@x.test")).doesNotThrowAnyException();
    }

    @Test
    void messagesTellThePersonHowLongToWait() {
        fail("1.1.1.1", "a@x.test", 5);
        assertThatThrownBy(() -> throttle.checkAllowed("1.1.1.1", "a@x.test"))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        ex -> assertThat(ex.getMessage()).contains("Too many failed sign-in attempts").containsPattern("\\d+ minutes?"));
    }
}
