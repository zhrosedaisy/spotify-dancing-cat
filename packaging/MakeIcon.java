import com.dancingcat.ui.CatSprites;
import com.dancingcat.ui.CatSprites.Eyes;
import com.dancingcat.ui.CatSprites.Pose;
import com.dancingcat.ui.Costume;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generates the macOS app icon set (packaging/DancingCat.iconset) from the cat's own sprites.
 * Run: java -cp build/classes/java/main packaging/MakeIcon.java packaging/DancingCat.iconset
 * then: iconutil -c icns packaging/DancingCat.iconset -o packaging/DancingCat.icns
 */
public class MakeIcon {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();
        int[] sizes = {16, 32, 128, 256, 512};
        for (int s : sizes) {
            ImageIO.write(icon(s), "png", new File(dir, "icon_" + s + "x" + s + ".png"));
            ImageIO.write(icon(s * 2), "png", new File(dir, "icon_" + s + "x" + s + "@2x.png"));
        }
    }

    static BufferedImage icon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Full-bleed square (no margin, no rounded corners): macOS 26+ applies its own rounded mask.
        // Icons with transparent margins are treated as "legacy" and shrunk into a gray frame.
        double m = 0, w = size;
        g.setPaint(new GradientPaint(0, 0, new Color(0x7B5EA7), 0, size, new Color(0x2A2346)));
        g.fillRect(0, 0, size, size);

        // a few pixel "stars"
        g.setColor(new Color(255, 255, 255, 90));
        double[][] stars = {{0.22, 0.24}, {0.74, 0.20}, {0.83, 0.42}, {0.18, 0.55}, {0.66, 0.30}};
        for (double[] st : stars) {
            int px = Math.max(1, size / 96);
            g.fillRect((int) (st[0] * size), (int) (st[1] * size), px, px);
        }

        // the cat: headphones, one paw up, eyes open
        BufferedImage cat = CatSprites.sitting(new Pose(0, 0, true, false, 0, Eyes.OPEN), Costume.HEADPHONES);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int catSize = (int) Math.round(w * 0.92);
        int cx = (int) Math.round(size / 2.0 - catSize / 2.0);
        int cy = (int) Math.round(size * 0.86 - catSize * 0.906); // feet (sprite row 29) at 86% height
        g.setColor(new Color(0, 0, 0, 60));
        g.fillOval(cx + catSize / 4, cy + (int) (catSize * 0.9), catSize / 2, Math.max(1, catSize / 14));
        g.drawImage(cat, cx, cy, catSize, catSize, null);

        // a yellow music note
        BufferedImage note = CatSprites.note(0xFFFFD166);
        int ns = (int) Math.round(w * 0.15);
        g.drawImage(note, (int) (m + w * 0.68), (int) (m + w * 0.13), ns, (int) (ns * 7 / 6.0), null);
        g.dispose();
        return img;
    }
}
