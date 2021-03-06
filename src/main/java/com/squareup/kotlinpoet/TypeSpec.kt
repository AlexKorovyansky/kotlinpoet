/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.kotlinpoet

import com.squareup.kotlinpoet.KModifier.COMPANION
import com.squareup.kotlinpoet.KModifier.EXPECT
import com.squareup.kotlinpoet.KModifier.PUBLIC
import java.lang.reflect.Type
import kotlin.reflect.KClass

/** A generated class, interface, or enum declaration.  */
class TypeSpec private constructor(builder: TypeSpec.Builder) {
  val kind = builder.kind
  val name = builder.name
  val anonymousTypeArguments = builder.anonymousTypeArguments
  val kdoc = builder.kdoc.build()
  val annotations = builder.annotations.toImmutableList()
  val modifiers = builder.modifiers.toImmutableSet()
  val typeVariables = builder.typeVariables.toImmutableList()
  val companionObject = builder.companionObject
  val primaryConstructor = builder.primaryConstructor
  val superclass = builder.superclass
  val superclassConstructorParameters = builder.superclassConstructorParameters.toImmutableList()

  /**
   * Map of superinterfaces - entries with a null value represent a regular superinterface (with
   * no delegation), while non-null [CodeBlock] values represent delegates
   * for the corresponding [TypeSpec] interface (key) value
   */
  val superinterfaces = builder.superinterfaces.toImmutableMap()
  val enumConstants = builder.enumConstants.toImmutableMap()
  val propertySpecs = builder.propertySpecs.toImmutableList()
  val initializerBlock = builder.initializerBlock.build()
  val funSpecs = builder.funSpecs.toImmutableList()
  val typeSpecs = builder.typeSpecs.toImmutableList()

  private val implicitPropertyModifiers = kind.implicitPropertyModifiers + when {
    KModifier.EXPECT in modifiers -> setOf(KModifier.EXPECT)
    else -> emptySet()
  }

  private val implicitFunctionModifiers = kind.implicitFunctionModifiers + when {
    KModifier.EXPECT in modifiers -> setOf(KModifier.EXPECT)
    else -> emptySet()
  }

  fun toBuilder(): Builder {
    val builder = Builder(kind, name, anonymousTypeArguments)
    builder.kdoc.add(kdoc)
    builder.annotations += annotations
    builder.modifiers += modifiers
    builder.typeVariables += typeVariables
    builder.superclass = superclass
    builder.superclassConstructorParameters += superclassConstructorParameters
    builder.enumConstants += enumConstants
    builder.propertySpecs += propertySpecs
    builder.funSpecs += funSpecs
    builder.typeSpecs += typeSpecs
    builder.initializerBlock.add(initializerBlock)
    builder.superinterfaces.putAll(superinterfaces)
    return builder
  }

