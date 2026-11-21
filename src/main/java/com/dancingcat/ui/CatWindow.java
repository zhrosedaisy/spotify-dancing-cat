package com.dancingcat.ui;

import com.dancingcat.CatController;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * The borderless, transparent, always-on-top and draggable cat window.
 *
 * Positions are always computed from "where the cat is on screen". When the cat gets close to
 * the top of the screen the panel switches to the "flipped" layout (buttons/lyrics below the
 * cat), so the cat can go all the way up without the window bumping into the menu bar.
 */
public final class CatWindow {

    private static final String PREF_X = "windowX";
    private static final String PREF_CAT_Y = "catScreenY";

    private final JWindow window = new JWindow();
    private final CatPanel panel;
    private final CatController controller;
    private final Preferences prefs;

    public CatWindow(CatController controller, Preferences prefs) {
        this.controller = controller;
        this.panel = controller.panel();
        this.prefs = prefs;
        window.setAlwaysOnTop(true);
        window.setBackground(new Color(0, 0, 0, 0));
        window.getRootPane().putClientProperty("Window.shadow", Boolean.FALSE); // disable the macOS window shadow
        window.setContentPane(panel);
        window.pack();
        restorePosition();
        installMouse();
    }

    public void show() { window.setVisible(true); }

    // ---------------------------------------------------------------- konum

    private void restorePosition() {
        Rectangle screen = usableBounds(new Point(0, 0));
        int defaultX = screen.x + screen.width - window.getWidth() - 20;
        int defaultCatY = screen.y + screen.height - panel.catPixelSize() - 10;
        placeCat(prefs.getInt(PREF_X, defaultX), prefs.getInt(PREF_CAT_Y, defaultCatY));
    }

    private int catScreenY() {
        return window.getY() + panel.catBounds().y;
    }

    /** Places the cat at (window x, top edge of the cat) on screen. */
    private void placeCat(int windowX, int catY) {
        Rectangle screen = usableBounds(new Point(windowX + window.getWidth() / 2, catY));
        // keep the cat on screen
        catY = Math.max(screen.y, Math.min(catY, screen.y + screen.height - panel.catPixelSize() / 2));
        int minX = screen.x - (window.getWidth() - panel.catPixelSize()) / 2;
        int maxX = screen.x + screen.width - (window.getWidth() + panel.catPixelSize()) / 2;
        windowX = Math.max(minX, Math.min(windowX, maxX));

        boolean flip = catY - panel.overlayHeight() < screen.y; // no room above -> buttons go below
        panel.setFlipped(flip);
        window.setLocation(windowX, flip ? catY : catY - panel.overlayHeight());
    }

    /** The area of the screen containing the point, excluding the menu bar / Dock. */
    private static Rectangle usableBounds(Point p) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = device.getDefaultConfiguration();
            Rectangle b = gc.getBounds();
            if (b.contains(p)) {
                Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
                return new Rectangle(b.x + in.left, b.y + in.top,
                        b.width - in.left - in.right, b.height - in.top - in.bottom);
            }
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    }

    private void savePosition() {
        prefs.putInt(PREF_X, window.getX());
        prefs.putInt(PREF_CAT_Y, catScreenY());
    }

    // ---------------------------------------------------------------- fare

    private void installMouse() {
        MouseAdapter mouse = new MouseAdapter() {
            private Point pressOnScreen;
            private int windowXAtPress;
            private int catYAtPress;
            private boolean dragged;

            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) { showMenu(e); return; }
                pressOnScreen = e.getLocationOnScreen();
                windowXAtPress = window.getX();
                catYAtPress = catScreenY();
                dragged = false;
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (pressOnScreen == null) return;
                int dx = e.getXOnScreen() - pressOnScreen.x;
                int dy = e.getYOnScreen() - pressOnScreen.y;
                if (Math.abs(dx) + Math.abs(dy) > 3) dragged = true;
                if (dragged) placeCat(windowXAtPress + dx, catYAtPress + dy);
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) { showMenu(e); return; }
                if (dragged) savePosition();
                else if (SwingUtilities.isLeftMouseButton(e)) {
                    if (e.getClickCount() == 2) onDoubleClick(e.getPoint());
                    else onClick(e.getPoint());
                }
                pressOnScreen = null;
            }

            @Override public void mouseMoved(MouseEvent e) {
                Control c = panel.controlAt(e.getPoint()).orElse(null);
                panel.setHover(c);
                panel.setCursor(Cursor.getPredefinedCursor(c != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }

            @Override public void mouseExited(MouseEvent e) {
                panel.setHover(null);
            }
        };
        panel.addMouseListener(mouse);
        panel.addMouseMotionListener(mouse);
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

    /** Right-click menu. */
    private void showMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        boolean connected = controller.isSpotifyConnected();
        if (controller.hasSpotify() && !connected) {
            JMenuItem connect = new JMenuItem("Connect to Spotify");
            connect.addActionListener(a -> controller.connectSpotify());
            menu.add(connect);
        }
        JCheckBoxMenuItem lyrics = new JCheckBoxMenuItem("Show lyrics", controller.lyricsEnabled());
        lyrics.addActionListener(a -> controller.setLyricsEnabled(lyrics.isSelected()));
        menu.add(lyrics);
        JMenu timing = new JMenu("Lyric timing");
        JMenuItem earlier = new JMenuItem("Lyrics are late → show 0.1 s earlier");
        earlier.addActionListener(a -> controller.nudgeLyricOffset(100));
        JMenuItem later = new JMenuItem("Lyrics are early → show 0.1 s later");
        later.addActionListener(a -> controller.nudgeLyricOffset(-100));
        timing.add(earlier);
        timing.add(later);
        menu.add(timing);
        JMenu costumes = new JMenu("Costume");
        ButtonGroup group = new ButtonGroup();
        for (Costume c : Costume.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(c.label(), c == controller.costume());
            item.addActionListener(a -> controller.setCostume(c));
            group.add(item);
            costumes.add(item);
        }
        menu.add(costumes);
        JMenuItem tempo = new JMenuItem(controller.tempoLabel());
        tempo.setEnabled(false);
        menu.add(tempo);
        if (!connected) { // Spotify yokken dans/uyku elle denenebilsin
            boolean sleeping = panel.state() == CatState.SLEEPING;
            JMenuItem toggle = new JMenuItem(sleeping ? "Dans et (test)" : "Uyu (test)");
            toggle.addActionListener(a -> panel.setState(sleeping ? CatState.DANCING : CatState.SLEEPING));
            menu.add(toggle);
        }
        menu.addSeparator();
        JMenuItem quit = new JMenuItem("Quit");
        quit.addActionListener(a -> System.exit(0));
        menu.add(quit);
        menu.show(panel, e.getX(), e.getY());
    }
}
