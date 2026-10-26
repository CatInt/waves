package io.waves.player

import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Minimal Objective-C runtime access through JNA. `objc_msgSend` is always called with a fixed argument list (never
 * as a C variadic), which is what arm64 requires; pass Long for NSInteger/NSUInteger, Double for CGFloat /
 * NSTimeInterval, and two Doubles for a CGSize.
 */
internal object ObjC {
    private val runtime = NativeLibrary.getInstance("objc")
    private val msgSend = runtime.getFunction("objc_msgSend")
    private val getClass = runtime.getFunction("objc_getClass")
    private val registerName = runtime.getFunction("sel_registerName")
    private val allocateClassPair = runtime.getFunction("objc_allocateClassPair")
    private val registerClassPair = runtime.getFunction("objc_registerClassPair")
    private val addMethod = runtime.getFunction("class_addMethod")
    private val poolPush = runtime.getFunction("objc_autoreleasePoolPush")
    private val poolPop = runtime.getFunction("objc_autoreleasePoolPop")

    fun cls(name: String): Pointer? = getClass.invokePointer(arrayOf(name))

    fun sel(name: String): Pointer = registerName.invokePointer(arrayOf(name))

    fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(receiver, sel(selector), *args))

    fun sendLong(receiver: Pointer?, selector: String, vararg args: Any?): Long =
        msgSend.invokeLong(arrayOf(receiver, sel(selector), *args))

    fun sendDouble(receiver: Pointer?, selector: String, vararg args: Any?): Double =
        msgSend.invokeDouble(arrayOf(receiver, sel(selector), *args))

    /** `[[cls alloc] init…]`: a +1 reference the caller must [release]. */
    fun new(className: String, init: String = "init", vararg args: Any?): Pointer? =
        send(send(cls(className), "alloc"), init, *args)

    fun release(obj: Pointer?) {
        if (obj != null) send(obj, "release")
    }

    fun string(value: String): Pointer? {
        val utf8 = value.encodeToByteArray()
        val memory = Memory(utf8.size + 1L).apply {
            write(0, utf8, 0, utf8.size)
            setByte(utf8.size.toLong(), 0)
        }
        return send(cls("NSString"), "stringWithUTF8String:", memory)
    }

    fun number(value: Double): Pointer? = send(cls("NSNumber"), "numberWithDouble:", value)

    fun number(value: Long): Pointer? = send(cls("NSNumber"), "numberWithLongLong:", value)

    fun data(bytes: ByteArray): Pointer? {
        if (bytes.isEmpty()) return send(cls("NSData"), "data")
        val memory = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        return send(cls("NSData"), "dataWithBytes:length:", memory, bytes.size.toLong())
    }

    /** UTF-8 contents of an NSString, or null for nil. */
    fun stringValue(obj: Pointer?): String? = obj?.let { send(it, "UTF8String")?.getString(0, "UTF-8") }

    /**
     * Defines (once per process) an NSObject subclass whose [selectors] all dispatch to [imp], with the given
     * Objective-C type encoding. Returns the class; [imp] must stay strongly reachable for the process lifetime.
     */
    fun defineClass(name: String, selectors: List<String>, imp: Callback, types: String): Pointer? {
        cls(name)?.let { return it }
        val created = allocateClassPair.invokePointer(arrayOf(cls("NSObject"), name, 0L)) ?: return cls(name)
        selectors.forEach { addMethod.invokeInt(arrayOf(created, sel(it), imp, types)) }
        registerClassPair.invokeVoid(arrayOf(created))
        return created
    }

    /** Runs [block] inside an autorelease pool, as threads other than AppKit's main thread have none of their own. */
    inline fun <T> autoreleasePool(block: () -> T): T {
        val pool = pushPool()
        try {
            return block()
        } finally {
            popPool(pool)
        }
    }

    @PublishedApi internal fun pushPool(): Pointer? = poolPush.invokePointer(emptyArray())

    @PublishedApi internal fun popPool(pool: Pointer?) = poolPop.invokeVoid(arrayOf(pool))
}
