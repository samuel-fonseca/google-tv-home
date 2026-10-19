package io.github.samuelfonseca.tvhome;

/**
 * A damped spring in one dimension, stepped once per frame: the focus highlight uses four of
 * them. Retargeting mid-flight keeps the velocity, so a fast run of key presses curves instead of
 * restarting. Plain Java, so the tests run without Android.
 */
final class Spring {
    private static final float MAX_STEP = 1 / 240f; // sub-step so a dropped frame can't explode it

    private final float stiffness;
    private final float damping;
    float value, velocity, target;

    /** @param dampingRatio below 1 overshoots a little and settles; 1 doesn't overshoot */
    Spring(float stiffness, float dampingRatio) {
        this.stiffness = stiffness;
        this.damping = 2 * dampingRatio * (float) Math.sqrt(stiffness);
    }

    /** Jump straight to {@code v} and stop. */
    void snap(float v) {
        value = target = v;
        velocity = 0;
    }

    /** Move both the value and the target, as when what it points at scrolls. */
    void offset(float d) {
        value += d;
        target += d;
    }

    /** Advance {@code dt} seconds. Returns false once it has settled on the target. */
    boolean step(float dt) {
        int n = Math.max(1, (int) Math.ceil(dt / MAX_STEP));
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            float a = -stiffness * (value - target) - damping * velocity;
            velocity += a * h;
            value += velocity * h;
        }
        if (Math.abs(value - target) < 0.5f && Math.abs(velocity) < 5f) {
            snap(target);
            return false;
        }
        return true;
    }
}
