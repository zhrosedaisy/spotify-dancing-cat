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
 * Kenarlıksız, şeffaf, her zaman üstte duran ve sürüklenebilen kedi penceresi.
 *
 * Konum her zaman "kedinin ekrandaki yeri" üzerinden hesaplanır. Kedi ekranın tepesine
 * yaklaşınca panel "flipped" yerleşime geçer (butonlar/sözler kedinin altına), böylece
 * pencere menü çubuğuna takılmadan kedi en üste kadar çıkabilir.
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
        window.getRootPane().putClientProperty("Window.shadow", Boolean.FALSE); // macOS gölgesini kapat
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

    /** Kediyi ekranda (pencere x'i, kedinin üst kenarı) noktasına yerleştirir. */
    private void placeCat(int windowX, int catY) {
        Rectangle screen = usableBounds(new Point(windowX + window.getWidth() / 2, catY));
        // Kedi ekranın dışına kaçmasın
        catY = Math.max(screen.y, Math.min(catY, screen.y + screen.height - panel.catPixelSize() / 2));
        int minX = screen.x - (window.getWidth() - panel.catPixelSize()) / 2;
        int maxX = screen.x + screen.width - (window.getWidth() + panel.catPixelSize()) / 2;
        windowX = Math.max(minX, Math.min(windowX, maxX));

        boolean flip = catY - panel.overlayHeight() < screen.y; // üstte yer yoksa butonlar alta
        panel.setFlipped(flip);
        window.setLocation(windowX, flip ? catY : catY - panel.overlayHeight());
    }

    /** Noktanın bulunduğu ekranın menü çubuğu / Dock hariç alanı. */
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

    /** Sol tık: butona basıldıysa komutu gönder; kediye basıldıysa butonları aç/kapat. */
    private void onClick(Point p) {
        Optional<Control> control = panel.controlAt(p);
        if (control.isPresent()) {
            panel.showControls(); // basıldıkça açık kalsın
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

    /** Kediye çift tıklama: şarkıyı beğen. İlk tıklamanın açıp kapadığı butonları geri al. */
    private void onDoubleClick(Point p) {
        if (!panel.catBounds().contains(p) || panel.controlAt(p).isPresent()) return;
        if (!controller.isSpotifyConnected()) return;
        if (panel.controlsVisible()) panel.hideControls();
        else panel.showControls();
        controller.likeByDoubleClick();
    }

    /** Sağ tık menüsü. */
    private void showMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        boolean connected = controller.isSpotifyConnected();
        if (controller.hasSpotify() && !connected) {
            JMenuItem connect = new JMenuItem("Spotify'a bağlan");
            connect.addActionListener(a -> controller.connectSpotify());
            menu.add(connect);
        }
        JCheckBoxMenuItem lyrics = new JCheckBoxMenuItem("Şarkı sözlerini göster", controller.lyricsEnabled());
        lyrics.addActionListener(a -> controller.setLyricsEnabled(lyrics.isSelected()));
        menu.add(lyrics);
        JMenu timing = new JMenu("Söz zamanlaması");
        JMenuItem earlier = new JMenuItem("Sözler geç kalıyor → 0,1 sn öne al");
        earlier.addActionListener(a -> controller.nudgeLyricOffset(100));
        JMenuItem later = new JMenuItem("Sözler erken geliyor → 0,1 sn geri al");
        later.addActionListener(a -> controller.nudgeLyricOffset(-100));
        timing.add(earlier);
        timing.add(later);
        menu.add(timing);
        JMenu costumes = new JMenu("Kostüm");
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
        JMenuItem quit = new JMenuItem("Çıkış");
        quit.addActionListener(a -> System.exit(0));
        menu.add(quit);
        menu.show(panel, e.getX(), e.getY());
    }
}
