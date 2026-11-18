import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 从用户提供的图标 PNG 生成 Android 启动图标全套位图:
 *  - legacy:ic_launcher.png / ic_launcher_round.png(mdpi~xxxhdpi,48/72/96/144/192)
 *  - adaptive 前景:ic_launcher_foreground.png(108/162/216/324/432,构图缩进中心 66/108 安全区)
 *  - adaptive 背景:ic_launcher_background.png(取源图标顶部/底部均色的垂直渐变)
 *
 * 用法:java GenIcons <源PNG路径> <apk目录>
 */
public class GenIcons {

    static final int[][] DENSITIES = {
            {48, 1}, {72, 2}, {96, 3}, {144, 4}, {192, 5}
    };
    static final String[] DPI_DIR = {
            "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"
    };

    public static void main(String[] args) throws IOException {
        String srcPath = args[0];
        String apkDir = args[1];
        BufferedImage src = ImageIO.read(new File(srcPath));
        if (src == null) throw new IOException("无法读取源图:" + srcPath);

        // 先按不透明像素裁出内容包围盒(源图四周常有透明边距)
        int[] box = opaqueBounds(src);
        BufferedImage square = src.getSubimage(box[0], box[1], box[2], box[3]);
        // 再补成正方形(内容居中),避免缩放后构图变形
        square = centerSquare(square);

        int[] bg = gradientEnds(square);
        System.out.printf("背景渐变:top=%02X%02X%02X bottom=%02X%02X%02X%n",
                (bg[0] >> 16) & 0xFF, (bg[0] >> 8) & 0xFF, bg[0] & 0xFF,
                (bg[1] >> 16) & 0xFF, (bg[1] >> 8) & 0xFF, bg[1] & 0xFF);

        for (int i = 0; i < DPI_DIR.length; i++) {
            int size = DENSITIES[i][0];
            File dir = new File(apkDir, "res/" + DPI_DIR[i]);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 " + dir);

            // legacy 方形与圆形
            write(scale(square, size, size, false), new File(dir, "ic_launcher.png"));
            write(scale(square, size, size, true), new File(dir, "ic_launcher_round.png"));

            // adaptive 前景:108dp 画布,内容缩进中心 66/108 安全区
            int canvas = (int) Math.round(size * 108.0 / 48.0);   // 48dp 图标 → 108dp 画布
            int content = (int) Math.round(canvas * 66.0 / 108.0);
            BufferedImage fg = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = fg.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x = (canvas - content) / 2;
            g.drawImage(scale(square, content, content, false), x, x, null);
            g.dispose();
            write(fg, new File(dir, "ic_launcher_foreground.png"));

            // adaptive 背景:垂直渐变
            BufferedImage bgImg = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_RGB);
            Graphics2D gb = bgImg.createGraphics();
            for (int y = 0; y < canvas; y++) {
                float t = (float) y / (canvas - 1);
                gb.setColor(new java.awt.Color(lerp(bg[0], bg[1], t)));
                gb.drawLine(0, y, canvas, y);
            }
            gb.dispose();
            write(bgImg, new File(dir, "ic_launcher_background.png"));
            System.out.println("生成 " + DPI_DIR[i] + " (" + size + "px)");
        }
        System.out.println("图标生成完成");
    }

    /** 不透明像素(alpha>16)的包围盒。 */
    static int[] opaqueBounds(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((img.getRGB(x, y) >>> 24) > 16) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return new int[]{0, 0, w, h};
        return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    /** 内容居中放入正方形画布(透明填充)。 */
    static BufferedImage centerSquare(BufferedImage img) {
        int side = Math.max(img.getWidth(), img.getHeight());
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, (side - img.getWidth()) / 2, (side - img.getHeight()) / 2, null);
        g.dispose();
        return out;
    }

    /** 等比缩放;round=true 时裁成圆形(透明四角)。 */
    static BufferedImage scale(BufferedImage src, int w, int h, boolean round) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (round) {
            g.setClip(new Ellipse2D.Float(0, 0, w, h));
        }
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** 渐变两端:自顶/底向内找第一条不透明占比足够的行带,只统计 alpha>128 的像素;找不到用方案深蓝/亮蓝。 */
    static int[] gradientEnds(BufferedImage img) {
        int w = img.getWidth();
        int band = Math.max(1, img.getHeight() / 20);
        int[] top = bandColor(img, 0, band);
        int[] bottom = bandColor(img, img.getHeight() - band, img.getHeight());
        if (top == null || bottom == null) {
            System.out.println("源图边缘全透明,回退方案配色(#0B1F3A → #1E88FF)");
            return new int[]{0x0B1F3A, 0x1E88FF};
        }
        return new int[]{top[0] << 16 | top[1] << 8 | top[2], bottom[0] << 16 | bottom[1] << 8 | bottom[2]};
    }

    /** 统计 [y0,y1) 行带内 alpha>128 像素的均色;不透明像素过少返回 null。 */
    static int[] bandColor(BufferedImage img, int y0, int y1) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                if ((c >>> 24) > 128) {
                    r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
                    n++;
                }
            }
        }
        if (n < img.getWidth() * (y1 - y0) / 8) return null;   // 不足 1/8 视为透明带
        return new int[]{(int) (r / n), (int) (g / n), (int) (b / n)};
    }

    static int lerp(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int rr = Math.round(ar + (br - ar) * t);
        int gg = Math.round(ag + (bg - ag) * t);
        int bb2 = Math.round(ab + (bb - ab) * t);
        return (rr << 16) | (gg << 8) | bb2;
    }

    static void write(BufferedImage img, File f) throws IOException {
        ImageIO.write(img, "png", f);
    }
}
