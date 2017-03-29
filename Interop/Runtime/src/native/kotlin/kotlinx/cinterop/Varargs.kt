package kotlinx.cinterop

private const val MAX_ARGUMENT_SIZE = 8


typealias FfiTypeKind = Int
// Also declared in Interop.cpp
const val FFI_TYPE_KIND_VOID: FfiTypeKind = 0
const val FFI_TYPE_KIND_SINT8: FfiTypeKind = 1
const val FFI_TYPE_KIND_SINT16: FfiTypeKind = 2
const val FFI_TYPE_KIND_SINT32: FfiTypeKind = 3
const val FFI_TYPE_KIND_SINT64: FfiTypeKind = 4
const val FFI_TYPE_KIND_FLOAT: FfiTypeKind = 5
const val FFI_TYPE_KIND_DOUBLE: FfiTypeKind = 6
const val FFI_TYPE_KIND_POINTER: FfiTypeKind = 7

private tailrec fun convertArgument(
        argument: Any?, isVariadic: Boolean, location: NativePointed,
        additionalPlacement: NativePlacement
): FfiTypeKind = when (argument) {
    is CValuesRef<*>? -> {
        location.reinterpret<CPointerVar<*>>().value = argument?.getPointer(additionalPlacement)
        FFI_TYPE_KIND_POINTER
    }

    is String -> {
        location.reinterpret<CPointerVar<*>>().value = argument.cstr.getPointer(additionalPlacement)
        FFI_TYPE_KIND_POINTER
    }

    is Int -> {
        location.reinterpret<CInt32Var>().value = argument
        FFI_TYPE_KIND_SINT32
    }

    is Long -> {
        location.reinterpret<CInt64Var>().value = argument
        FFI_TYPE_KIND_SINT64
    }

    is Byte -> if (isVariadic) {
        convertArgument(argument.toInt(), isVariadic, location, additionalPlacement)
    } else {
        location.reinterpret<CInt8Var>().value = argument
        FFI_TYPE_KIND_SINT8
    }

    is Short -> if (isVariadic) {
        convertArgument(argument.toInt(), isVariadic, location, additionalPlacement)
    } else {
        location.reinterpret<CInt16Var>().value = argument
        FFI_TYPE_KIND_SINT16
    }

    is Double -> {
        location.reinterpret<CFloat64Var>().value = argument
        FFI_TYPE_KIND_DOUBLE
    }

    is Float -> if (isVariadic) {
        convertArgument(argument.toDouble(), isVariadic, location, additionalPlacement)
    } else {
        location.reinterpret<CFloat32Var>().value = argument
        FFI_TYPE_KIND_FLOAT
    }

    is CEnum -> convertArgument(argument.value, isVariadic, location, additionalPlacement)

    else -> throw Error("unsupported argument: $argument")
}

fun callWithVarargs(codePtr: NativePtr, returnValuePtr: NativePtr, returnTypeKind: FfiTypeKind,
                    fixedArguments: Array<out Any?>, variadicArguments: Array<out Any?>,
                    argumentsPlacement: NativePlacement) {

    val totalArgumentsNumber = fixedArguments.size + variadicArguments.size

    // All supported arguments take at most 8 bytes each:
    val argumentsStorage = argumentsPlacement.allocArray<CInt64Var>(totalArgumentsNumber)
    val arguments = argumentsPlacement.allocArray<CPointerVar<*>>(totalArgumentsNumber)
    val types = argumentsPlacement.allocArray<CPointerVar<*>>(totalArgumentsNumber)

    var index = 0

    inline fun addArgument(argument: Any?, isVariadic: Boolean) {
        val storage = argumentsStorage[index]
        val typeKind = convertArgument(argument, isVariadic = isVariadic,
                location = storage, additionalPlacement = argumentsPlacement)

        types[index].value = interpretCPointer<COpaque>(nativeNullPtr + typeKind.toLong())
        arguments[index].value = storage.ptr

        ++index
    }

    for (argument in fixedArguments) {
        addArgument(argument, isVariadic = false)
    }

    for (argument in variadicArguments) {
        addArgument(argument, isVariadic = true)
    }

    assert (index == totalArgumentsNumber)

    callWithVarargs(codePtr, returnValuePtr, returnTypeKind, arguments.rawValue, types.rawValue,
            fixedArguments.size, totalArgumentsNumber)
}

@SymbolName("callWithVarargs")
private external fun callWithVarargs(codePtr: NativePtr, returnValuePtr: NativePtr, returnTypeKind: FfiTypeKind,
                                     arguments: NativePtr, argumentTypeKinds: NativePtr,
                                     fixedArgumentsNumber: Int, totalArgumentsNumber: Int)