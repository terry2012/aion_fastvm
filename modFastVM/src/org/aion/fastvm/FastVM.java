package org.aion.fastvm;

import org.aion.base.db.IRepositoryCache;
import org.aion.base.util.NativeLoader;
import org.aion.vm.ExecutionContext;
import org.aion.vm.ExecutionResult;
import org.aion.vm.VirtualMachine;
import org.apache.commons.lang3.tuple.Pair;

/**
 * The FastVM implementation. It calls into the jit library via JNI.
 *
 * @author yulong
 */
public class FastVM implements VirtualMachine {

    public static int REVISION_FRONTIER = 0;
    public static int REVISION_HOMESTEAD = 1;
    public static int REVISION_TANGERINE_WHISTLE = 2;
    public static int REVISION_SPURIOUS_DRAGON = 3;
    public static int REVISION_BYZANTIUM = 4;
    public static int REVISION_AION = 5;
    public static int REVISION_AION_V1 = 7;

    public static int FLAG_STATIC = 1;

    static {
        NativeLoader.loadLibrary("fastvm");
        init();
    }

    /** Creates a FastVM instance. */
    public FastVM() {}

    /** Initializes library. One time */
    private static native void init();

    /**
     * Creates a new VM instance.
     *
     * @return instance
     */
    private static native long create();

    /** Executes the given code and returns the execution results. */
    private static native byte[] run(long instance, byte[] code, byte[] context, int revision);

    /** Destroys the given VM instance. */
    private static native void destroy(long instance);

    @SuppressWarnings("unchecked")
    public ExecutionResult run(byte[] code, ExecutionContext ctx, IRepositoryCache repo) {
        Callback.push(Pair.of(ctx, repo));
        long instance = create();
        byte[] result = run(instance, code, ctx.toBytes(), REVISION_AION);
        destroy(instance);
        Callback.pop();

        return ExecutionResult.parse(result);
    }

    @SuppressWarnings("unchecked")
    public ExecutionResult run_v1(byte[] code, ExecutionContext ctx, IRepositoryCache repo) {
        Callback.push(Pair.of(ctx, repo));
        long instance = create();
        byte[] result = run(instance, code, ctx.toBytes(), REVISION_AION_V1);
        destroy(instance);
        Callback.pop();

        return ExecutionResult.parse(result);
    }
}
