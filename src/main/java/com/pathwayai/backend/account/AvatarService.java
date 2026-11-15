package com.pathwayai.backend.account;

import com.pathwayai.backend.account.StudentProfileDtos.AvatarInfo;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A student's profile picture. It is stored in this database (durable, backed up, no ephemeral disk, no extra service) as a small JPEG that
 * the SERVER produced itself:
 * <ul>
 *   <li>the file type is decided from the leading bytes, never from the file name or the declared content type (JPEG and PNG only);</li>
 *   <li>the upload is size-limited and the pixel count is read from the header BEFORE decoding (decompression-bomb guard);</li>
 *   <li>the picture is decoded, centre-cropped to a square, scaled to 256 x 256, flattened onto white and re-encoded: this removes metadata
 *       (EXIF, GPS), embedded profiles and any payload hidden in the file, and keeps every avatar small;</li>
 *   <li>only the owner's own row is ever read or written (the account id comes from the session).</li>
 * </ul>
 */
@Service
class AvatarService {

    static final int MAX_UPLOAD_BYTES = 400 * 1024;
    static final int MAX_PIXELS = 16_000_000;
    static final int MAX_SIDE = 6000;
    static final int SIZE = 256;

    private final JdbcClient jdbc;

    AvatarService(JdbcClient jdbc) {
        this.jdbc = jdbc;
        ImageIO.setUseCache(false);
    }

    @Transactional
    AvatarInfo replace(UUID id, byte[] upload) {
        byte[] jpeg = reencode(upload);
        jdbc.sql("""
                insert into student_avatar (account_id, data, version) values (:id, :d, 1)
                on conflict (account_id) do update set data = excluded.data, version = student_avatar.version + 1, updated_at = now()""")
                .param("id", id).param("d", jpeg).update();
        return info(id);
    }

    Optional<byte[]> read(UUID id) {
        return jdbc.sql("select data from student_avatar where account_id = :id").param("id", id).query((rs, n) -> rs.getBytes(1)).optional();
    }

    @Transactional
    void remove(UUID id) {
        jdbc.sql("delete from student_avatar where account_id = :id").param("id", id).update();
    }

    AvatarInfo info(UUID id) {
        return jdbc.sql("select version from student_avatar where account_id = :id").param("id", id).query(Integer.class).optional()
                .map(v -> new AvatarInfo(true, v)).orElse(new AvatarInfo(false, 0));
    }

    static byte[] reencode(byte[] upload) {
        if (upload == null || upload.length == 0) {
            throw bad(400, "AVATAR_EMPTY", "Choose a picture to upload.");
        }
        if (upload.length > MAX_UPLOAD_BYTES) {
            throw bad(413, "AVATAR_TOO_LARGE", "The picture is too large. Choose one under 400 KB (the page shrinks it for you).");
        }
        if (!isJpeg(upload) && !isPng(upload)) {
            throw bad(415, "AVATAR_TYPE", "Only JPEG and PNG pictures are accepted.");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(upload))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw bad(415, "AVATAR_TYPE", "This file is not a readable JPEG or PNG picture.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w < 32 || h < 32) {
                    throw bad(400, "AVATAR_SMALL", "The picture is too small (at least 32 x 32 pixels).");
                }
                if (w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
                    throw bad(413, "AVATAR_PIXELS", "The picture has too many pixels.");
                }
                BufferedImage source = reader.read(0);
                return encode(square(source));
            } finally {
                reader.dispose();
            }
        } catch (AccountException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw bad(415, "AVATAR_UNREADABLE", "This picture could not be read. Try another one.");
        }
    }

    private static BufferedImage square(BufferedImage source) {
        int side = Math.min(source.getWidth(), source.getHeight());
        BufferedImage cropped = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
        BufferedImage current = flatten(cropped, side);
        // halve repeatedly while far larger than the target: much better quality than one big step
        while (current.getWidth() / 2 >= SIZE) {
            current = scale(current, current.getWidth() / 2);
        }
        return current.getWidth() == SIZE ? current : scale(current, SIZE);
    }

    private static BufferedImage flatten(BufferedImage in, int side) {
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, side, side);
            g.drawImage(in, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage scale(BufferedImage in, int side) {
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(in, 0, 0, side, side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(image, null, null), param); // no metadata is written
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A
                && b[6] == 0x1A && b[7] == 0x0A;
    }

    private static AccountException bad(int status, String code, String message) {
        return new AccountException(status, code, message);
    }
}
