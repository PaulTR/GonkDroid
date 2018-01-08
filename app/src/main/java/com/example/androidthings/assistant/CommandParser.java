package com.example.androidthings.assistant;

public class CommandParser {

    private DroidCommands mListener;

    private String COMMAND_REBOOT = "reboot";
    private String COMMAND_BACKGROUND_LOOP = "loop";
    private String COMMAND_PLAY_SOUND = "playsound";

    public CommandParser(DroidCommands listener) {
        mListener = listener;
    }

    public void parse(String payload) {
        String arguments[] = payload.split(" ", 2);
        if( arguments[0].equalsIgnoreCase(COMMAND_REBOOT) ) {
            mListener.onRestartCommand();
        } else if( arguments[0].equalsIgnoreCase(COMMAND_BACKGROUND_LOOP)) {
            if( arguments[1].equalsIgnoreCase("on")) {
                mListener.onBackgroundSoundToggle(true);
            } else if( arguments[1].equalsIgnoreCase("off")) {
                mListener.onBackgroundSoundToggle(false);
            }
        } else if( arguments[0].equalsIgnoreCase(COMMAND_PLAY_SOUND)) {
            if( arguments[1].matches("\\d+(?:\\.\\d+)?")) {
                mListener.onPlaySound(Integer.valueOf(arguments[1]));
            }
        }
    }

}
