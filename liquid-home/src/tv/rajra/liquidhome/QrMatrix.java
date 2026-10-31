package tv.rajra.liquidhome;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small dependency-free QR encoder for short local upload URLs (QR version 5-L). */
final class QrMatrix {
    private static final int SIZE = 37;
    private static final int DATA_CODEWORDS = 108;
    private static final int ECC_CODEWORDS = 26;

    private QrMatrix() { }

    static boolean[][] encode(String value) {
        byte[] payload = value.getBytes(StandardCharsets.UTF_8);
        if (payload.length > 100) throw new IllegalArgumentException("QR URL is too long");
        byte[] data = makeData(payload);
        byte[] ecc = makeEcc(data);
        boolean[][] modules = new boolean[SIZE][SIZE];
        boolean[][] function = new boolean[SIZE][SIZE];
        drawFunctionPatterns(modules, function);

        byte[] all = new byte[data.length + ecc.length];
        System.arraycopy(data, 0, all, 0, data.length);
        System.arraycopy(ecc, 0, all, data.length, ecc.length);
        drawCodewords(modules, function, all);
        drawFormatBits(modules, function);
        return modules;
    }

    private static byte[] makeData(byte[] payload) {
        BitBuffer bits = new BitBuffer();
        bits.append(0x4, 4); // byte mode
        bits.append(payload.length, 8); // versions 1–9 use an 8-bit byte count
        for (byte value : payload) bits.append(value & 0xFF, 8);
        int capacity = DATA_CODEWORDS * 8;
        bits.append(0, Math.min(4, capacity - bits.length()));
        while ((bits.length() & 7) != 0) bits.append(0, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream(DATA_CODEWORDS);
        out.write(bits.toBytes(), 0, bits.toBytes().length);
        int pad = 0;
        while (out.size() < DATA_CODEWORDS) out.write((pad++ & 1) == 0 ? 0xEC : 0x11);
        return out.toByteArray();
    }

    private static byte[] makeEcc(byte[] data) {
        byte[] generator = new byte[] {1};
        int root = 1;
        for (int i = 0; i < ECC_CODEWORDS; i++) {
            byte[] next = new byte[generator.length + 1];
            for (int j = 0; j < generator.length; j++) {
                next[j] ^= generator[j];
                next[j + 1] ^= (byte) multiply(generator[j] & 0xFF, root);
            }
            generator = next;
            root = multiply(root, 2);
        }
        byte[] remainder = new byte[ECC_CODEWORDS];
        for (byte value : data) {
            int factor = (value & 0xFF) ^ (remainder[0] & 0xFF);
            System.arraycopy(remainder, 1, remainder, 0, remainder.length - 1);
            remainder[remainder.length - 1] = 0;
            for (int i = 0; i < remainder.length; i++) {
                remainder[i] ^= (byte) multiply(generator[i + 1] & 0xFF, factor);
            }
        }
        return remainder;
    }

    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ (((z >>> 7) & 1) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static void drawFunctionPatterns(boolean[][] modules, boolean[][] function) {
        for (int i = 0; i < SIZE; i++) {
            setFunction(modules, function, 6, i, (i & 1) == 0);
            setFunction(modules, function, i, 6, (i & 1) == 0);
        }
        drawFinder(modules, function, 3, 3);
        drawFinder(modules, function, SIZE - 4, 3);
        drawFinder(modules, function, 3, SIZE - 4);
        drawAlignment(modules, function, 30, 30);
        setFunction(modules, function, 8, SIZE - 8, true);
        reserveFormatAreas(modules, function);
    }

    private static void drawFinder(boolean[][] modules, boolean[][] function, int centerX, int centerY) {
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
            int x = centerX + dx, y = centerY + dy;
            if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) continue;
            int distance = Math.max(Math.abs(dx), Math.abs(dy));
            setFunction(modules, function, x, y, distance != 2 && distance != 4);
        }
    }

    private static void drawAlignment(boolean[][] modules, boolean[][] function, int centerX, int centerY) {
        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
            int distance = Math.max(Math.abs(dx), Math.abs(dy));
            setFunction(modules, function, centerX + dx, centerY + dy, distance != 1);
        }
    }

    private static void reserveFormatAreas(boolean[][] modules, boolean[][] function) {
        for (int i = 0; i <= 5; i++) setFunction(modules, function, 8, i, false);
        setFunction(modules, function, 8, 7, false);
        setFunction(modules, function, 8, 8, false);
        setFunction(modules, function, 7, 8, false);
        for (int i = 9; i < 15; i++) setFunction(modules, function, 14 - i, 8, false);
        for (int i = 0; i < 8; i++) setFunction(modules, function, SIZE - 1 - i, 8, false);
        for (int i = 8; i < 15; i++) setFunction(modules, function, 8, SIZE - 15 + i, false);
    }

    private static void drawFormatBits(boolean[][] modules, boolean[][] function) {
        int data = (1 << 3); // error correction level L, mask pattern 0
        int remainder = data;
        for (int i = 0; i < 10; i++) remainder = (remainder << 1) ^ (((remainder >>> 9) & 1) * 0x537);
        int bits = ((data << 10) | remainder) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFunction(modules, function, 8, i, bit(bits, i));
        setFunction(modules, function, 8, 7, bit(bits, 6));
        setFunction(modules, function, 8, 8, bit(bits, 7));
        setFunction(modules, function, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(modules, function, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFunction(modules, function, SIZE - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(modules, function, 8, SIZE - 15 + i, bit(bits, i));
        setFunction(modules, function, 8, SIZE - 8, true);
    }

    private static void drawCodewords(boolean[][] modules, boolean[][] function, byte[] data) {
        int bitIndex = 0;
        boolean upward = true;
        for (int right = SIZE - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < SIZE; vert++) {
                int y = upward ? SIZE - 1 - vert : vert;
                for (int offset = 0; offset < 2; offset++) {
                    int x = right - offset;
                    if (function[y][x]) continue;
                    boolean value = bitIndex < data.length * 8 && bit(data[bitIndex >>> 3] & 0xFF, 7 - (bitIndex & 7));
                    modules[y][x] = value ^ (((x + y) & 1) == 0); // mask 0
                    bitIndex++;
                }
            }
            upward = !upward;
        }
        if (bitIndex < data.length * 8) throw new IllegalStateException("QR matrix did not fit its codewords");
    }

    private static boolean bit(int value, int index) { return ((value >>> index) & 1) != 0; }

    private static void setFunction(boolean[][] modules, boolean[][] function, int x, int y, boolean value) {
        modules[y][x] = value;
        function[y][x] = true;
    }

    private static final class BitBuffer {
        private final List<Integer> values = new ArrayList<>();
        int length() { return values.size(); }
        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) values.add((value >>> i) & 1);
        }
        byte[] toBytes() {
            byte[] result = new byte[(values.size() + 7) / 8];
            for (int i = 0; i < values.size(); i++) result[i >>> 3] |= values.get(i) << (7 - (i & 7));
            return result;
        }
    }
}
