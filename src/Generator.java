import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class Generator {
    private static void constant(List<String> out, int reg, int value) {
        out.add(String.format("lui $%d, 0x%04x", reg, value >>> 16));
        out.add(String.format("ori $%d, $%d, 0x%04x", reg, reg, value & 65535));
    }
    public static Path generate(Path target, long seed, int count, int memoryBytes) throws IOException {
        if (count < 1 || count > 900 || memoryBytes < 4 || memoryBytes > 32764 || memoryBytes % 4 != 0)
            throw new IllegalArgumentException("Generator requires 1..900 operations, DM size 4..32764 bytes aligned to 4");
        List<String> out = new ArrayList<>();
        out.add("# Deterministic base-ISA mixed program; seed=" + seed);
        Random random = new Random(seed);
        int[] edges = {0,1,-1,2,-2,Integer.MIN_VALUE,Integer.MAX_VALUE,0x80000001,0x12345678,0xa5c35a96};
        for (int i = 1; i <= 27; i++) constant(out, i, i <= edges.length ? edges[i - 1] : random.nextInt());
        constant(out, 28, 0); constant(out, 29, memoryBytes); constant(out, 30, -4);
        for (int i = 0; i < count; i++) {
            int dest = random.nextInt(28), rs = random.nextInt(32), rt = random.nextInt(32);
            String operation;
            switch (random.nextInt(8)) {
                case 0: operation = String.format("add $%d, $%d, $%d", dest, rs, rt); break;
                case 1: operation = String.format("sub $%d, $%d, $%d", dest, rs, rt); break;
                case 2: operation = String.format("ori $%d, $%d, 0x%04x", dest, rs, random.nextInt(65536)); break;
                case 3: operation = String.format("lui $%d, 0x%04x", dest, random.nextInt(65536)); break;
                case 4: case 5:
                    int base = 28 + random.nextInt(3), baseValue = base == 28 ? 0 : base == 29 ? memoryBytes : -4;
                    int address = i % 13 == 0 ? memoryBytes - 4 : i % 11 == 0 ? 0 : random.nextInt(memoryBytes / 4) * 4;
                    operation = String.format("%s $%d, %d($%d)", i % 2 == 0 ? "lw" : "sw", dest, address - baseValue, base); break;
                case 6:
                    int branchTarget = Math.min(count, i + 1 + random.nextInt(3));
                    operation = String.format("beq $%d, $%d, random_%d", rs, i % 2 == 0 ? rs : rt, branchTarget); break;
                default: operation = "nop";
            }
            out.add("random_" + i + ": " + operation);
        }
        out.add("random_" + count + ":");
        Files.write(target, out);
        return target;
    }
}
