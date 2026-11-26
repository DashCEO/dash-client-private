package com.dash.client.client;

import net.minecraft.client.KeyMapping;

public final class SaveMode {
    private SaveMode() {}

    public static boolean isPaused() {
        return PauseManager.state() != PauseManager.State.ACTIVE;
    }

    static boolean isManagedBinding(KeyMapping binding) {
        return binding.getName().startsWith("key.dash-client.");
    }
}
