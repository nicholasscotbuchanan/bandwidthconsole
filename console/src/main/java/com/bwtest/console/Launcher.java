package com.bwtest.console;

/**
 * Plain entry point for the shaded fat jar. Launching {@link App} (an
 * {@code Application} subclass) directly from an executable jar trips the JavaFX
 * "missing components" check; bouncing through a non-Application main avoids it.
 */
public class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
