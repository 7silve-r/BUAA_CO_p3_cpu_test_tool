import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import mars.*;
import mars.mips.hardware.*;
import mars.mips.instructions.LoadInstruction;

/** Runs in a separate JVM. All assembly and instruction execution are performed by MARS. */
@SuppressWarnings("deprecation")
public final class MarsOracle {
    private static final Pattern REG = Pattern.compile("\\$\\s*(\\d+)\\s*<=\\s*([0-9a-fA-F]{8})");
    private static final Pattern MEM = Pattern.compile("\\*([0-9a-fA-F]{8})\\s*<=\\s*([0-9a-fA-F]{8})");
    private static int hex(String s) { return (int) Long.parseLong(s, 16); }
    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable error) {
            System.out.println("MARS REFERENCE ERROR: " + error);
            if (error instanceof ProcessingException p && p.errors() != null) System.out.println(p.errors().generateErrorAndWarningReport());
            error.printStackTrace(System.out); System.exit(2);
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void run(String[] args) throws Exception {
        Path source = Path.of(args[0]), out = Path.of(args[1]);
        int base = Integer.parseInt(args[2]), imWords = Integer.parseInt(args[3]), dmBytes = Integer.parseInt(args[4]);
        int maxSteps = Integer.parseInt(args[5]), boundedSteps = Integer.parseInt(args[6]);
        Globals.initialize(false);
        MemoryConfigurations.setCurrentConfiguration(MemoryConfigurations.getConfigurationByName("CompactLargeText"));
        if (Memory.textBaseAddress != base || Memory.dataSegmentBaseAddress != 0)
            throw new IllegalArgumentException("MARS memory layout disagrees with adapter");
        Globals.memory.setByteOrder(Memory.LITTLE_ENDIAN);
        Globals.getSettings().setDelayedBranchingEnabledNonPersistent(false);
        Globals.getSettings().setIgnoreArithmeticOverflow(true);
        Globals.getSettings().setExceptionForCourse(false);
        Globals.getSettings().setOutputLoggingLevel(0);
        for (String line : Files.readAllLines(Path.of(args[7]))) {
            String path = line.replace("\uFEFF", "").trim();
            if (!path.isEmpty()) { LoadInstruction.loadClass(path); System.out.println("Loaded extension: " + path); }
        }
        MIPSprogram program = new MIPSprogram();
        ArrayList files = new ArrayList(); files.add(source.toString());
        ArrayList programs = program.prepareFilesForAssembly(files, source.toString(), null);
        program.assemble(programs, false, true); // no pseudoinstructions; warnings are errors
        TreeMap<Integer,ProgramStatement> statements = new TreeMap<>();
        for (Object o : program.getMachineList()) {
            ProgramStatement s = (ProgramStatement) o;
            long index = ((long) s.getAddress() - base) / 4;
            if ((s.getAddress() & 3) != 0 || s.getAddress() < base || index >= imWords)
                throw new IllegalArgumentException("Assembled instruction outside CPU IM: " + s.getAddress());
            if (statements.put(s.getAddress(), s) != null) throw new IllegalArgumentException("Overlapping text addresses");
        }
        if (statements.isEmpty()) throw new IllegalArgumentException("No machine instructions");
        int[] image = new int[(statements.lastKey() - base) / 4 + 1];
        List<String> listing = new ArrayList<>();
        for (ProgramStatement s : statements.values()) {
            image[(s.getAddress() - base) / 4] = s.getBinaryStatement();
            listing.add(String.format("%08X\t%08X\t%d\t%s", s.getAddress(), s.getBinaryStatement(), s.getSourceLine(), s.getPrintableBasicAssemblyStatement()));
        }
        writeRaw(out.resolve("program.hex"), image);
        Files.write(out.resolve("program.lst"), listing);
        // Align all architectural initial registers with the CPU without adding instructions.
        for (Register register : RegisterFile.getRegisters()) register.setValue(0);
        RegisterFile.initializeProgramCounter(base);
        // CPU reset initializes data memory to zero. .data initialization must not bypass that.
        for (int a = 0; a < dmBytes; a += 4) if (Globals.memory.getWordNoNotify(a) != 0)
            throw new IllegalArgumentException("Nonzero initial .data is unsupported; initialize memory using CPU store instructions");
        int limit = boundedSteps > 0 ? boundedSteps : maxSteps;
        if (limit < 1 || limit > maxSteps) throw new IllegalArgumentException("Invalid step limit");
        int completed = 0;
        try (PrintWriter trace = new PrintWriter(Files.newBufferedWriter(out.resolve("expected-trace.tsv")))) {
            trace.println("pc\tword\tnext_pc\treg_address\treg_data\tmem_address\tmem_word\tsource_line");
            while (completed < limit) {
                int pc = RegisterFile.getProgramCounter();
                ProgramStatement instruction = Globals.memory.getStatementNoNotify(pc);
                if (instruction == null) break;
                if (!statements.containsKey(pc)) throw new IllegalArgumentException("Execution outside assembled CPU program");
                Globals.displayRFchanging.clear(); Globals.displayDMchanging.clear();
                program.simulate(1);
                List<String> regs = new ArrayList<>(Globals.displayRFchanging), memory = new ArrayList<>(Globals.displayDMchanging);
                if (regs.size() > 1 || memory.size() > 1)
                    throw new IllegalArgumentException("This adapter supports at most one GRF write and one memory write per instruction");
                int rd = -1, data = 0, address = -1, word = 0;
                if (!regs.isEmpty()) {
                    Matcher m = REG.matcher(regs.get(0).trim());
                    if (!m.matches()) throw new IllegalArgumentException("Unsupported register trace: " + regs);
                    rd = Integer.parseInt(m.group(1)); data = hex(m.group(2));
                    if (rd < 1 || rd > 31) throw new IllegalArgumentException("Unsupported register destination");
                }
                if (!memory.isEmpty()) {
                    Matcher m = MEM.matcher(memory.get(0).trim());
                    if (!m.matches()) throw new IllegalArgumentException("Unsupported memory trace: " + memory);
                    address = hex(m.group(1)); word = hex(m.group(2));
                    if (address < 0 || address > dmBytes - 4 || address % 4 != 0)
                        throw new IllegalArgumentException("Reference store outside CPU DM");
                }
                trace.printf("%08X\t%08X\t%08X\t%d\t%08X\t%d\t%08X\t%d%n", pc, instruction.getBinaryStatement(),
                    RegisterFile.getProgramCounter(), rd, data, address, word, instruction.getSourceLine());
                completed++;
            }
        }
        if (boundedSteps == 0 && Globals.memory.getStatementNoNotify(RegisterFile.getProgramCounter()) != null)
            throw new IllegalArgumentException("Reference step limit reached; use an explicit bounded test for intentional loops");
        int[] memory = new int[dmBytes / 4], registers = new int[32];
        for (int i = 0; i < memory.length; i++) memory[i] = Globals.memory.getWordNoNotify(i * 4);
        for (int i = 0; i < 32; i++) registers[i] = RegisterFile.getValue(i);
        writeRaw(out.resolve("expected-memory.hex"), memory);
        List<String> regLines = new ArrayList<>();
        for (int i = 0; i < 32; i++) regLines.add(String.format("%08X", registers[i]));
        Files.write(out.resolve("expected-registers.hex"), regLines);
        Files.writeString(out.resolve("reference-complete.txt"), "steps=" + completed + "\nnextPC=" + String.format("%08X", RegisterFile.getProgramCounter()) + "\n");
        System.out.println("MARS reference complete: steps=" + completed);
    }
    private static void writeRaw(Path target, int[] values) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(target))) {
            writer.println("v2.0 raw"); for (int value : values) writer.printf("%08x%n", value);
        }
    }
}
