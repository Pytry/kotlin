/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.compiler.backend.ir

import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationPluginContext
import org.jetbrains.kotlinx.serialization.compiler.resolve.KSerializerDescriptorResolver

// This doesn't support annotation arguments of type KClass and Array<KClass> because the codegen doesn't compute JVM signatures for
// such cases correctly (because inheriting from annotation classes is prohibited in Kotlin).
// Currently it results in an "accidental override" error where a method with return type KClass conflicts with the one with Class.
// TODO: support annotation properties of types KClass<...> and Array<KClass<...>>.
class SerialInfoImplJvmIrGenerator(
    private val irClass: IrClass,
    private val context: SerializationPluginContext,
) : IrBuilderExtension {
    override val compilerContext: SerializationPluginContext
        get() = context

    private val jvmNameClass = context.irFactory.buildClass {
        this.name = DescriptorUtils.JVM_NAME.shortName()
        this.kind = ClassKind.ANNOTATION_CLASS
    }.apply {
        createImplicitParameterDeclarationWithWrappedDescriptor()
        parent = IrExternalPackageFragmentImpl.createEmptyExternalPackageFragment(
            context.moduleDescriptor, DescriptorUtils.JVM_NAME.parent()
        )
        addConstructor().apply {
            addValueParameter("name", context.irBuiltIns.stringType)
        }
    }

    private fun generate() {
        val properties = irClass.declarations.filterIsInstance<IrProperty>()
        if (properties.isEmpty()) return

        val startOffset = UNDEFINED_OFFSET
        val endOffset = UNDEFINED_OFFSET

        val ctor = irClass.addConstructor {
            visibility = DescriptorVisibilities.PUBLIC
        }
        val ctorBody = context.irFactory.createBlockBody(
            startOffset, endOffset, listOf(
                IrDelegatingConstructorCallImpl(
                    startOffset, endOffset, context.irBuiltIns.unitType, context.irBuiltIns.anyClass.constructors.single(),
                    typeArgumentsCount = 0, valueArgumentsCount = 0
                )
            )
        )
        ctor.body = ctorBody

        for (property in properties) {
            generateSimplePropertyWithBackingField(property.descriptor, irClass, false, Name.identifier("_" + property.name.asString()))

            val getter = property.getter!!
            getter.origin = SERIALIZABLE_SYNTHETIC_ORIGIN
            // Add JvmName annotation to property getters to force the resulting JVM method name for 'x' be 'x', instead of 'getX',
            // and to avoid having useless bridges for it generated in BridgeLowering.
            // Unfortunately, this results in an extra `@JvmName` annotation in the bytecode, but it shouldn't matter very much.
            getter.annotations += jvmName(property.name.asString())

            val field = property.backingField!!
            field.visibility = DescriptorVisibilities.PRIVATE
            field.origin = SERIALIZABLE_SYNTHETIC_ORIGIN

            val parameter = ctor.addValueParameter(property.name.asString(), getter.returnType)
            ctorBody.statements += IrSetFieldImpl(
                startOffset, endOffset, field.symbol,
                IrGetValueImpl(startOffset, endOffset, irClass.thisReceiver!!.symbol),
                IrGetValueImpl(startOffset, endOffset, parameter.symbol),
                context.irBuiltIns.unitType,
            )
        }
    }

    private fun jvmName(name: String): IrConstructorCall =
        IrConstructorCallImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, jvmNameClass.defaultType, jvmNameClass.constructors.single().symbol,
            typeArgumentsCount = 0, constructorTypeArgumentsCount = 0, valueArgumentsCount = 1,
        ).apply {
            putValueArgument(0, IrConstImpl.string(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.stringType, name))
        }

    companion object {
        fun generate(irClass: IrClass, context: SerializationPluginContext) {
            if (KSerializerDescriptorResolver.isSerialInfoImpl(irClass.descriptor))
                SerialInfoImplJvmIrGenerator(irClass, context).generate()
        }
    }
}
