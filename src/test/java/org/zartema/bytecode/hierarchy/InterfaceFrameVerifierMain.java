package org.zartema.bytecode.hierarchy;

/** Child-process entry point for the shared-interface verifier regression. */
public final class InterfaceFrameVerifierMain {
    private InterfaceFrameVerifierMain() { }

    public static void main(String[] args) throws Exception {
        Class.forName("sample.Factory").getMethod("call", boolean.class).invoke(null, true);
    }
}
