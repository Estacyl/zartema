package org.zartema.bytecode.hierarchy;

/** Child-process entry point used to force verification with {@code -Xverify:all}. */
public final class ArrayFrameVerifierMain {
    private ArrayFrameVerifierMain() { }

    public static void main(String[] args) throws Exception {
        Class<?> factory = Class.forName("sample.Factory");
        Object value = factory.getMethod("chooseElement", boolean.class).invoke(null, true);
        if (value != null) throw new AssertionError("newly allocated array element should be null");
    }
}
