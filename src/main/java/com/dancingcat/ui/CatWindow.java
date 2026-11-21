package com.dancingcat.ui;

import com.dancingcat.CatController;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * The borderless, transparent, always-on-top and draggable cat window (JavaFX).
 *
 * CatPanel renders each frame into an image; this window copies it into a JavaFX WritableImage.
 * (The first version used a Swing JWindow, but on macOS translucent Swing windows occasionally
 * show a fully cleared frame while being repainted, which made the cat blink.)
 *
 * Positions are always computed from "where the cat is on screen". When the cat gets close to
 * the top of the screen the panel switches to the "flipped" layout (buttons/lyrics below the
 * cat), so the cat can go all the way up.
 */
public final class CatWindow {

    private static final String PREF_X = "windowX";
    private static final String PREF_CAT_Y = "catScreenY";
    private static final long FRAME_NS = 30_000_000L; // check for changes ~33 times per second

    private final Stage stage;
    private final CatPanel panel;
    private final CatController controller;
    private final Preferences prefs;
    private final ImageView view = new ImageView();

    private BufferedImage buffer;
    private WritableImage image;
    private ContextMenu menu;

    public CatWindow(Stage stage, CatController controller, Preferences prefs) {
        this.stage = stage;
        this.controller = controller;
        this.panel = controller.panel();
        this.prefs = prefs;

        view.setFitWidth(panel.width());
        view.setFitHeight(panel.height());
        view.setSmooth(true);
        Pane root = new Pane(view);
        root.setStyle("-fx-background-color: transparent;");

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setTitle("Dancing Cat");
        stage.setScene(new Scene(root, panel.width(), panel.height(), Color.TRANSPARENT));

        installMouse();
        restorePosition();
    }

    public void show() {
        stage.show();
        new AnimationTimer() {
            private long last;

            @Override
            public void handle(long now) {
                if (now - last < FRAME_NS) return;
                last = now;
                double scale = Math.max(1, stage.getOutputScaleX()); // 2 on Retina screens
                if (panel.tick() || needsResize(scale)) draw(scale);
            }
        }.start();
    }

    // ---------------------------------------------------------------- drawing

    private boolean needsResize(double scale) {
        return buffer == null || buffer.getWidth() != pixelSize(panel.width(), scale);
    }

    private static int pixelSize(int logical, double scale) {
        return (int) Math.round(logical * scale);
    }