  internal fun emit(codeWriter: CodeWriter, enumName: String?) {
    // Nested classes interrupt wrapped line indentation. Stash the current wrapping state and put
    // it back afterwards when this type is complete.
    val previousStatementLine = codeWriter.statementLine
    codeWriter.statementLine = -1

    val constructorProperties: Map<String, PropertySpec> = constructorProperties()

    try {
      if (enumName != null) {
        codeWriter.emitKdoc(kdoc)
        codeWriter.emitAnnotations(annotations, false)
        codeWriter.emitCode("%L", enumName)
        if (anonymousTypeArguments!!.formatParts.isNotEmpty()) {
          codeWriter.emit("(")
          codeWriter.emitCode(anonymousTypeArguments)
          codeWriter.emit(")")
        }
        if (hasNoBody) {
          return // Avoid unnecessary braces "{}".
        }
        codeWriter.emit(" {\n")
      } else if (anonymousTypeArguments != null) {

        codeWriter.emitCode("object")
        val supertype = if (superclass != ANY) {
          listOf(CodeBlock.of(" %T(%L)", superclass, anonymousTypeArguments))
        } else {
          listOf()
        }

        val allSuperTypes = supertype + if (superinterfaces.isNotEmpty())
          superinterfaces.keys.map { CodeBlock.of(" %T", it) } else
          emptyList()

        if (allSuperTypes.isNotEmpty()) {
          codeWriter.emitCode(" :")
          codeWriter.emitCode(allSuperTypes.joinToCode(","))
        }
        if (hasNoBody) {
          codeWriter.emit(" { }")
          return
        }
        codeWriter.emit(" {\n")
      } else {
        codeWriter.emitKdoc(kdoc)
        codeWriter.emitAnnotations(annotations, false)
        codeWriter.emitModifiers(modifiers, setOf(PUBLIC))
        codeWriter.emit(kind.declarationKeyword)
        if (name != null) {
          codeWriter.emitCode(" %L", name)
        }
        codeWriter.emitTypeVariables(typeVariables)
        codeWriter.emitWhereBlock(typeVariables)

        primaryConstructor?.let {
          var useKeyword = false
          var emittedAnnotations = false

          if (it.annotations.isNotEmpty()) {
            codeWriter.emit(" ")
            codeWriter.emitAnnotations(it.annotations, true)
            useKeyword = true
            emittedAnnotations = true
          }

          if (it.modifiers.isNotEmpty()) {
            if (!emittedAnnotations) codeWriter.emit(" ")
            codeWriter.emitModifiers(it.modifiers)
            useKeyword = true
          }

          if (useKeyword) {
            codeWriter.emit("constructor")
          }

          it.parameters.emit(codeWriter) { param ->
            val property = constructorProperties[param.name]
            if (property != null) {
              property.emit(codeWriter, setOf(PUBLIC), withInitializer = false, inline = true)
              param.emitDefaultValue(codeWriter)
            } else {
              param.emit(codeWriter)
            }
          }
        }

        val types = listOf(superclass).filter { it != ANY }.map {
          if (primaryConstructor != null || funSpecs.none { it.isConstructor }) {
            CodeBlock.of("%T(%L)", it, superclassConstructorParameters.joinToCode())
          } else {
            CodeBlock.of("%T", it)
          }
        }
        val superTypes = types + superinterfaces.entries.map { (type, init) ->
            if (init == null)  CodeBlock.of("%T", type) else CodeBlock.of("%T by $init", type)
        }

        if (superTypes.isNotEmpty()) {
          codeWriter.emitCode(superTypes.joinToCode(separator = ",%W", prefix = " : "))
        }

        if (hasNoBody) {
          codeWriter.emit("\n")
          return // Avoid unnecessary braces "{}".
        }
        if (kind != Kind.ANNOTATION) {
          codeWriter.emit(" {\n")
        }
      }

      codeWriter.pushType(this)
      codeWriter.indent()
      var firstMember = true
      val i = enumConstants.entries.iterator()
      while (i.hasNext()) {
        val enumConstant = i.next()
        if (!firstMember) codeWriter.emit("\n")
        enumConstant.value
            .emit(codeWriter, enumConstant.key)
        firstMember = false
        if (i.hasNext()) {
          codeWriter.emit(",\n")
        } else if (propertySpecs.isNotEmpty() || funSpecs.isNotEmpty() || typeSpecs.isNotEmpty()) {
          codeWriter.emit(";\n")
        } else {
          codeWriter.emit("\n")
        }
      }

      // Non-static properties.
      for (propertySpec in propertySpecs) {
        if (constructorProperties.containsKey(propertySpec.name)) {
          continue
        }
        if (!firstMember) codeWriter.emit("\n")
        propertySpec.emit(codeWriter, implicitPropertyModifiers)
        firstMember = false
      }

      if (primaryConstructor != null && primaryConstructor.body.isNotEmpty()) {
        codeWriter.emit("init {\n")
        codeWriter.indent()
        codeWriter.emitCode(primaryConstructor.body)
        codeWriter.unindent()
        codeWriter.emit("}\n")
      }

      // Initializer block.
      if (initializerBlock.isNotEmpty()) {
        if (!firstMember) codeWriter.emit("\n")
        codeWriter.emitCode(initializerBlock)
        firstMember = false
      }

      // Constructors.
      for (funSpec in funSpecs) {
        if (!funSpec.isConstructor) continue
        if (!firstMember) codeWriter.emit("\n")
        funSpec.emit(codeWriter, name, implicitFunctionModifiers)
        firstMember = false
      }

      // Functions (static and non-static).
      for (funSpec in funSpecs) {
        if (funSpec.isConstructor) continue
        if (!firstMember) codeWriter.emit("\n")
        funSpec.emit(codeWriter, name, implicitFunctionModifiers)
        firstMember = false
      }

      // Types.
      for (typeSpec in typeSpecs) {
        if (!firstMember) codeWriter.emit("\n")
        typeSpec.emit(codeWriter, null)
        firstMember = false
      }

      companionObject?.emit(codeWriter, null)

      codeWriter.unindent()
      codeWriter.popType()

      if (kind != Kind.ANNOTATION) {
        codeWriter.emit("}")
      }
      if (enumName == null && anonymousTypeArguments == null) {
        codeWriter.emit("\n") // If this type isn't also a value, include a trailing newline.
      }
    } finally {
      codeWriter.statementLine = previousStatementLine
    }
  }

