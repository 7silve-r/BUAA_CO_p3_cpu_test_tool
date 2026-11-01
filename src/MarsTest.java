import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** MARS is the assembler/oracle; this process only drives and observes the real circuit. */
public final class MarsTest {
    private record Step(int pc, int word, int nextPc, int rd, int data, int address, int memoryWord, int line) {}
    private record Source(int steps, List<TestData.Anchor> anchors) {}
    private static String hash(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
    private static int hex(String value) { return (int) Long.parseLong(value, 16); }
    private static int[] words(Path file, boolean raw) throws IOException {
        List<Integer> result = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String s = line.trim(); if (s.isEmpty() || raw && s.equals("v2.0 raw")) continue;
            if (!s.matches("[0-9a-fA-F]{8}")) throw new IllegalArgumentException("Invalid MARS output word in " + file);
            result.add(hex(s));
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }
    private static Source prepare(Path input, Path source) throws IOException {
        List<String> cleaned = new ArrayList<>(); List<TestData.Anchor> anchors = new ArrayList<>(); int steps = 0;
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            line = line.replace("\uFEFF", ""); String text = line.split("#", 2)[0].trim();
            String[] t = text.replace(',', ' ').split("\\s+");
            if (t[0].equals(".steps")) {
                if (t.length != 2 || steps != 0) throw new IllegalArgumentException("Use one .steps N directive");
                steps = TestData.integer(t[1]); if (steps <= 0) throw new IllegalArgumentException(".steps must be positive");
                cleaned.add("");
            } else if (t[0].equals(".expect")) {
                if (t.length != 4 || !(t[1].equals("reg") || t[1].equals("mem"))) throw new IllegalArgumentException("Invalid .expect directive");
                boolean mem = t[1].equals("mem");
                anchors.add(new TestData.Anchor(mem, mem ? TestData.integer(t[2]) : TestData.register(t[2]), TestData.integer(t[3])));
                cleaned.add("");
            } else cleaned.add(line);
        }
        Files.write(source, cleaned, StandardCharsets.UTF_8);
        return new Source(steps, anchors);
    }
    private static List<Step> trace(Path file) throws IOException {
        List<Step> result = new ArrayList<>(); List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty() || !lines.get(0).equals("pc\tword\tnext_pc\treg_address\treg_data\tmem_address\tmem_word\tsource_line"))
            throw new IllegalArgumentException("Invalid MARS trace header");
        for (int i = 1; i < lines.size(); i++) {
            String[] v = lines.get(i).split("\t", -1);
            if (v.length != 8) throw new IllegalArgumentException("Truncated MARS trace");
            Step step = new Step(hex(v[0]),hex(v[1]),hex(v[2]),Integer.parseInt(v[3]),hex(v[4]),Integer.parseInt(v[5]),hex(v[6]),Integer.parseInt(v[7]));
            if (!result.isEmpty() && result.get(result.size()-1).nextPc != step.pc) throw new IllegalArgumentException("Discontinuous MARS trace");
            result.add(step);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("MARS did not execute any instructions");
        return result;
    }
    private static void reference(Path source, Path out, Path jar, Path extensionList, List<Path> extensions,
                                  LogisimRunner.Config config, int maxSteps, int steps, int timeoutMs) throws Exception {
        LinkedHashSet<String> classpath = new LinkedHashSet<>();
        // Extension directories first: Windows paths are case insensitive (addm vs legacy Addm).
        for (Path extension : extensions) classpath.add(extension.getParent().toString());
        classpath.add(System.getProperty("java.class.path")); classpath.add(jar.toString());
        List<String> cmd = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"),"bin","java.exe").toString(), "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
            "-Djava.util.prefs.PreferencesFactory=LogisimRunner$MemoryPreferencesFactory", "-classpath", String.join(File.pathSeparator,classpath),
            "MarsOracle",source.toString(),out.toString(),Integer.toString(config.number("pc.base",0x3000)),
            Integer.toString(config.number("im.words",4096)),Integer.toString(config.number("dm.bytes",0x3000)),
            Integer.toString(maxSteps),Integer.toString(steps),extensionList.toString()));
        Files.writeString(out.resolve("reference-command.txt"), String.join("\n",cmd));
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.resolve("reference.log").toFile()).start();
        if (!process.waitFor(timeoutMs,TimeUnit.MILLISECONDS)) {
            process.destroyForcibly(); process.waitFor(); throw new IllegalArgumentException("MARS reference process timed out");
        }
        if (process.exitValue()!=0 || !Files.isRegularFile(out.resolve("reference-complete.txt")))
            throw new IllegalArgumentException("MARS reference failed; see reference.log: " + Files.readString(out.resolve("reference.log")).strip());
    }
    private static void anchors(Source source, int[] regs, int[] memory) {
        for (TestData.Anchor e : source.anchors) {
            int actual;
            if (e.memory()) {
                if (e.address()<0 || e.address()%4!=0 || e.address()/4>=memory.length) throw new IllegalArgumentException("Anchor memory address outside DM");
                actual=memory[e.address()/4];
            } else actual=regs[e.address()];
            if(actual!=e.value()) throw new IllegalArgumentException(String.format("MARS anchor mismatch: %s %d expected=%08X actual=%08X",e.memory()?"DM":"R",e.address(),e.value(),actual));
        }
    }
    private static String test(Path input, Path out, Path fixture, Path jar, Path extensionList, List<Path> extensions,
                               LogisimRunner.Config config,int maxSteps,int timeoutMs,PrintWriter summary) throws Exception {
        Files.createDirectories(out); Files.copy(input,out.resolve("source.asm"));
        String stage="MARS reference",status="PASS",detail=""; int completed=0; LogisimRunner runner=null;
        try(PrintWriter actualTrace=new PrintWriter(Files.newBufferedWriter(out.resolve("actual-trace.tsv")))) {
            Source source=prepare(out.resolve("source.asm"),out.resolve("mars-source.asm"));
            reference(out.resolve("mars-source.asm"),out,jar,extensionList,extensions,config,maxSteps,source.steps,timeoutMs);
            List<Step> steps=trace(out.resolve("expected-trace.tsv"));
            int[] image=words(out.resolve("program.hex"),true),memory=words(out.resolve("expected-memory.hex"),true),regs=words(out.resolve("expected-registers.hex"),false);
            if(memory.length!=config.number("dm.bytes",0x3000)/4 || regs.length!=32) throw new IllegalArgumentException("Invalid MARS state dimensions");
            Properties metadata=new Properties();try(Reader reader=Files.newBufferedReader(out.resolve("reference-complete.txt"))){metadata.load(reader);}
            if(Integer.parseInt(metadata.getProperty("steps"))!=steps.size()) throw new IllegalArgumentException("MARS trace length mismatch");
            if(hex(metadata.getProperty("nextPC"))!=steps.get(steps.size()-1).nextPc)throw new IllegalArgumentException("MARS final PC mismatch");
            anchors(source,regs,memory);
            stage="load/reset"; runner=new LogisimRunner(fixture,config,image,config.number("pc.base",0x3000));
            int[] observedExpectedRegs=new int[32]; long deadline=System.nanoTime()+timeoutMs*1_000_000L;
            actualTrace.println("step\tpc\tword\tRegWrite\tRegAddr\tRegData\tMemWrite\tMemAddr\tMemData");
            for(Step step:steps) {
                stage=String.format("step=%d PC=%08X instruction=%08X source_line=%d",completed,step.pc,step.word,step.line);
                if(System.nanoTime()>deadline) throw new CircuitTimeout("Circuit execution exceeded "+timeoutMs+" ms");
                int[] v=runner.outputs();
                actualTrace.printf("%d\t%08X\t%08X\t%d\t%d\t%08X\t%d\t%08X\t%08X%n",completed,v[1],v[0],v[2],v[3],v[4],v[5],v[6],v[7]);
                LogisimRunner.requireEqual("PC",v[1],step.pc);LogisimRunner.requireEqual("Instr",v[0],step.word);
                LogisimRunner.requireEqual("effective RegWrite",v[2]!=0&&v[3]!=0?1:0,step.rd>=0?1:0);
                if(step.rd>=0) {
                    LogisimRunner.requireEqual("RegAddr",v[3],step.rd);LogisimRunner.requireEqual("RegData",v[4],step.data);
                    observedExpectedRegs[step.rd]=step.data;
                }
                LogisimRunner.requireEqual("MemWrite",v[5],step.address>=0?1:0);
                if(step.address>=0) {
                    if(v[6]<0 || v[6]>=config.number("dm.bytes",0x3000)) throw new LogisimRunner.Mismatch("MemAddr outside DM");
                    LogisimRunner.requireEqual("MemAddr aligned word",v[6]&~3,step.address);
                }
                runner.pulse();
                LogisimRunner.requireEqual("next PC",runner.output("pc"),step.nextPc);
                runner.checkRegisters(observedExpectedRegs);
                if(step.address>=0) LogisimRunner.requireEqual("committed memory word",runner.memoryWord(step.address),step.memoryWord);
                completed++;
            }
            stage="final state";runner.checkRegisters(regs);runner.checkMemoryWords(memory);
        }catch(LogisimRunner.Mismatch e){status="FAIL";detail=stage+"\n"+e.getMessage();}
        catch(CircuitTimeout e){status="TIMEOUT";detail=stage+"\n"+e.getMessage();}
        catch(Exception e){status="ERROR";detail=stage+"\n"+e.getClass().getSimpleName()+": "+e.getMessage();}
        if(runner!=null)try{runner.exportMemory(out.resolve("observed-memory.hex"));}catch(Exception e){if(status.equals("PASS"))status="ERROR";detail+="\nMemory export: "+e.getMessage();}
        Files.writeString(out.resolve("result.txt"),status+"\n"+detail+"\n");
        System.out.printf("%-7s %-36s cycles=%d%n",status,input.getFileName(),completed);
        if(!detail.isEmpty())System.out.println("        "+detail.replace("\n","\n        "));
        summary.printf("%s\t%s\t%d\t%s%n",input.getFileName(),status,completed,detail.replace('\n',' ').replace('\t',' '));summary.flush();
        return status;
    }
    private static final class CircuitTimeout extends RuntimeException { CircuitTimeout(String message){super(message);} }
    public static void main(String[] args) {
        try{System.exit(run(args));}catch(Exception e){System.out.println("TOOL ERROR: "+e);e.printStackTrace(System.out);System.exit(2);}
    }
    static int run(String[] args) throws Exception {
        System.setProperty("java.util.prefs.PreferencesFactory","LogisimRunner$MemoryPreferencesFactory");
        Map<String,String> options=new LinkedHashMap<>();
        Set<String> keys=Set.of("circuit","adapter","tests","out","mars-jar","extension-list","seed","random-count","max-steps","timeout-ms");
        for(int i=0;i<args.length;i+=2)if(i+1>=args.length||!args[i].startsWith("--")||!keys.contains(args[i].substring(2))||options.putIfAbsent(args[i].substring(2),args[i+1])!=null)
            throw new IllegalArgumentException("Invalid option: "+args[i]);
        for(String key:List.of("circuit","adapter","tests","out","mars-jar","extension-list"))if(!options.containsKey(key))throw new IllegalArgumentException("Missing --"+key);
        Path circuit=Path.of(options.get("circuit")),out=Path.of(options.get("out")),jar=Path.of(options.get("mars-jar")),extensionList=Path.of(options.get("extension-list"));
        Files.createDirectories(out);String sourceHash=hash(circuit);
        LogisimRunner.Config config=new LogisimRunner.Config(Path.of(options.get("adapter")));
        if(config.number("pc.base",0x3000)!=0x3000||config.number("dm.bytes",0x3000)!=0x3000)throw new IllegalArgumentException("MARS adapter currently requires PC=0x3000, DM=0..0x2fff");
        if(!config.flag("snapshot.memory",true))throw new IllegalArgumentException("MARS mode requires actual RAM snapshots (snapshot.memory=true)");
        int maxSteps=Integer.parseInt(options.getOrDefault("max-steps","10000")),timeout=Integer.parseInt(options.getOrDefault("timeout-ms","15000"));
        int randomCount=Integer.parseInt(options.getOrDefault("random-count","256"));long seed=Long.parseLong(options.getOrDefault("seed","20261101"));
        if(maxSteps<1||maxSteps>1_000_000||timeout<1||randomCount<0)throw new IllegalArgumentException("Invalid execution limits");
        List<Path> extensions=new ArrayList<>();Map<String,Path> names=new HashMap<>();List<String> manifest=new ArrayList<>();
        for(String line:Files.readAllLines(extensionList)) {
            String text=line.replace("\uFEFF","").trim();if(text.isEmpty())continue;Path p=Path.of(text).toAbsolutePath().normalize();
            if(!Files.isRegularFile(p)||!p.toString().toLowerCase(Locale.ROOT).endsWith(".class"))throw new IllegalArgumentException("Missing extension .class: "+p);
            if(names.putIfAbsent(p.getFileName().toString().toLowerCase(Locale.ROOT),p)!=null)throw new IllegalArgumentException("Duplicate extension class name");
            extensions.add(p);manifest.add(p+"\t"+hash(p));
        }
        Files.write(out.resolve("extensions.tsv"),manifest);Files.copy(Path.of(options.get("adapter")),out.resolve("adapter.properties"));
        Files.writeString(out.resolve("environment.txt"),"Engine=MARS\nCircuit="+circuit+"\nCircuitSHA256="+sourceHash+"\nMARS="+jar+"\nMarsSHA256="+hash(jar)+"\nOptions="+options+"\n");
        Path fixture=out.resolve("clock-fixture.circ");LogisimRunner.prepareFixture(circuit,fixture,config);
        List<Path> inputs=new ArrayList<>();Path tests=Path.of(options.get("tests"));
        if(Files.isDirectory(tests)){try(var files=Files.list(tests)){files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".asm")).sorted().forEach(inputs::add);}}
        else if(tests.toString().endsWith(".asm"))inputs.add(tests);else throw new IllegalArgumentException("MARS mode expects .asm programs");
        if(inputs.isEmpty())throw new IllegalArgumentException("No tests");
        if(randomCount>0)inputs.add(Generator.generate(out.resolve("generated-seed-"+seed+".asm"),seed,randomCount,config.number("dm.bytes",0x3000)));
        int passed=0,failed=0,errors=0;
        try(PrintWriter summary=new PrintWriter(Files.newBufferedWriter(out.resolve("summary.tsv")))) {
            summary.println("test\tstatus\tcompleted_cycles\tdetail");
            for(int i=0;i<inputs.size();i++) {
                Path p=inputs.get(i);Path caseOut=out.resolve(String.format("%03d-",i+1)+p.getFileName().toString().replaceAll("[^A-Za-z0-9_.-]","_"));
                String status=test(p,caseOut,fixture,jar,extensionList,extensions,config,maxSteps,timeout,summary);
                if(status.equals("PASS"))passed++;else if(status.equals("ERROR"))errors++;else failed++;
            }
        }finally{if(!hash(circuit).equals(sourceHash))throw new IllegalStateException("Source circuit changed; discard results");}
        String totals=String.format("RESULT engine=MARS passed=%d failed=%d errors=%d%n",passed,failed,errors);
        Files.writeString(out.resolve("summary.txt"),totals+"Source circuit hash unchanged.\n");System.out.print(totals);System.out.println("Output: "+out);
        return errors>0?2:failed>0?1:0;
    }
}
