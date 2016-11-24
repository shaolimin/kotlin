/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.allopen.ide

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.kotlin.allopen.AbstractAllOpenDeclarationAttributeAltererExtension
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.psi.KtModifierListOwner
import java.util.*

val ALL_OPEN_ANNOTATIONS = Key<List<String>>("org.jetbrains.kotlin.allopen.AllOpenAnnotations")

class IdeAllOpenDeclarationAttributeAltererExtension(val project: Project) : AbstractAllOpenDeclarationAttributeAltererExtension() {
    private val cache: CachedValue<WeakHashMap<Module, List<String>>> = cachedValue(project) {
        CachedValueProvider.Result.create(WeakHashMap<Module, List<String>>(), ProjectRootModificationTracker.getInstance(project))
    }

    override fun getAllOpenAnnotationFqNames(modifierListOwner: KtModifierListOwner): List<String> {
        val project = modifierListOwner.project

        val virtualFile = modifierListOwner.containingFile?.virtualFile ?: return emptyList()
        val module = ProjectRootManager.getInstance(project).fileIndex.getModuleForFile(virtualFile) ?: return emptyList()

        return cache.value.getOrPut(module) {
            val kotlinFacet = KotlinFacet.get(module) ?: return@getOrPut emptyList()
            kotlinFacet.getUserData(ALL_OPEN_ANNOTATIONS) ?: emptyList()
        }
    }

    private fun <T> cachedValue(project: Project, result: () -> CachedValueProvider.Result<T>): CachedValue<T> {
        return CachedValuesManager.getManager(project).createCachedValue(result, false)
    }
}