  /** Returns the properties that can be declared inline as constructor parameters. */
  private fun constructorProperties(): Map<String, PropertySpec> {
    if (primaryConstructor == null) return emptyMap()

    val result: MutableMap<String, PropertySpec> = LinkedHashMap()
    for (property in propertySpecs) {
      val parameter = primaryConstructor.parameter(property.name) ?: continue
      if (parameter.type != property.type) continue
      if (CodeBlock.of("%N", parameter) != property.initializer) continue
      result[property.name] = property
    }
    return result
  }

  private val hasNoBody: Boolean
    get() {
      if (kind == Kind.ANNOTATION) {
        return true
      }
      if (propertySpecs.isNotEmpty()) {
        val constructorProperties = constructorProperties()
        for (propertySpec in propertySpecs) {
          if (!constructorProperties.containsKey(propertySpec.name)) {
            return false
          }
        }
      }
      return companionObject == null &&
          enumConstants.isEmpty() &&
          initializerBlock.isEmpty() &&
          (primaryConstructor?.body?.isEmpty() ?: true) &&
          funSpecs.isEmpty() &&
          typeSpecs.isEmpty()
    }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null) return false
    if (javaClass != other.javaClass) return false
    return toString() == other.toString()
  }

  override fun hashCode() = toString().hashCode()

  override fun toString() = buildString { emit(CodeWriter(this), null) }

  enum class Kind(
    internal val declarationKeyword: String,
    internal val implicitPropertyModifiers: Set<KModifier>,
    internal val implicitFunctionModifiers: Set<KModifier>
  ) {
    CLASS(
        "class",
        setOf(KModifier.PUBLIC),
        setOf(KModifier.PUBLIC)),

    OBJECT(
        "object",
        setOf(KModifier.PUBLIC),
        setOf(KModifier.PUBLIC)),

    INTERFACE(
        "interface",
        setOf(KModifier.PUBLIC),
        setOf(KModifier.PUBLIC, KModifier.ABSTRACT)),

    ENUM(
        "enum class",
        setOf(KModifier.PUBLIC),
        setOf(KModifier.PUBLIC)),

    ANNOTATION(
        "annotation class",
        emptySet(),
        setOf(KModifier.PUBLIC, KModifier.ABSTRACT));
  }

  class Builder internal constructor(
    internal val kind: Kind,
    internal val name: String?,
    internal val anonymousTypeArguments: CodeBlock?
  ) {
    internal val kdoc = CodeBlock.builder()
    internal val annotations = mutableListOf<AnnotationSpec>()
    internal val modifiers = mutableListOf<KModifier>()
    internal val typeVariables = mutableListOf<TypeVariableName>()
    internal var primaryConstructor: FunSpec? = null
    internal var companionObject: TypeSpec? = null
    internal var superclass: TypeName = ANY
    internal val superclassConstructorParameters = mutableListOf<CodeBlock>()
    internal val superinterfaces = mutableMapOf<TypeName, CodeBlock?>()
    internal val enumConstants = mutableMapOf<String, TypeSpec>()
    internal val propertySpecs = mutableListOf<PropertySpec>()
    internal val initializerBlock = CodeBlock.builder()
    internal val funSpecs = mutableListOf<FunSpec>()
    internal val typeSpecs = mutableListOf<TypeSpec>()

    init {
      require(name == null || name.isName) { "not a valid name: $name" }
    }

    fun addKdoc(format: String, vararg args: Any) = apply {
      kdoc.add(format, *args)
    }

    fun addKdoc(block: CodeBlock) = apply {
      kdoc.add(block)
    }

    fun addAnnotations(annotationSpecs: Iterable<AnnotationSpec>) = apply {
      annotations += annotationSpecs
    }

    fun addAnnotation(annotationSpec: AnnotationSpec) = apply {
      annotations += annotationSpec
    }

    fun addAnnotation(annotation: ClassName)
        = addAnnotation(AnnotationSpec.builder(annotation).build())

    fun addAnnotation(annotation: Class<*>) = addAnnotation(annotation.asClassName())

    fun addAnnotation(annotation: KClass<*>) = addAnnotation(annotation.asClassName())

    fun addModifiers(vararg modifiers: KModifier) = apply {
      check(anonymousTypeArguments == null) { "forbidden on anonymous types." }
      this.modifiers += modifiers
    }

    fun addTypeVariables(typeVariables: Iterable<TypeVariableName>) = apply {
      check(anonymousTypeArguments == null) { "forbidden on anonymous types." }
      this.typeVariables += typeVariables
    }

    fun addTypeVariable(typeVariable: TypeVariableName) = apply {
      check(anonymousTypeArguments == null) { "forbidden on anonymous types." }
      typeVariables += typeVariable
    }

    fun companionObject(companionObject: TypeSpec) = apply {
      check(kind.isOneOf(Kind.CLASS, Kind.INTERFACE)) { "$kind can't have a companion object" }
      require(companionObject.kind == Kind.OBJECT && COMPANION in companionObject.modifiers) {
        "expected a companion object class but was $kind "
      }
      this.companionObject = companionObject
    }

    fun primaryConstructor(primaryConstructor: FunSpec?) = apply {
      check(kind.isOneOf(Kind.CLASS, Kind.ENUM, Kind.ANNOTATION)) {
        "$kind can't have initializer blocks"
      }
      if (primaryConstructor != null) {
        require(primaryConstructor.isConstructor) {
          "expected a constructor but was ${primaryConstructor.name}"
        }
      }
      this.primaryConstructor = primaryConstructor
    }

    fun superclass(superclass: TypeName) = apply {
      ensureCanHaveSuperclass()
      check(this.superclass === ANY) { "superclass already set to ${this.superclass}" }
      this.superclass = superclass
    }

    private fun ensureCanHaveSuperclass() {
      check(kind.isOneOf(Kind.CLASS, Kind.OBJECT)) {
        "only classes can have super classes, not $kind"
      }
    }

    fun superclass(superclass: Type) = superclass(superclass.asTypeName())

    fun superclass(superclass: KClass<*>) = superclass(superclass.asTypeName())

    fun addSuperclassConstructorParameter(format: String, vararg args: Any) = apply {
      addSuperclassConstructorParameter(CodeBlock.of(format, *args))
    }

    fun addSuperclassConstructorParameter(codeBlock: CodeBlock) = apply {
      ensureCanHaveSuperclass()
      this.superclassConstructorParameters += codeBlock
    }

    fun addSuperinterfaces(superinterfaces: Iterable<TypeName>) = apply {
      this.superinterfaces.putAll(superinterfaces.map {
        Pair(it, null)
      })
    }

    fun addSuperinterface(superinterface: TypeName, delegate: CodeBlock = CodeBlock.EMPTY) = apply {
      if (delegate.isEmpty()) {
        this.superinterfaces.put(superinterface, null)
      } else {
        require(kind == Kind.CLASS) {
          "delegation only allowed for classes (found $kind '$name')"
        }
        require(!superinterface.nullable) {
          "expected non-nullable type but was '${superinterface.asNonNullable()}'"
        }
        require(this.superinterfaces[superinterface] == null) {
          "'$name' can not delegate to $superinterface by $delegate with existing declaration by " +
              "${this.superinterfaces[superinterface]}"
        }
        this.superinterfaces[superinterface] = delegate
      }
    }

    fun addSuperinterface(superinterface: Type, delegate: CodeBlock = CodeBlock.EMPTY)
        = addSuperinterface(superinterface.asTypeName(), delegate)

    fun addSuperinterface(superinterface: KClass<*>, delegate: CodeBlock = CodeBlock.EMPTY)
        = addSuperinterface(superinterface.asTypeName(), delegate)

    fun addSuperinterface(superinterface: KClass<*>, constructorParameterName: String) =
        addSuperinterface(superinterface.asTypeName(), constructorParameterName)

    fun addSuperinterface(superinterface: TypeName, constructorParameter: String) = apply {
      requireNotNull(primaryConstructor) {
        "delegating to constructor parameter requires not-null constructor"
      }
      val parameter = primaryConstructor?.parameter(constructorParameter)
      requireNotNull(parameter) {
        "no such constructor parameter '$constructorParameter' to delegate to for type '$name'"
      }
      addSuperinterface(superinterface, CodeBlock.of(constructorParameter))
    }

    @JvmOverloads fun addEnumConstant(
      name: String,
      typeSpec: TypeSpec = anonymousClassBuilder("").build()
    ) = apply {
      check(kind == Kind.ENUM) { "${this.name} is not enum" }
      require(typeSpec.anonymousTypeArguments != null) {
        "enum constants must have anonymous type arguments"
      }
      require(name.isName) { "not a valid enum constant: $name" }
      enumConstants.put(name, typeSpec)
    }

    fun addProperties(propertySpecs: Iterable<PropertySpec>) = apply {
      propertySpecs.map(this::addProperty)
    }

    fun addProperty(propertySpec: PropertySpec) = apply {
      if (EXPECT in modifiers) {
        require(propertySpec.initializer == null) {
          "properties in expect classes can't have initializers"
        }
        require(propertySpec.getter == null && propertySpec.setter == null) {
          "properties in expect classes can't have getters and setters"
        }
      }
      propertySpecs += propertySpec
    }

    fun addProperty(name: String, type: TypeName, vararg modifiers: KModifier)
        = addProperty(PropertySpec.builder(name, type, *modifiers).build())

    fun addProperty(name: String, type: Type, vararg modifiers: KModifier)
        = addProperty(name, type.asTypeName(), *modifiers)

    fun addProperty(name: String, type: KClass<*>, vararg modifiers: KModifier)
        = addProperty(name, type.asTypeName(), *modifiers)

    fun addInitializerBlock(block: CodeBlock) = apply {
      check(kind.isOneOf(Kind.CLASS, Kind.OBJECT, Kind.ENUM)) {
        "$kind can't have initializer blocks"
      }
      check(EXPECT !in modifiers) {
        "expect $kind can't have initializer blocks"
      }
      initializerBlock.add("init {\n")
          .indent()
          .add(block)
          .unindent()
          .add("}\n")
    }

    fun addFunctions(funSpecs: Iterable<FunSpec>) = apply {
      funSpecs.forEach { addFunction(it) }
    }

    fun addFunction(funSpec: FunSpec) = apply {
      when {
        kind == Kind.INTERFACE -> {
          requireNoneOf(funSpec.modifiers, KModifier.INTERNAL, KModifier.PROTECTED)
          requireNoneOrOneOf(funSpec.modifiers, KModifier.ABSTRACT, KModifier.PRIVATE)
        }
        kind == Kind.ANNOTATION -> require(funSpec.modifiers == kind.implicitFunctionModifiers) {
          "$kind $name.${funSpec.name} requires modifiers ${kind.implicitFunctionModifiers}"
        }
        EXPECT in modifiers -> require(funSpec.body.isEmpty()) {
          "functions in expect classes can't have bodies"
        }
      }
      funSpecs += funSpec
    }

    fun addTypes(typeSpecs: Iterable<TypeSpec>) = apply {
      this.typeSpecs += typeSpecs
    }

    fun addType(typeSpec: TypeSpec) = apply {
      typeSpecs += typeSpec
    }

    fun build(): TypeSpec {
      require(kind != Kind.ENUM || enumConstants.isNotEmpty()) {
        "at least one enum constant is required for $name"
      }

      val isAbstract = modifiers.contains(KModifier.ABSTRACT) || kind != Kind.CLASS
      for (funSpec in funSpecs) {
        require(isAbstract || !funSpec.modifiers.contains(KModifier.ABSTRACT)) {
          "non-abstract type $name cannot declare abstract function ${funSpec.name}"
        }
      }

      if (primaryConstructor == null) {
        require(funSpecs.none { it.isConstructor } || superclassConstructorParameters.isEmpty()) {
          "types without a primary constructor cannot specify secondary constructors and superclass constructor parameters"
        }
      }

      return TypeSpec(this)
    }
  }

  companion object {
    @JvmStatic fun classBuilder(name: String) = Builder(Kind.CLASS, name, null)

    @JvmStatic fun classBuilder(className: ClassName) = classBuilder(className.simpleName())

    @JvmStatic fun expectClassBuilder(name: String) = Builder(Kind.CLASS, name, null).apply {
      addModifiers(KModifier.EXPECT)
    }

    @JvmStatic fun expectClassBuilder(className: ClassName) = expectClassBuilder(className.simpleName())

    @JvmStatic fun objectBuilder(name: String) = Builder(Kind.OBJECT, name, null)

    @JvmStatic fun objectBuilder(className: ClassName) = objectBuilder(className.simpleName())

    @JvmStatic @JvmOverloads fun companionObjectBuilder(name: String? = null) =
        Builder(Kind.OBJECT, name, null).apply {
          addModifiers(KModifier.COMPANION)
        }

    @JvmStatic fun interfaceBuilder(name: String) = Builder(Kind.INTERFACE, name, null)

    @JvmStatic fun interfaceBuilder(className: ClassName) = interfaceBuilder(className.simpleName())

    @JvmStatic fun enumBuilder(name: String) = Builder(Kind.ENUM, name, null)

    @JvmStatic fun enumBuilder(className: ClassName) = enumBuilder(className.simpleName())

    @JvmStatic fun anonymousClassBuilder(): Builder {
      return anonymousClassBuilder("")
    }

    @JvmStatic fun anonymousClassBuilder(typeArgumentsFormat: String, vararg args: Any): Builder {
      return Builder(Kind.CLASS, null, CodeBlock.builder()
          .add(typeArgumentsFormat, *args)
          .build())
    }

    @JvmStatic fun annotationBuilder(name: String) = Builder(Kind.ANNOTATION, name, null)

    @JvmStatic fun annotationBuilder(className: ClassName)
        = annotationBuilder(className.simpleName())
  }
}
