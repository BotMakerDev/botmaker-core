package com.botmaker.core.assist;

import com.botmaker.plugin.api.assist.AgentReply;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpImageTest {

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** A frame PNG cannot shrink: every pixel differs from its neighbours, as on a game's busy screen. */
    private static BufferedImage busy(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Random random = new Random(7);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) image.setRGB(x, y, 0xFF000000 | random.nextInt(0x1000000));
        }
        return image;
    }

    @Test
    void aLargeFrameGoesOutAsASmallerJpegOfTheSameSize() throws IOException {
        byte[] png = png(busy(400, 300, BufferedImage.TYPE_INT_RGB));
        McpImage sent = McpImage.of(png);
        assertEquals("image/jpeg", sent.mimeType());
        assertTrue(sent.bytes().length < png.length, sent.bytes().length + " vs " + png.length);
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(sent.bytes()));
        assertNotNull(read);
        assertEquals(400, read.getWidth());
        assertEquals(300, read.getHeight());
    }

    @Test
    void aRefusalThatShowsAnImageIsSentAsAResultSoTheImageIsSeen() throws IOException {
        AgentReply boxed = AgentReply.refused("That box holds 6 characters apart")
                .and(AgentReply.image(png(busy(40, 20, BufferedImage.TYPE_INT_RGB))));
        CallToolResult sent = McpTools.reply(boxed);
        assertFalse(sent.isError(), "Claude Code drops an error result's image");
        assertEquals("REFUSED: That box holds 6 characters apart",
                ((McpSchema.TextContent) sent.content().getFirst()).text());
        assertTrue(sent.content().get(1) instanceof McpSchema.ImageContent);

        CallToolResult plain = McpTools.reply(AgentReply.refused("No screen Home"));
        assertTrue(plain.isError());
        assertEquals("No screen Home", ((McpSchema.TextContent) plain.content().getFirst()).text());
    }

    @Test
    void aSmallPictureAndATransparentOneStayExactPngs() throws IOException {
        byte[] small = png(busy(40, 40, BufferedImage.TYPE_INT_RGB));
        assertEquals("image/png", McpImage.of(small).mimeType());
        assertArrayEquals(small, McpImage.of(small).bytes());

        byte[] transparent = png(busy(400, 300, BufferedImage.TYPE_INT_ARGB));
        assertEquals("image/png", McpImage.of(transparent).mimeType());
    }
}
