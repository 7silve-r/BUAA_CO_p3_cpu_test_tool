/** Shared numeric/register notation only; contains no instruction encoding or execution rules. */
public final class TestData {
    public record Anchor(boolean memory, int address, int value) {}
    private static final String[] NAMES = {"zero","at","v0","v1","a0","a1","a2","a3",
        "t0","t1","t2","t3","t4","t5","t6","t7","s0","s1","s2","s3","s4","s5","s6","s7",
        "t8","t9","k0","k1","gp","sp","fp","ra"};
    public static int integer(String text) {
        String s=text.trim().toLowerCase(java.util.Locale.ROOT);boolean negative=s.startsWith("-");
        if(negative||s.startsWith("+"))s=s.substring(1);
        long value=s.startsWith("0x")?Long.parseLong(s.substring(2),16):Long.parseLong(s,10);
        if(negative)value=-value;
        if(value<Integer.MIN_VALUE||value>0xffffffffL)throw new IllegalArgumentException("32-bit value out of range: "+text);
        return (int)value;
    }
    public static int register(String token) {
        String name=token.replace("$","").toLowerCase(java.util.Locale.ROOT);
        if(name.equals("s8"))return 30;
        for(int i=0;i<NAMES.length;i++)if(name.equals(NAMES[i]))return i;
        int value=Integer.parseInt(name);
        if(value<0||value>31)throw new IllegalArgumentException("Register out of range: "+token);
        return value;
    }
}