    /** Renders the cat and copies the pixels into the JavaFX image in one go. */
    private void draw(double scale) {
        int w = pixelSize(panel.width(), scale), h = pixelSize(panel.height(), scale);
        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h) {
            buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
            image = new WritableImage(w, h);
            view.setImage(image);
        }
        panel.render(buffer, scale);
        int[] pixels = ((DataBufferInt) buffer.getRaster().getDataBuffer()).getData();
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbPreInstance(), pixels, 0, w);
    }

    // ---------------------------------------------------------------- position

    private void restorePosition() {
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double defaultX = screen.getMaxX() - panel.width() - 20;
        double defaultCatY = screen.getMaxY() - panel.catPixelSize() - 10;
        placeCat(prefs.getDouble(PREF_X, defaultX), prefs.getDouble(PREF_CAT_Y, defaultCatY));
    }

    private double catScreenY() {
        return stage.getY() + panel.catBounds().y;
    }

    /** Places the cat at (window x, top edge of the cat) on screen. */
    private void placeCat(double windowX, double catY) {
        Rectangle2D screen = usableBounds(windowX + panel.width() / 2.0, catY);
        int catPx = panel.catPixelSize();
        // keep the cat on screen
        catY = Math.max(screen.getMinY(), Math.min(catY, screen.getMaxY() - catPx / 2.0));
        double minX = screen.getMinX() - (panel.width() - catPx) / 2.0;
        double maxX = screen.getMaxX() - (panel.width() + catPx) / 2.0;
        windowX = Math.max(minX, Math.min(windowX, maxX));

        boolean flip = catY - panel.overlayHeight() < screen.getMinY(); // no room above -> buttons go below
        panel.setFlipped(flip);
        stage.setX(windowX);
        stage.setY(flip ? catY : catY - panel.overlayHeight());
    }

    /** The area of the screen containing the point, excluding the menu bar / Dock. */
    private static Rectangle2D usableBounds(double x, double y) {
        List<Screen> screens = Screen.getScreensForRectangle(x, y, 1, 1);
        return (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
    }

    private void savePosition() {
        prefs.putDouble(PREF_X, stage.getX());
        prefs.putDouble(PREF_CAT_Y, catScreenY());
    }

    // ---------------------------------------------------------------- mouse

    private void installMouse() {
        double[] press = new double[4]; // screenX, screenY, windowX, catY at press
        boolean[] dragged = {false};

        view.setOnMousePressed(e -> {
            if (menu != null) menu.hide();
            press[0] = e.getScreenX();
            press[1] = e.getScreenY();
            press[2] = stage.getX();
            press[3] = catScreenY();
            dragged[0] = false;
        });
        view.setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            double dx = e.getScreenX() - press[0], dy = e.getScreenY() - press[1];
            if (Math.abs(dx) + Math.abs(dy) > 3) dragged[0] = true;
            if (dragged[0]) placeCat(press[2] + dx, press[3] + dy);
        });
        view.setOnMouseReleased(e -> {
            if (dragged[0]) {
                savePosition();
            } else if (e.getButton() == MouseButton.PRIMARY && !e.isControlDown()) {
                if (e.getClickCount() == 2) onDoubleClick(point(e));
                else onClick(point(e));
            }
        });
        view.setOnMouseMoved(e -> {
            Control c = panel.controlAt(point(e)).orElse(null);
            panel.setHover(c);
            view.setCursor(c != null ? Cursor.HAND : Cursor.DEFAULT);
        });
        view.setOnMouseExited(e -> panel.setHover(null));
        view.setOnContextMenuRequested(e -> showMenu(e.getScreenX(), e.getScreenY()));
    }

    private static Point point(MouseEvent e) {
        return new Point((int) e.getX(), (int) e.getY());
    }

    /** Left click: on a button, send its command; on the cat, show/hide the buttons. */
    private void onClick(Point p) {
        Optional<Control> control = panel.controlAt(p);
        if (control.isPresent()) {
            panel.showControls(); // stay open while being used
            if (control.get() != Control.LYRICS) panel.react();
            controller.onControl(control.get());
            return;
        }
        if (!panel.catBounds().contains(p)) return;
        if (controller.isSpotifyConnected()) {
            if (panel.controlsVisible()) panel.hideControls();
            else panel.showControls();
        }
        panel.react();
    }

    /** Double-click on the cat: like the song. Undo the show/hide caused by the first click. */
    private void onDoubleClick(Point p) {
        if (!panel.catBounds().contains(p) || panel.controlAt(p).isPresent()) return;
        if (!controller.isSpotifyConnected()) return;
        if (panel.controlsVisible()) panel.hideControls();
        else panel.showControls();
        controller.likeByDoubleClick();
    }

    // ---------------------------------------------------------------- right-click menu

    private void showMenu(double screenX, double screenY) {
        if (menu != null) menu.hide();
        menu = new ContextMenu();
        boolean connected = controller.isSpotifyConnected();

        if (controller.hasSpotify() && !connected) {
            MenuItem connect = new MenuItem("Connect to Spotify");
            connect.setOnAction(a -> controller.connectSpotify());
            menu.getItems().add(connect);
        }

        CheckMenuItem lyrics = new CheckMenuItem("Show lyrics");
        lyrics.setSelected(controller.lyricsEnabled());
        lyrics.setOnAction(a -> controller.setLyricsEnabled(lyrics.isSelected()));

        Menu timing = new Menu("Lyric timing");
        MenuItem earlier = new MenuItem("Lyrics are late → show 0.1 s earlier");
        earlier.setOnAction(a -> controller.nudgeLyricOffset(100));
        MenuItem later = new MenuItem("Lyrics are early → show 0.1 s later");
        later.setOnAction(a -> controller.nudgeLyricOffset(-100));
        timing.getItems().addAll(earlier, later);

        Menu costumes = new Menu("Costume");
        ToggleGroup group = new ToggleGroup();
        for (Costume c : Costume.values()) {
            RadioMenuItem item = new RadioMenuItem(c.label());
            item.setToggleGroup(group);
            item.setSelected(c == controller.costume());
            item.setOnAction(a -> controller.setCostume(c));
            costumes.getItems().add(item);
        }

        MenuItem tempo = new MenuItem(controller.tempoLabel());
        tempo.setDisable(true);
        menu.getItems().addAll(lyrics, timing, costumes, tempo);

        if (!connected) { // without Spotify, dancing/sleeping can be tried by hand
            boolean sleeping = panel.state() == CatState.SLEEPING;
            MenuItem toggle = new MenuItem(sleeping ? "Dance (test)" : "Sleep (test)");
            toggle.setOnAction(a -> panel.setState(sleeping ? CatState.DANCING : CatState.SLEEPING));
            menu.getItems().add(toggle);
        }

        MenuItem quit = new MenuItem("Quit");
        quit.setOnAction(a -> {
            Platform.exit();
            System.exit(0);
        });
        menu.getItems().addAll(new SeparatorMenuItem(), quit);
        menu.show(view, screenX, screenY);
    }
}
