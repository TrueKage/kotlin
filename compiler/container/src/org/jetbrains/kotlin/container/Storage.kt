/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.container

import com.intellij.util.containers.MultiMap
import java.io.Closeable
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashSet

public enum class ComponentStorageState {
    Initial,
    Initialized,
    Disposing,
    Disposed
}

public class ComponentStorage(val myId: String) : ValueResolver {
    var state = ComponentStorageState.Initial
    val registry = ComponentRegistry()
    val descriptors = LinkedHashSet<ComponentDescriptor>()
    val dependencies = MultiMap.createLinkedSet<ComponentDescriptor, Class<*>>()

    override fun resolve(request: Class<*>, context: ValueResolveContext): ValueDescriptor? {
        if (state == ComponentStorageState.Initial)
            throw ContainerConsistencyException("Container was not composed before resolving")

        val entry = registry.tryGetEntry(request)
        if (entry.isNotEmpty()) {
            registerDependency(request, context)

            return entry.singleOrNull()
        }
        return null
    }

    private fun registerDependency(request: Class<*>, context: ValueResolveContext) {
        if (context is ComponentResolveContext) {
            val descriptor = context.requestingDescriptor
            if (descriptor is ComponentDescriptor) {
                dependencies.putValue(descriptor, request)
            }
        }
    }

    public fun resolveMultiple(request: Class<*>, context: ValueResolveContext): Iterable<ValueDescriptor> {
        registerDependency(request, context)
        return registry.tryGetEntry(request)
    }

    public fun registerDescriptors(context: ComponentResolveContext, items: List<ComponentDescriptor>) {
        if (state == ComponentStorageState.Disposed) {
            throw ContainerConsistencyException("Cannot register descriptors in $state state")
        }

        for (descriptor in items)
            descriptors.add(descriptor)

        if (state == ComponentStorageState.Initialized)
            composeDescriptors(context, items)

    }

    public fun compose(context: ComponentResolveContext) {
        if (state != ComponentStorageState.Initial)
            throw ContainerConsistencyException("Container $myId was already composed.")

        state = ComponentStorageState.Initialized
        composeDescriptors(context, descriptors)
    }

    private fun composeDescriptors(context: ComponentResolveContext, descriptors: Collection<ComponentDescriptor>) {
        if (descriptors.isEmpty()) return

        registry.addAll(descriptors)

        val implicits = inspectDependenciesAndRegisterImplicits(context, descriptors)

        injectProperties(context, descriptors + implicits)
    }

    private fun injectProperties(context: ComponentResolveContext, components: Collection<ComponentDescriptor>) {
        for (component in components) {
            if (component.shouldInjectProperties) {
                injectProperties(component.getValue(), context)
            }
        }
    }

    private fun inspectDependenciesAndRegisterImplicits(context: ComponentResolveContext, descriptors: Collection<ComponentDescriptor>): LinkedHashSet<ComponentDescriptor> {
        val implicits = LinkedHashSet<ComponentDescriptor>()
        val visitedTypes = HashSet<Class<*>>()
        for (descriptor in descriptors) {
            registerImplicits(context, descriptor, visitedTypes, implicits)
        }
        registry.addAll(implicits)
        return implicits
    }

    private fun registerImplicits(
            context: ComponentResolveContext, descriptor: ComponentDescriptor,
            visitedTypes: HashSet<Class<*>>, implicitDescriptors: LinkedHashSet<ComponentDescriptor>
    ) {
        val dependencies = descriptor.getDependencies(context)
        for (type in dependencies) {
            if (!visitedTypes.add(type)) continue

            val entry = registry.tryGetEntry(type)
            if (entry.isEmpty()) {
                if (!Modifier.isAbstract(type.getModifiers()) && !type.isPrimitive()) {
                    val implicitDescriptor = SingletonTypeComponentDescriptor(context.container, type)
                    implicitDescriptors.add(implicitDescriptor)
                    registerImplicits(context, implicitDescriptor, visitedTypes, implicitDescriptors)
                }
            }
        }
    }

    private fun injectProperties(instance: Any, context: ValueResolveContext) {
        val classInfo = instance.javaClass.getInfo()

        classInfo.setterInfos.forEach { setterInfo ->
            val methodBinding = setterInfo.method.bindToMethod(context)
            methodBinding.invoke(instance)
        }
    }

    public fun dispose() {
        if (state != ComponentStorageState.Initialized) {
            if (state == ComponentStorageState.Initial)
                return // it is valid to dispose container which was not initialized
            throw ContainerConsistencyException("Component container cannot be disposed in the $state state.")
        }

        state = ComponentStorageState.Disposing
        val disposeList = getDescriptorsInDisposeOrder()
        for (descriptor in disposeList)
            disposeDescriptor(descriptor)
        state = ComponentStorageState.Disposed
    }

    fun getDescriptorsInDisposeOrder(): List<ComponentDescriptor> {
        return topologicalSort(descriptors) {
            val dependent = ArrayList<ComponentDescriptor>()
            for (interfaceType in dependencies[it]) {
                for (dependency in registry.tryGetEntry(interfaceType)) {
                    dependent.add(dependency)
                }
            }
            dependent
        }
    }

    fun disposeDescriptor(descriptor: ComponentDescriptor) {
        if (descriptor is Closeable)
            descriptor.close()
    }
}