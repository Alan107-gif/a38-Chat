package de.corecosmetic.a38chat;

final class DebugActivationCounter {
    enum Result {
        NONE,
        SHOW_FIVE_MORE_HINT,
        ACTIVATE
    }

    private int taps;

    Result tap(boolean alreadyEnabled) {
        if (alreadyEnabled) {
            taps = 0;
            return Result.NONE;
        }

        taps++;
        if (taps == 3) {
            return Result.SHOW_FIVE_MORE_HINT;
        }
        if (taps >= 8) {
            taps = 0;
            return Result.ACTIVATE;
        }
        return Result.NONE;
    }
}
