package com.dancingcat;

import com.dancingcat.config.AppConfig;
import com.dancingcat.config.AppPaths;
import com.dancingcat.ui.CatWindow;
import javafx.application.Application;
import javafx.stage.Stage;

import java.util.prefs.Preferences;

/**
 * Entry point. The window is JavaFX; Java2D (BufferedImage) is only used off-screen for drawing
 * the pixel art, so AWT runs headless and never opens a window of its own.
 */
public final class Main {

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        Application.launch(App.class, args);
    }

    public static final class App extends Application {
        @Override
        public void start(Stage stage) {
            AppConfig config = AppConfig.load(AppPaths.configFile());
            // User preferences (cat position, lyrics on/off, ...) are stored under ~/Library/Preferences on macOS
            Preferences prefs = Preferences.userNodeForPackage(Main.class);

            CatController controller = new CatController(config, prefs, url -> getHostServices().showDocument(url));
            new CatWindow(stage, controller, prefs).show();
            controller.start();
        }
    }
}
