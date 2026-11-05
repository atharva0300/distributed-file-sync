package com.filesync.chunking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the chunking algorithm to {@code testdata/cdc-golden.json}, which the frontend's
 * TypeScript implementation is tested against too. If this test and the TypeScript test both
 * pass, client and server compute identical boundaries and hashes. Regenerate the fixture only
 * for a deliberate algorithm change: {@code -Dcdc.golden.write=true}.
 */
class CdcGoldenVectorTest {

    private static final Path FIXTURE = Path.of("..", "testdata", "cdc-golden.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** xorshift32 byte stream: trivially portable to any language. */
    static byte[] input(String kind, int length) {
        byte[] out = new byte[length];
        if ("zeros".equals(kind)) return out;
        int x = 123456789;
        for (int i = 0; i < length; i++) {
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            out[i] = (byte) x;
        }
        return out;
    }

    private static final int[][] CASES = {
            // min, target, max, length
            {64, 256, 1024, 20000},
            {256, 512, 1024, 50000},
            {1, 16, 64, 5000},
            {2048, 8192, 32768, 300000},
            {64, 256, 1024, 5000},   // zeros: only forced boundaries
    };

    private static String kindOf(int caseIndex) {
        return caseIndex == CASES.length - 1 ? "zeros" : "xorshift32";
    }

    private static ArrayNode chunksOf(int[] c, String kind) throws Exception {
        byte[] data = input(kind, c[3]);
        List<ChunkBoundary> bs = new ContentDefinedChunkingStrategy(c[0], c[1], c[2])
                .chunk(new ByteArrayInputStream(data), data.length);
        ArrayNode arr = MAPPER.createArrayNode();
        for (ChunkBoundary b : bs) {
            arr.add(MAPPER.createObjectNode().put("offset", b.offset()).put("size", b.size()).put("sha256", b.sha256Hash()));
        }
        return arr;
    }

    @Test
    void matchesSharedFixture() throws Exception {
        if (Boolean.getBoolean("cdc.golden.write")) {
            ArrayNode cases = MAPPER.createArrayNode();
            for (int i = 0; i < CASES.length; i++) {
                int[] c = CASES[i];
                String kind = kindOf(i);
                ObjectNode n = MAPPER.createObjectNode();
                n.put("input", kind).put("length", c[3]).put("minSize", c[0]).put("targetSize", c[1]).put("maxSize", c[2]);
                n.set("chunks", chunksOf(c, kind));
                cases.add(n);
            }
            Files.writeString(FIXTURE, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                    MAPPER.createObjectNode().set("cases", cases)) + "\n");
        }
        JsonNode root = MAPPER.readTree(Files.readString(FIXTURE));
        JsonNode cases = root.get("cases");
        assertEquals(CASES.length, cases.size());
        for (int i = 0; i < CASES.length; i++) {
            JsonNode n = cases.get(i);
            int[] c = {n.get("minSize").asInt(), n.get("targetSize").asInt(), n.get("maxSize").asInt(), n.get("length").asInt()};
            assertEquals(n.get("chunks").toString(), chunksOf(c, n.get("input").asText()).toString(), "case " + i);
        }
    }
}
