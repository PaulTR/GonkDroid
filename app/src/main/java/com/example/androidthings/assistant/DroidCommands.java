package com.example.androidthings.assistant;

public interface DroidCommands {

    void onRestartCommand();
    void onBackgroundSoundToggle(boolean enabled);
    void onPlaySound(int sound);

}
