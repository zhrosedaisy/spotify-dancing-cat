package com.dancingcat;

import com.dancingcat.config.AppConfig;
import com.dancingcat.ui.CatWindow;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.prefs.Preferences;

public final class Main {

    public static void main(String[] args) {
        AppConfig config = AppConfig.load(Path.of("config.properties"));
        // User preferences (cat position, lyrics on/off, ...) are stored under ~/Library/Preferences on macOS
        Preferences prefs = Preferences.userNodeForPackage(Main.class);

        SwingUtilities.invokeLater(() -> {
            CatController controller = new CatController(config, prefs);
            new CatWindow(controller, prefs).show();
            controller.start();
        });
    }
}
