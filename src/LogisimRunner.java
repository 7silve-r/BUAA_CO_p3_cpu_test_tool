import java.io.*;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.prefs.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import com.cburch.hex.HexModel;
import com.cburch.logisim.circuit.*;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.*;
import com.cburch.logisim.file.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.memory.Rom;
import com.cburch.logisim.std.wiring.Pin;

/** The only source file which depends on Logisim's internal API (version 2.7.1). */
public final class LogisimRunner {
    public static final String[] OUTPUTS = {"Instr","pc","RegWrite","RegAddr","RegData","MemWrite","MemAddr","MemData"};
    private static final int[] WIDTHS = {32,32,1,5,32,1,32,32};
    public static final class Mismatch extends RuntimeException {
        public Mismatch(String message) { super(message); }
    }
    public static final class Config {
        final Properties p = new Properties();
        public Config(Path file) throws IOException { try (Reader in = Files.newBufferedReader(file)) { p.load(in); } }
        public String get(String key, String fallback) { return p.getProperty(key, fallback).trim(); }
        public int number(String key, int fallback) { return TestData.integer(get(key, Integer.toString(fallback))); }
        public boolean flag(String key, boolean fallback) {
            String s = get(key, Boolean.toString(fallback));
            if (!s.equals("true") && !s.equals("false")) throw new IllegalArgumentException("Expected true/false: " + key);
            return Boolean.parseBoolean(s);
        }
        public String output(String role) { return get("pin." + role, role); }
    }
    public static void prepareFixture(Path source, Path target, Config config) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder().parse(source.toFile());
        Element main = null;
        NodeList circuits = doc.getDocumentElement().getElementsByTagName("circuit");
        for (int i = 0; i < circuits.getLength(); i++) {
            Element c = (Element) circuits.item(i);
            if (c.getAttribute("name").equals(config.get("main", "main"))) main = c;
        }
        if (main == null) throw new IllegalArgumentException("Main circuit not found");
        for (Element lib : children(doc.getDocumentElement(), "lib")) {
            if (!lib.getAttribute("desc").startsWith("#"))
                throw new IllegalArgumentException("This version supports built-in libraries only: " + lib.getAttribute("desc"));
        }
        if (config.flag("appearance.verify", true)) checkAppearance(main, config);
        if (config.get("clock.mode", "builtin").equals("builtin")) {
            String selector = config.get("clock.selector", "Clock");
            List<Element> clocks = new ArrayList<>();
            for (Element component : children(main, "comp")) if (matches(component, selector)) clocks.add(component);
            if (clocks.size() != 1 || !clocks.get(0).getAttribute("name").equals("Clock"))
                throw new IllegalArgumentException("clock.selector must select exactly one built-in Clock");
            Element clock = clocks.get(0);
            String facing = attribute(clock, "facing", "east");
            clock.setAttribute("name", "Pin");
            for (Element a : children(clock, "a")) clock.removeChild(a);
            addAttribute(doc, clock, "facing", facing);
            addAttribute(doc, clock, "tristate", "false");
            addAttribute(doc, clock, "label", config.get("clock.pin", "__autotest_clock"));
        } else if (!config.get("clock.mode", "builtin").equals("pin")) {
            throw new IllegalArgumentException("clock.mode must be builtin or pin");
        }
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.transform(new DOMSource(doc), new StreamResult(target.toFile()));
    }
    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && e.getTagName().equals(tag)) result.add(e);
        return result;
    }
    private static String attribute(Element element, String name, String fallback) {
        for (Element a : children(element, "a")) if (a.getAttribute("name").equals(name)) return a.getAttribute("val");
        return fallback;
    }
    private static void addAttribute(Document doc, Element parent, String name, String value) {
        Element e = doc.createElement("a"); e.setAttribute("name", name); e.setAttribute("val", value); parent.appendChild(e);
    }
    private static boolean matches(Element e, String selector) {
        if (selector.startsWith("@")) return attribute(e, "label", "").equals(selector.substring(1));
        if (selector.startsWith("(")) return e.getAttribute("loc").equals(selector);
        return e.getAttribute("name").equals(selector);
    }
    private static void checkAppearance(Element main, Config config) {
        List<Element> appearances = children(main, "appear");
        if (appearances.size() != 1) throw new IllegalArgumentException("No explicit appearance; set appearance.verify=false if intentional");
        Map<String,String> pins = new HashMap<>();
        for (Element e : children(main, "comp")) {
            if (e.getAttribute("name").equals("Pin") && attribute(e, "output", "false").equals("true"))
                pins.put(e.getAttribute("loc").replace("(", "").replace(")", ""), attribute(e, "label", ""));
        }
        Map<String,Double> positions = new HashMap<>();
        for (Element port : children(appearances.get(0), "circ-port")) {
            String label = pins.get(port.getAttribute("pin"));
            if (label != null) positions.put(label, Double.parseDouble(port.getAttribute("y")) + Double.parseDouble(port.getAttribute("height")) / 2);
        }
        double previous = Double.NEGATIVE_INFINITY;
        for (String role : OUTPUTS) {
            Double y = positions.get(config.output(role));
            if (y == null || y <= previous) throw new IllegalArgumentException("Appearance output order/port missing at " + role + "; expected Instr,pc,RegWrite,RegAddr,RegData,MemWrite,MemAddr,MemData");
            previous = y;
        }
    }

    private final Config config;
    private final LogisimFile file;
    private final Project project;
    private final Circuit main;
    private final CircuitState state;
    private final Map<String,Component> pins = new LinkedHashMap<>();
    public LogisimRunner(Path fixture, Config config, int[] words, int base) throws Exception {
        this.config = config;
        file = LogisimFile.load(fixture.toFile(), new Loader(null));
        if (file == null) throw new IllegalArgumentException("Logisim could not load circuit");
        project = new Project(file); main = file.getCircuit(config.get("main", "main"));
        for (Circuit c : file.getCircuits()) if (c.getWidthIncompatibilityData() != null && !c.getWidthIncompatibilityData().isEmpty())
            throw new Mismatch("WIRING: width conflicts in " + c.getName() + ": " + c.getWidthIncompatibilityData());
        Circuit imCircuit = circuitAt(config.get("im.path", "IFU"));
        Component component = select(imCircuit, config.get("im.selector", "ROM"));
        if (!(component.getFactory() instanceof Rom)) throw new IllegalArgumentException("im.selector is not a ROM");
        HexModel rom = (HexModel) component.getAttributeSet().getValue(Rom.CONTENTS_ATTR);
        int capacity = config.number("im.words", 4096);
        if (rom.getLastOffset() + 1 < capacity || words.length > capacity)
            throw new IllegalArgumentException("Configured IM capacity disagrees with ROM/program");
        rom.fill(0, rom.getLastOffset() + 1, 0);
        for (int i = 0; i < words.length; i++) rom.set(i, words[i]);
        state = new CircuitState(project, main);
        for (Component x : main.getNonWires()) if (x.getFactory() instanceof Pin) {
            if (pins.putIfAbsent(label(x), x) != null) throw new IllegalArgumentException("Duplicate top-level pin label: " + label(x));
        }
        for (int i = 0; i < OUTPUTS.length; i++) {
            Component p = requirePin(config.output(OUTPUTS[i]), WIDTHS[i]);
            if (Pin.FACTORY.isInputPin(Instance.getInstanceFor(p))) throw new IllegalArgumentException("Expected output: " + OUTPUTS[i]);
        }
        String reset = config.get("reset.pin", "rst"), clock = config.get("clock.pin", "__autotest_clock");
        requireInput(reset); requireInput(clock);
        for (Component x : pins.values()) if (Pin.FACTORY.isInputPin(Instance.getInstanceFor(x))) {
            String name = label(x);
            if (name.equals(reset) || name.equals(clock)) continue;
            String value = config.get("input." + name, "");
            if (value.isEmpty()) throw new IllegalArgumentException("Configure additional input: input." + name);
            put(name, TestData.integer(value));
        }
        put(clock, 0); put(reset, config.number("reset.active", 1)); settle();
        requireEqual("reset pc", output("pc"), base);
        checkRegisters(new int[32]);
        checkMemoryWords(new int[config.number("dm.bytes", 0x3000) / 4]);
        put(reset, 1 - config.number("reset.active", 1)); settle();
    }
    private void requireInput(String name) {
        Component p = requirePin(name, 1);
        if (!Pin.FACTORY.isInputPin(Instance.getInstanceFor(p))) throw new IllegalArgumentException("Expected input: " + name);
    }
    private Component requirePin(String name, int width) {
        Component p = pins.get(name);
        if (p == null || p.getAttributeSet().getValue(StdAttr.WIDTH).getWidth() != width)
            throw new IllegalArgumentException("Missing pin or wrong width: " + name + " expected " + width);
        return p;
    }
    private static String label(Component x) {
        String value = x.getAttributeSet().getValue(StdAttr.LABEL); return value == null ? "" : value;
    }
    private static Component select(Circuit circuit, String selector) {
        List<Component> result = new ArrayList<>();
        for (Component x : circuit.getNonWires()) {
            boolean match = selector.startsWith("@") ? label(x).equals(selector.substring(1)) :
                selector.startsWith("(") ? x.getLocation().toString().replace(" ", "").equals(selector.replace(" ", "")) :
                x.getFactory().getName().equals(selector);
            if (match) result.add(x);
        }
        if (result.size() != 1) throw new IllegalArgumentException("Selector '" + selector + "' matched " + result.size() + " components in " + circuit.getName());
        return result.get(0);
    }
    private Circuit circuitAt(String path) {
        Circuit current = main;
        if (!path.isEmpty()) for (String segment : path.split("/")) {
            Component c = select(current, segment);
            if (!(c.getFactory() instanceof SubcircuitFactory sub)) throw new IllegalArgumentException("Not a subcircuit: " + segment);
            current = sub.getSubcircuit();
        }
        return current;
    }
    private CircuitState stateAt(String path) {
        CircuitState current = state;
        if (!path.isEmpty()) for (String segment : path.split("/")) {
            Component c = select(current.getCircuit(), segment);
            if (!(c.getFactory() instanceof SubcircuitFactory sub)) throw new IllegalArgumentException("Not a subcircuit: " + segment);
            current = sub.getSubstate(current, c);
        }
        return current;
    }
    private void put(String name, int value) {
        Component x = pins.get(name);
        Pin.FACTORY.setValue(state.getInstanceState(x), Value.createKnown(x.getAttributeSet().getValue(StdAttr.WIDTH), value));
        state.markComponentAsDirty(x);
    }
    private void settle() {
        state.getPropagator().propagate();
        if (state.getPropagator().isOscillating()) throw new Mismatch("OSCILLATION: circuit did not settle");
    }
    public int output(String role) {
        Value value = state.getValue(pins.get(config.output(role)).getLocation());
        if (!value.isFullyDefined()) throw new Mismatch("UNKNOWN: " + role + "=" + value);
        return value.toIntValue();
    }
    public int[] outputs() {
        int[] result = new int[OUTPUTS.length];
        for (int i = 0; i < result.length; i++) result[i] = output(OUTPUTS[i]);
        return result;
    }
    public void pulse() {
        String name = config.get("clock.pin", "__autotest_clock");
        put(name, 1); settle(); put(name, 0); settle();
    }
    public void checkRegisters(int[] expected) {
        if (!config.flag("snapshot.registers", true)) return;
        CircuitState grf = stateAt(config.get("grf.path", "GRF"));
        String pattern = config.get("grf.labelPattern", "%d");
        for (int i = 0; i < 32; i++) {
            Component reg = select(grf.getCircuit(), "@" + String.format(Locale.ROOT, pattern, i));
            if (!reg.getFactory().getName().equals("Register")) throw new IllegalArgumentException("GRF snapshot expects Registers");
            Value v = grf.getValue(reg.getLocation());
            if (!v.isFullyDefined() || v.getWidth() != 32) throw new Mismatch("UNKNOWN: R" + i + "=" + v);
            requireEqual("R" + i, v.toIntValue(), expected[i]);
        }
    }
    public void checkMemoryWords(int[] words) throws Exception {
        if (!config.flag("snapshot.memory", true)) return;
        HexModel contents = memoryContents();
        if (contents.getLastOffset() + 1 < words.length) throw new IllegalArgumentException("RAM capacity too small");
        for (int i = 0; i < words.length; i++)
            requireEqual("DM[" + String.format("%08X", i * 4) + "]", (int) contents.get(i), words[i]);
    }
    public int memoryWord(int byteAddress) throws Exception {
        if (byteAddress < 0 || byteAddress % 4 != 0 || byteAddress >= config.number("dm.bytes", 0x3000))
            throw new IllegalArgumentException("Memory snapshot address outside DM");
        return (int) memoryContents().get(byteAddress / 4);
    }
    private HexModel memoryContents() throws Exception {
        CircuitState dm = stateAt(config.get("dm.path", "DM"));
        Component ram = select(dm.getCircuit(), config.get("dm.selector", "RAM"));
        Object data = dm.getInstanceState(ram).getData();
        if (data == null) throw new IllegalArgumentException("RAM state unavailable");
        Method method = data.getClass().getMethod("getContents"); method.setAccessible(true);
        return (HexModel) method.invoke(data);
    }
    public void exportMemory(Path target) throws Exception {
        if (!config.flag("snapshot.memory", true)) return;
        HexModel contents = memoryContents();
        List<String> words = new ArrayList<>(); words.add("v2.0 raw");
        for (int i = 0; i < config.number("dm.bytes", 0x3000) / 4; i++) words.add(String.format("%08x", contents.get(i)));
        Files.write(target, words);
    }
    public static void requireEqual(String signal, int actual, int expected) {
        if (actual != expected) throw new Mismatch(String.format("%s expected=%08X actual=%08X", signal, expected, actual));
    }

    /** Avoid platform preferences/registry access by Logisim's GUI initialization. */
    public static final class MemoryPreferencesFactory implements PreferencesFactory {
        private final Preferences root = new MemoryPreferences(null, "");
        public Preferences userRoot() { return root; }
        public Preferences systemRoot() { return root; }
    }
    public static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String,String> values = new HashMap<>();
        MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
        protected void putSpi(String k, String v) { values.put(k, v); }
        protected String getSpi(String k) { return values.get(k); }
        protected void removeSpi(String k) { values.remove(k); }
        protected void removeNodeSpi() { values.clear(); }
        protected String[] keysSpi() { return values.keySet().toArray(new String[0]); }
        protected String[] childrenNamesSpi() { return new String[0]; }
        protected AbstractPreferences childSpi(String n) { return new MemoryPreferences(this, n); }
        protected void syncSpi() {}
        protected void flushSpi() {}
    }
}
