package com.pick.voxel.debug;

// Movement in the interface, in one place so the Reduce motion setting can turn it off everywhere (#212).
//     v = Motion.approach(v, target, rate, dt)   eases toward target the same at any frame rate
//     e = Motion.smooth(v)                       an ease-in-out of a 0..1 value
// rate is per second: about 3/rate seconds to get there (rate 6: half a second). With Reduce motion on,
// approach() arrives at once and Motion.ambient() is false (no idle spinning).
public final class Motion {
    private Motion() {}

    public static volatile boolean reduce;

    public static float approach(float v, float target, float rate, float dt) {
        if (reduce) return target;
        float n = v + (target - v) * (1f - (float) Math.exp(-rate * dt));
        return Math.abs(target - n) < 0.002f ? target : n;
    }

    public static float smooth(float x) {
        x = Math.clamp(x, 0f, 1f);
        return x * x * (3 - 2 * x);
    }

    // Movement for its own sake (globes turning while nobody touches them): off with Reduce motion
    public static boolean ambient() { return !reduce; }
}
