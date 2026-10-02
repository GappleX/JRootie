package io.github.gapplex.jrootie;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

public class Rootie {
    private static final Audit log = Log.audit(Rootie.class);
    //Core
    private final Unsafe UNSAFE;
    private final MethodHandles.Lookup IMPL_LOOKUP;

    //Native methods
    private final MethodHandle GET_DECLARED_FIELDS_0;
    private final MethodHandle GET_DECLARED_METHODS_0;
    private final MethodHandle GET_DECLARED_CLASSES_0;
    private final MethodHandle GET_DECLARED_CONSTRUCTORS_0;

    private Rootie(Unsafe unsafe, MethodHandles.Lookup lookup
            , MethodHandle fields, MethodHandle methods
            , MethodHandle classes, MethodHandle constructors
            ){
        UNSAFE = unsafe;
        IMPL_LOOKUP = lookup;
        GET_DECLARED_FIELDS_0 = fields;
        GET_DECLARED_METHODS_0 = methods;
        GET_DECLARED_CLASSES_0 = classes;
        GET_DECLARED_CONSTRUCTORS_0 = constructors;
    }

    public static Rootie acquired(){
        try {
            log.acquired("normal");
            //Get Unsafe
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Unsafe unsafe = (Unsafe) theUnsafe.get(null);
            //Get IMPL_LOOKUP
            Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            MethodHandles.Lookup lookup = (MethodHandles.Lookup) unsafe.getObject(unsafe.staticFieldBase(implLookupField)
                    , unsafe.staticFieldOffset(implLookupField));
            //Get native methods
            MethodHandle getDeclaredFields0 = lookup.unreflect(
                    Class.class.getDeclaredMethod("getDeclaredFields0", boolean.class));
            MethodHandle getDeclaredMethods0 = lookup.unreflect(
                    Class.class.getDeclaredMethod("getDeclaredMethods0", boolean.class));
            MethodHandle getDeclaredClasses0 = lookup.unreflect(
                    Class.class.getDeclaredMethod("getDeclaredClasses0"));
            MethodHandle getDeclaredConstructors0 = lookup.unreflect(
                    Class.class.getDeclaredMethod("getDeclaredConstructors0", boolean.class));

            //Return
            return new Rootie(unsafe, lookup
                    , getDeclaredFields0, getDeclaredMethods0
                    , getDeclaredClasses0, getDeclaredConstructors0);
        } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException | SecurityException e){
            throw new AcquireFailedException("Acquire failed", e);
        }
    }
}
