/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:JvmName("KAnnotatedElements")

package kotlin.reflect.full

import kotlin.reflect.*

/**
 * Returns an annotation of the given type on this element.
 */
@SinceKotlin("1.1")
inline fun <reified T : Annotation> KAnnotatedElement.findAnnotation(): T? =
    @Suppress("UNCHECKED_CAST")
    annotations.firstOrNull { it is T } as T?

/**
 * Returns true if this element is annotated with an annotation of type [T].
 */
@SinceKotlin("1.4")
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@WasExperimental(ExperimentalStdlibApi::class)
inline fun <reified T : Annotation> KAnnotatedElement.hasAnnotation(): Boolean =
    findAnnotation<T>() != null
